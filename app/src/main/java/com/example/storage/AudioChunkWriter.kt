package com.example.storage

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.example.model.AudioFormatOption
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ChunkFinalizeResult(
    val file: File,
    val actualExtension: String,
    val durationMillis: Long,
    val sizeBytes: Long
)

/**
 * Swappable audio chunk writer contract.
 * Implementations stream 16 kHz 16-bit mono PCM into crash-safe chunk files and fsync on demand.
 */
interface AudioChunkWriter {
    val fileExtension: String
    val durationMillis: Long
    val bytesWritten: Long

    fun open(outputFile: File, sampleRate: Int = 16_000, channels: Int = 1)
    fun writePcm(samples: ShortArray, offset: Int = 0, length: Int = samples.size)
    fun syncToDisk()
    fun closeAndFinalize(): ChunkFinalizeResult
}

/**
 * Factory creating the requested writer for [AudioFormatOption].
 * Uses native Android MediaCodec/MediaMuxer when supported, and falls back gracefully to
 * crash-safe [WavChunkWriter] if a device or emulator lacks a hardware/software codec.
 */
object AudioChunkWriterFactory {
    private const val TAG = "AudioChunkWriterFactory"

    fun createAndOpen(
        directory: File,
        baseFileName: String,
        format: AudioFormatOption,
        sampleRate: Int = 16_000,
        channels: Int = 1
    ): AudioChunkWriter {
        val preferred: AudioChunkWriter = when (format) {
            AudioFormatOption.OGG_OPUS -> OggOpusChunkWriter()
            AudioFormatOption.M4A_AAC -> AacAdtsChunkWriter()
            AudioFormatOption.MP3 -> Mp3ChunkWriter()
            AudioFormatOption.FLAC -> FlacChunkWriter()
            AudioFormatOption.WAV -> WavChunkWriter()
        }

        val targetFile = File(directory, "$baseFileName.${preferred.fileExtension}")
        return try {
            preferred.open(targetFile, sampleRate, channels)
            preferred
        } catch (e: Exception) {
            Log.w(TAG, "Preferred writer (${preferred.fileExtension}) unavailable; falling back to WAV", e)
            runCatching { if (targetFile.exists()) targetFile.delete() }
            val fallback = WavChunkWriter()
            val wavFile = File(directory, "$baseFileName.wav")
            fallback.open(wavFile, sampleRate, channels)
            fallback
        }
    }
}

/**
 * Crash-safe 16-bit PCM WAV chunk writer with 44-byte RIFF header patching and FileChannel.force(false).
 */
class WavChunkWriter : AudioChunkWriter {
    override val fileExtension: String = "wav"

    private var raf: RandomAccessFile? = null
    private var outputFile: File? = null
    private var sampleRate: Int = 16_000
    private var channels: Int = 1
    private var dataBytesWritten: Long = 0L
    private var totalSamplesWritten: Long = 0L
    private var scratchBytes = ByteArray(4096)

    override val durationMillis: Long
        get() = if (sampleRate > 0) (totalSamplesWritten * 1000L) / sampleRate else 0L

    override val bytesWritten: Long
        get() = HEADER_SIZE + dataBytesWritten

    override fun open(outputFile: File, sampleRate: Int, channels: Int) {
        this.outputFile = outputFile
        this.sampleRate = sampleRate
        this.channels = channels
        this.dataBytesWritten = 0L
        this.totalSamplesWritten = 0L

        outputFile.parentFile?.mkdirs()
        val file = RandomAccessFile(outputFile, "rw")
        file.setLength(0L)
        file.write(buildWavHeader(dataSize = 0L, sampleRate = sampleRate, channels = channels))
        file.channel.force(false)
        this.raf = file
    }

