package com.example.audio

import android.util.Log
import com.example.model.AudioChunk
import com.example.model.AudioFormatOption
import com.example.storage.AudioChunkWriter
import com.example.storage.AudioChunkWriterFactory
import com.example.storage.AudioRemuxer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated background disk writer owning all chunk open/write/sync/finalize/remux I/O.
 *
 * Keeps [VoxRecordingService]'s high-priority audio capture thread completely non-blocking during
 * flash `fsync()` calls and 15-minute continuous-speech chunk rotations, preventing dropped PCM
 * frames even under slow storage I/O.
 */
class AsyncChunkDiskWriter(
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    private val onChunkFinalized: (AudioChunk) -> Unit
) {
    private sealed interface Command {
        data class OpenChunk(
            val directory: File,
            val chunkIndex: Int,
            val format: AudioFormatOption,
            val startTimeMillis: Long,
            val preRollSamples: ShortArray,
            val firstFrame: ShortArray
        ) : Command

        data class WriteFrame(val samples: ShortArray) : Command
        data object SyncToDisk : Command
        data object FinalizeChunk : Command
        data class RotateChunk(
            val directory: File,
            val nextChunkIndex: Int,
            val format: AudioFormatOption,
            val startTimeMillis: Long,
            val firstFrame: ShortArray
        ) : Command

        data class Shutdown(val latch: CountDownLatch) : Command
    }

    private val queue = LinkedBlockingQueue<Command>(MAX_QUEUED_COMMANDS)
    private val running = AtomicBoolean(false)
    private val activeChunkDurationMs = AtomicLong(0L)
    private val hasActiveChunk = AtomicBoolean(false)
    private val droppedFramesCounter = AtomicLong(0L)

    private var workerThread: Thread? = null
    private var activeWriter: AudioChunkWriter? = null
    private var activeChunkIndex: Int = 0
    private var activeChunkStartTime: Long = 0L
    private var activeRequestedFormat: AudioFormatOption = AudioFormatOption.OGG_OPUS

    val currentChunkDurationMillis: Long
        get() = activeChunkDurationMs.get()

    val isChunkOpen: Boolean
        get() = hasActiveChunk.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = Thread({
            while (true) {
                val cmd = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    if (!running.get() && queue.isEmpty()) break
                    continue
                }

                handleCommand(cmd)
                if (cmd is Command.Shutdown) {
                    break
                }
            }
            finalizeCurrentChunkInternal()
        }, "NTK_VOX-DiskWriter")
        workerThread = thread
        thread.start()
    }

    fun enqueueOpenChunk(
        directory: File,
        chunkIndex: Int,
        format: AudioFormatOption,
        startTimeMillis: Long,
        preRollSamples: ShortArray,
        firstFrame: ShortArray,
        firstFrameLength: Int
    ) {
        val copy = firstFrame.copyOf(firstFrameLength)
        enqueueControlCommand(
            Command.OpenChunk(
                directory = directory,
                chunkIndex = chunkIndex,
                format = format,
                startTimeMillis = startTimeMillis,
                preRollSamples = preRollSamples,
                firstFrame = copy
            )
        )
    }

    fun enqueueWriteFrame(samples: ShortArray, length: Int) {
        if (length <= 0) return
        val copy = samples.copyOf(length)
        if (!queue.offer(Command.WriteFrame(copy))) {
            val dropped = droppedFramesCounter.incrementAndGet()
            if (dropped == 1L || dropped % 50L == 0L) {
                Log.w(TAG, "Disk writer queue saturated; dropped $dropped audio frames")
            }
        }
    }

    fun enqueueSync() {
        queue.offer(Command.SyncToDisk)
    }

    fun enqueueRotateChunk(
        directory: File,
        nextChunkIndex: Int,
        format: AudioFormatOption,
        startTimeMillis: Long,
        firstFrame: ShortArray,
        firstFrameLength: Int
    ) {
        val copy = firstFrame.copyOf(firstFrameLength)
        enqueueControlCommand(
            Command.RotateChunk(
                directory = directory,
                nextChunkIndex = nextChunkIndex,
                format = format,
                startTimeMillis = startTimeMillis,
                firstFrame = copy
            )
        )
    }

    fun enqueueFinalizeChunk() {
        enqueueControlCommand(Command.FinalizeChunk)
    }

    /**
     * Drains all pending frames, finalizes and fsyncs any open chunk, and terminates the writer
     * thread cleanly. Safe to call from stopSession or ACTION_SHUTDOWN.
     */
    fun drainAndShutdown(timeoutMs: Long = 5_000L) {
        if (!running.getAndSet(false)) return
        val latch = CountDownLatch(1)
        enqueueControlCommand(Command.Shutdown(latch))
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        runCatching { workerThread?.join(1_000L) }
        workerThread = null
    }

    /**
     * Guarantees lifecycle commands (`OpenChunk`, `RotateChunk`, `FinalizeChunk`, `Shutdown`)
     * are never silently dropped if the queue fills during a flash I/O stall or slow encode.
     */
    private fun enqueueControlCommand(cmd: Command) {
        if (queue.offer(cmd)) return
        // Shed non-essential SyncToDisk entries first
        queue.removeAll { it is Command.SyncToDisk }
        if (queue.offer(cmd)) return
        // Drop one oldest queued audio frame if still completely full
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() is Command.WriteFrame) {
                iterator.remove()
                break
            }
        }
        if (!queue.offer(cmd)) {
            runCatching { queue.offer(cmd, 1_500L, TimeUnit.MILLISECONDS) }
        }
    }

    private fun handleCommand(cmd: Command) {
        when (cmd) {
            is Command.OpenChunk -> {
                finalizeCurrentChunkInternal()
                openChunkInternal(
                    directory = cmd.directory,
                    chunkIndex = cmd.chunkIndex,
                    format = cmd.format,
                    startTimeMillis = cmd.startTimeMillis,
                    preRollSamples = cmd.preRollSamples,
                    firstFrame = cmd.firstFrame
                )
            }

            is Command.WriteFrame -> {
                val writer = activeWriter ?: return
                runCatching {
                    writer.writePcm(cmd.samples, 0, cmd.samples.size)
                    activeChunkDurationMs.set(writer.durationMillis)
                }.onFailure { Log.e(TAG, "Chunk write failed", it) }
            }

            Command.SyncToDisk -> {
                runCatching { activeWriter?.syncToDisk() }
            }

            is Command.RotateChunk -> {
                finalizeCurrentChunkInternal()
                openChunkInternal(
                    directory = cmd.directory,
                    chunkIndex = cmd.nextChunkIndex,
                    format = cmd.format,
                    startTimeMillis = cmd.startTimeMillis,
                    preRollSamples = ShortArray(0),
                    firstFrame = cmd.firstFrame
                )
            }

            Command.FinalizeChunk -> {
                finalizeCurrentChunkInternal()
            }

            is Command.Shutdown -> {
                finalizeCurrentChunkInternal()
                cmd.latch.countDown()
            }
        }
    }

    private fun openChunkInternal(
        directory: File,
        chunkIndex: Int,
        format: AudioFormatOption,
        startTimeMillis: Long,
        preRollSamples: ShortArray,
        firstFrame: ShortArray
    ) {
        try {
            val baseName = String.format("part_%04d", chunkIndex)
            val writer = AudioChunkWriterFactory.createAndOpen(
                directory = directory,
                baseFileName = baseName,
                format = format,
                sampleRate = sampleRate,
                channels = channels
            )
            if (preRollSamples.isNotEmpty()) {
                writer.writePcm(preRollSamples, 0, preRollSamples.size)
            }
            if (firstFrame.isNotEmpty()) {
                writer.writePcm(firstFrame, 0, firstFrame.size)
            }
            activeWriter = writer
            activeChunkIndex = chunkIndex
            activeChunkStartTime = startTimeMillis
            activeRequestedFormat = format
            hasActiveChunk.set(true)
            activeChunkDurationMs.set(writer.durationMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open chunk #$chunkIndex", e)
            activeWriter = null
            hasActiveChunk.set(false)
            activeChunkDurationMs.set(0L)
        }
    }

    private fun finalizeCurrentChunkInternal() {
        val writer = activeWriter ?: return
        activeWriter = null
        hasActiveChunk.set(false)
        activeChunkDurationMs.set(0L)

        val result = runCatching { writer.closeAndFinalize() }.getOrNull() ?: return
        if (result.sizeBytes <= 0L || result.durationMillis <= 0L) {
            runCatching { result.file.delete() }
            return
        }

        // Losslessly remux crash-safe ADTS (.aac) into universal .m4a container upon finalization
        var finalFile = result.file
        var finalExt = result.actualExtension
        if (activeRequestedFormat == AudioFormatOption.M4A_AAC &&
            result.actualExtension.equals("aac", ignoreCase = true)
        ) {
            val m4aTarget = File(result.file.parentFile, "${result.file.nameWithoutExtension}.m4a")
            if (AudioRemuxer.remuxAdtsToM4a(listOf(result.file), m4aTarget)) {
                runCatching { result.file.delete() }
                finalFile = m4aTarget
                finalExt = "m4a"
            }
        }

        val chunk = AudioChunk(
            index = activeChunkIndex,
            fileName = finalFile.name,
            startTimeMillis = activeChunkStartTime,
            durationMillis = result.durationMillis,
            sizeBytes = if (finalFile.exists()) finalFile.length() else result.sizeBytes,
            actualExtension = finalExt
        )
        onChunkFinalized(chunk)
    }

    companion object {
        private const val TAG = "AsyncChunkDiskWriter"
        private const val MAX_QUEUED_COMMANDS = 1024
    }
}