    override fun writePcm(samples: ShortArray, offset: Int, length: Int) {
        val file = raf ?: return
        if (length <= 0) return

        val byteCount = length * 2
        if (scratchBytes.size < byteCount) {
            scratchBytes = ByteArray(byteCount)
        }

        var byteIdx = 0
        for (i in offset until (offset + length)) {
            val sample = samples[i].toInt()
            scratchBytes[byteIdx++] = (sample and 0xFF).toByte()
            scratchBytes[byteIdx++] = ((sample shr 8) and 0xFF).toByte()
        }

        file.write(scratchBytes, 0, byteCount)
        dataBytesWritten += byteCount.toLong()
        totalSamplesWritten += (length / channels).toLong()
    }

    override fun syncToDisk() {
        val file = raf ?: return
        try {
            val currentPos = file.filePointer
            patchHeader(file, dataBytesWritten)
            file.seek(currentPos)
            file.channel.force(false)
        } catch (e: Exception) {
            Log.w(TAG, "Periodic WAV sync failed", e)
        }
    }

    override fun closeAndFinalize(): ChunkFinalizeResult {
        val file = raf
        val target = outputFile ?: File("")
        if (file != null) {
            try {
                patchHeader(file, dataBytesWritten)
                file.channel.force(false)
            } catch (e: Exception) {
                Log.w(TAG, "Final WAV header patch/sync failed", e)
            } finally {
                runCatching { file.close() }
                raf = null
            }
        }
        val finalSize = if (target.exists()) target.length() else bytesWritten
        return ChunkFinalizeResult(
            file = target,
            actualExtension = fileExtension,
            durationMillis = durationMillis,
            sizeBytes = finalSize
        )
    }

    private fun patchHeader(file: RandomAccessFile, dataSize: Long) {
        val header = buildWavHeader(dataSize, sampleRate, channels)
        file.seek(0L)
        file.write(header)
    }

    companion object {
        private const val TAG = "WavChunkWriter"
        const val HEADER_SIZE = 44L

        fun buildWavHeader(dataSize: Long, sampleRate: Int, channels: Int): ByteArray {
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val safeDataSize = dataSize.coerceAtMost(0xFFFFFF00L)
            val riffChunkSize = safeDataSize + 36L

            val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(riffChunkSize.toInt())
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16) // PCM subchunk1 size
            buffer.putShort(1) // PCM format
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign.toShort())
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(safeDataSize.toInt())
            return buffer.array()
        }

        /**
         * Silent crash recovery: repairs the 44-byte WAV header sizes to match actual file bytes on disk.
         * Returns computed duration in ms, or 0 if the file has no usable PCM payload.
         */
        fun repairWavHeaderIfNeeded(file: File, sampleRate: Int = 16_000, channels: Int = 1): Long {
            if (!file.exists() || file.length() <= HEADER_SIZE) return 0L
            val actualDataBytes = (file.length() - HEADER_SIZE) and 0x7FFFFFFFFFFFFFFEL // even byte count for 16-bit PCM
            if (actualDataBytes <= 0L) return 0L

            RandomAccessFile(file, "rw").use { raf ->
                val header = buildWavHeader(actualDataBytes, sampleRate, channels)
                raf.seek(0L)
                raf.write(header)
                if (raf.length() > HEADER_SIZE + actualDataBytes) {
                    raf.setLength(HEADER_SIZE + actualDataBytes)
                }
                raf.channel.force(false)
            }
            val samples = actualDataBytes / (2L * channels)
            return (samples * 1000L) / sampleRate
        }
    }
}

/**
 * Native Android MediaCodec Opus + MediaMuxer MUXER_OUTPUT_OGG writer (API 29+).
 */
class OggOpusChunkWriter : AudioChunkWriter {
    override val fileExtension: String = "ogg"

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var outputFile: File? = null
    private var trackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private var sampleRate: Int = 16_000
    private var channels: Int = 1
    private var totalSamplesWritten: Long = 0L
    private var encodedBytes: Long = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    override val durationMillis: Long
        get() = if (sampleRate > 0) (totalSamplesWritten * 1000L) / sampleRate else 0L

    override val bytesWritten: Long
        get() = outputFile?.takeIf { it.exists() }?.length() ?: encodedBytes

    override fun open(outputFile: File, sampleRate: Int, channels: Int) {
        this.outputFile = outputFile
        this.sampleRate = sampleRate
        this.channels = channels
        this.totalSamplesWritten = 0L
        this.encodedBytes = 0L
        this.trackIndex = -1
        this.muxerStarted = false

        outputFile.parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val oggMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

        this.codec = encoder
        this.muxer = oggMuxer
    }

    override fun writePcm(samples: ShortArray, offset: Int, length: Int) {
        val encoder = codec ?: return
        if (length <= 0) return

        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            val inputIndex = encoder.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                inputBuffer.clear()
                val maxSamples = inputBuffer.capacity() / 2
                val chunkSamples = minOf(end - cursor, maxSamples)
                for (i in 0 until chunkSamples) {
                    val v = samples[cursor + i].toInt()
                    inputBuffer.put((v and 0xFF).toByte())
                    inputBuffer.put(((v shr 8) and 0xFF).toByte())
                }
                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                encoder.queueInputBuffer(inputIndex, 0, chunkSamples * 2, ptsUs, 0)
                totalSamplesWritten += (chunkSamples / channels).toLong()
                cursor += chunkSamples
            }
            drainEncoder(encoder, endOfStream = false)
        }
    }

    override fun syncToDisk() {
        codec?.let { drainEncoder(it, endOfStream = false) }
    }

    override fun closeAndFinalize(): ChunkFinalizeResult {
        val encoder = codec
        val oggMuxer = muxer
        val target = outputFile ?: File("")
        if (encoder != null) {
            try {
                val inputIndex = encoder.dequeueInputBuffer(20_000L)
                if (inputIndex >= 0) {
                    val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                    encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(encoder, endOfStream = true)
            } catch (e: Exception) {
                Log.w(TAG, "Error draining Opus encoder on close", e)
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                codec = null
            }
        }
        if (oggMuxer != null) {
            if (muxerStarted) {
                runCatching { oggMuxer.stop() }
            }
            runCatching { oggMuxer.release() }
            muxer = null
        }
        if (target.exists() && target.length() > 0L) {
            runCatching {
                RandomAccessFile(target, "rw").use { raf ->
                    raf.channel.force(true)
                }
            }
        }
        return ChunkFinalizeResult(
            file = target,
            actualExtension = fileExtension,
            durationMillis = durationMillis,
            sizeBytes = if (target.exists()) target.length() else encodedBytes
        )
    }

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val oggMuxer = muxer ?: return
        var attempts = 0
        while (attempts < 32) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    attempts++
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = oggMuxer.addTrack(encoder.outputFormat)
                        oggMuxer.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val outBuffer = encoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        oggMuxer.writeSampleData(trackIndex, outBuffer, bufferInfo)
                        encodedBytes += bufferInfo.size
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "OggOpusChunkWriter"
    }
}

/**
 * Crash-safe AAC ADTS chunk writer (.aac with 7-byte ADTS headers on every frame).
 * ExoPlayer plays ADTS AAC directly, and each frame is self-describing so power loss never
 * corrupts earlier speech.
 */
class AacAdtsChunkWriter : AudioChunkWriter {
    override val fileExtension: String = "aac"

    private var codec: MediaCodec? = null
    private var outputStream: FileOutputStream? = null
    private var outputFile: File? = null
    private var sampleRate: Int = 16_000
    private var channels: Int = 1
    private var totalSamplesWritten: Long = 0L
    private var totalBytesWritten: Long = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    override val durationMillis: Long
        get() = if (sampleRate > 0) (totalSamplesWritten * 1000L) / sampleRate else 0L

    override val bytesWritten: Long
        get() = totalBytesWritten

    override fun open(outputFile: File, sampleRate: Int, channels: Int) {
        this.outputFile = outputFile
        this.sampleRate = sampleRate
        this.channels = channels
        this.totalSamplesWritten = 0L
        this.totalBytesWritten = 0L

        outputFile.parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        this.codec = encoder
        this.outputStream = FileOutputStream(outputFile)
    }

    override fun writePcm(samples: ShortArray, offset: Int, length: Int) {
        val encoder = codec ?: return
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            val inputIndex = encoder.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                inputBuffer.clear()
                val maxSamples = inputBuffer.capacity() / 2
                val chunkSamples = minOf(end - cursor, maxSamples)
                for (i in 0 until chunkSamples) {
                    val v = samples[cursor + i].toInt()
                    inputBuffer.put((v and 0xFF).toByte())
                    inputBuffer.put(((v shr 8) and 0xFF).toByte())
                }
                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                encoder.queueInputBuffer(inputIndex, 0, chunkSamples * 2, ptsUs, 0)
                totalSamplesWritten += (chunkSamples / channels).toLong()
                cursor += chunkSamples
            }
            drainEncoder(encoder, endOfStream = false)
        }
    }

    override fun syncToDisk() {
        codec?.let { drainEncoder(it, endOfStream = false) }
        runCatching {
            outputStream?.flush()
            outputStream?.fd?.sync()
        }
    }

    override fun closeAndFinalize(): ChunkFinalizeResult {
        val encoder = codec
        val target = outputFile ?: File("")
        if (encoder != null) {
            try {
                val inputIndex = encoder.dequeueInputBuffer(20_000L)
                if (inputIndex >= 0) {
                    val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                    encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(encoder, endOfStream = true)
            } catch (e: Exception) {
                Log.w(TAG, "Error draining AAC encoder", e)
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                codec = null
            }
        }
        outputStream?.let { fos ->
            runCatching { fos.flush() }
            runCatching { fos.fd.sync() }
            runCatching { fos.close() }
        }
        outputStream = null

        return ChunkFinalizeResult(
            file = target,
            actualExtension = fileExtension,
            durationMillis = durationMillis,
            sizeBytes = if (target.exists()) target.length() else totalBytesWritten
        )
    }

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val fos = outputStream ?: return
        var attempts = 0
        while (attempts < 32) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    attempts++
                }
                outIndex >= 0 -> {
                    val outBuffer = encoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val packetLen = bufferInfo.size + 7
                        val packet = ByteArray(packetLen)
                        writeAdtsHeader(packet, packetLen, sampleRate, channels)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.get(packet, 7, bufferInfo.size)
                        fos.write(packet)
                        totalBytesWritten += packetLen
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    companion object {
        private const val TAG = "AacAdtsChunkWriter"

        private fun sampleRateIndex(sampleRate: Int): Int = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            else -> 8
        }

        private fun writeAdtsHeader(packet: ByteArray, packetLen: Int, sampleRate: Int, channels: Int) {
            val profile = 2 // AAC LC
            val freqIdx = sampleRateIndex(sampleRate)
            packet[0] = 0xFF.toByte()
            packet[1] = 0xF9.toByte()
            packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte()
            packet[3] = (((channels and 3) shl 6) + (packetLen shr 11)).toByte()
            packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
            packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
            packet[6] = 0xFC.toByte()
        }

        /**
         * Streaming crash recovery for AAC ADTS files (O(1) RAM): walks 7-byte ADTS frame headers
         * via [RandomAccessFile] and trims any partial trailing frame left behind by power loss.
         */
        fun repairAdtsTailIfNeeded(file: File): Long {
            if (!file.exists() || file.length() < 7L) return 0L
            return try {
                RandomAccessFile(file, "rw").use { raf ->
                    val fileLen = raf.length()
                    val header = ByteArray(7)
                    var offset = 0L
                    var frameCount = 0L

                    while (offset + 7L <= fileLen) {
                        raf.seek(offset)
                        if (raf.read(header) < 7) break
                        val b0 = header[0].toInt() and 0xFF
                        val b1 = header[1].toInt() and 0xFF
                        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) break

                        val frameLen = ((header[3].toInt() and 0x03) shl 11) or
                            ((header[4].toInt() and 0xFF) shl 3) or
                            ((header[5].toInt() and 0xE0) shr 5)
                        if (frameLen < 7 || offset + frameLen > fileLen) break

                        offset += frameLen
                        frameCount++
                    }

                    if (offset in 1L until fileLen) {
                        raf.setLength(offset)
                        raf.channel.force(false)
                    }
                    // 1024 samples per AAC-LC frame at 16 kHz = 64 ms/frame
                    frameCount * 64L
                }
            } catch (e: Exception) {
                Log.w(TAG, "Streaming ADTS tail repair failed for ${file.name}", e)
                0L
            }
        }
    }
}

/**
 * Native MediaCodec FLAC lossless chunk writer.
 */
class FlacChunkWriter : AudioChunkWriter {
    override val fileExtension: String = "flac"

    private var codec: MediaCodec? = null
    private var outputStream: FileOutputStream? = null
    private var outputFile: File? = null
    private var sampleRate: Int = 16_000
    private var channels: Int = 1
    private var totalSamplesWritten: Long = 0L
    private var totalBytesWritten: Long = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    override val durationMillis: Long
        get() = if (sampleRate > 0) (totalSamplesWritten * 1000L) / sampleRate else 0L

    override val bytesWritten: Long
        get() = totalBytesWritten

    override fun open(outputFile: File, sampleRate: Int, channels: Int) {
        this.outputFile = outputFile
        this.sampleRate = sampleRate
        this.channels = channels
        this.totalSamplesWritten = 0L
        this.totalBytesWritten = 0L

        outputFile.parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        this.codec = encoder
        this.outputStream = FileOutputStream(outputFile)
    }

    override fun writePcm(samples: ShortArray, offset: Int, length: Int) {
        val encoder = codec ?: return
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            val inputIndex = encoder.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                inputBuffer.clear()
                val maxSamples = inputBuffer.capacity() / 2
                val chunkSamples = minOf(end - cursor, maxSamples)
                for (i in 0 until chunkSamples) {
                    val v = samples[cursor + i].toInt()
                    inputBuffer.put((v and 0xFF).toByte())
                    inputBuffer.put(((v shr 8) and 0xFF).toByte())
                }
                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                encoder.queueInputBuffer(inputIndex, 0, chunkSamples * 2, ptsUs, 0)
                totalSamplesWritten += (chunkSamples / channels).toLong()
                cursor += chunkSamples
            }
            drainEncoder(encoder, endOfStream = false)
        }
    }

    override fun syncToDisk() {
        codec?.let { drainEncoder(it, endOfStream = false) }
        runCatching {
            outputStream?.flush()
            outputStream?.fd?.sync()
        }
    }

    override fun closeAndFinalize(): ChunkFinalizeResult {
        val encoder = codec
        val target = outputFile ?: File("")
        if (encoder != null) {
            try {
                val inputIndex = encoder.dequeueInputBuffer(20_000L)
                if (inputIndex >= 0) {
                    val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate
                    encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(encoder, endOfStream = true)
            } catch (e: Exception) {
                Log.w(TAG, "Error draining FLAC encoder", e)
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                codec = null
            }
        }
        outputStream?.let { fos ->
            runCatching { fos.flush() }
            runCatching { fos.fd.sync() }
            runCatching { fos.close() }
        }
        outputStream = null

        return ChunkFinalizeResult(
            file = target,
            actualExtension = fileExtension,
            durationMillis = durationMillis,
            sizeBytes = if (target.exists()) target.length() else totalBytesWritten
        )
    }

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val fos = outputStream ?: return
        var attempts = 0
        while (attempts < 32) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    attempts++
                }
                outIndex >= 0 -> {
                    val outBuffer = encoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.get(data)
                        fos.write(data)
                        totalBytesWritten += data.size
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    companion object {
        private const val TAG = "FlacChunkWriter"
    }
}
