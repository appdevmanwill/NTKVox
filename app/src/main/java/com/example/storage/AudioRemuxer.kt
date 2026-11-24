package com.example.storage

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure-Kotlin streaming PCM downmixer and sample-rate converter (O(1) RAM, zero NDK).
 *
 * Ensures decoded audio from decoders that output 48 kHz (such as Android's `c2.android.opus.decoder`)
 * or stereo streams is smoothly converted into exact 16 kHz mono PCM without altering speed or pitch.
 */
class PcmResampler(
    inputSampleRate: Int = 16_000,
    inputChannels: Int = 1,
    val targetSampleRate: Int = 16_000
) {
    private var inRate: Int = inputSampleRate.coerceAtLeast(1)
    private var inChannels: Int = inputChannels.coerceAtLeast(1)

    private var decimFactor: Int = computeDecimFactor(inRate, targetSampleRate)
    private var decimSum: Long = 0L
    private var decimCount: Int = 0

    private var step: Double = inRate.toDouble() / targetSampleRate.toDouble()
    private var hasPrevSample: Boolean = false
    private var prevSample: Float = 0f
    private var posToNext: Double = step

    private var outScratch = ShortArray(4096)

    fun updateInputFormat(sampleRate: Int, channels: Int) {
        val safeRate = sampleRate.coerceAtLeast(1)
        val safeChannels = channels.coerceAtLeast(1)
        if (safeRate == inRate && safeChannels == inChannels) return

        inRate = safeRate
        inChannels = safeChannels
        decimFactor = computeDecimFactor(inRate, targetSampleRate)
        decimSum = 0L
        decimCount = 0
        step = inRate.toDouble() / targetSampleRate.toDouble()
        hasPrevSample = false
        posToNext = step
    }

    fun process(
        interleavedSamples: ShortArray,
        sampleCount: Int,
        onMonoPcm: (samples: ShortArray, length: Int) -> Unit
    ) {
        val frames = sampleCount / inChannels
        if (frames <= 0) return

        // Fast pass-through for native 16 kHz mono PCM
        if (inRate == targetSampleRate && inChannels == 1) {
            onMonoPcm(interleavedSamples, frames)
            return
        }

        // Exact integer decimation (e.g. 48,000 Hz -> 16,000 Hz Opus 3:1 with boxcar anti-aliasing)
        if (decimFactor >= 2) {
            val factor = decimFactor
            val neededOut = (frames / factor) + 2
            if (outScratch.size < neededOut) outScratch = ShortArray(neededOut)
            var outLen = 0
            for (f in 0 until frames) {
                val mono = readMonoFrame(interleavedSamples, f, inChannels)
                decimSum += mono
                decimCount++
                if (decimCount >= factor) {
                    val avg = (decimSum / factor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outScratch[outLen++] = avg.toShort()
                    decimSum = 0L
                    decimCount = 0
                }
            }
            if (outLen > 0) {
                onMonoPcm(outScratch, outLen)
            }
            return
        }

        // Same rate multi-channel downmix (e.g. 16 kHz stereo -> mono)
        if (inRate == targetSampleRate) {
            if (outScratch.size < frames) outScratch = ShortArray(frames)
            for (f in 0 until frames) {
                outScratch[f] = readMonoFrame(interleavedSamples, f, inChannels)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            onMonoPcm(outScratch, frames)
            return
        }

        // General streaming fractional linear interpolation (e.g. 44.1 kHz -> 16 kHz, 8 kHz -> 16 kHz)
        val estimatedOut = ((frames.toDouble() * targetSampleRate.toDouble()) / inRate.toDouble()).toInt() + 8
        if (outScratch.size < estimatedOut) outScratch = ShortArray(estimatedOut)
        var outLen = 0

        for (f in 0 until frames) {
            val curr = readMonoFrame(interleavedSamples, f, inChannels).toFloat()
            if (!hasPrevSample) {
                if (outLen >= outScratch.size) outScratch = outScratch.copyOf(outScratch.size * 2)
                outScratch[outLen++] = curr.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                prevSample = curr
                hasPrevSample = true
                posToNext = step
            } else {
                posToNext -= 1.0
                while (posToNext <= 1e-9) {
                    val frac = (1.0 + posToNext).coerceIn(0.0, 1.0).toFloat()
                    val interp = prevSample + (curr - prevSample) * frac
                    if (outLen >= outScratch.size) outScratch = outScratch.copyOf(outScratch.size * 2)
                    outScratch[outLen++] = interp.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    posToNext += step
                }
                prevSample = curr
            }
        }
        if (outLen > 0) {
            onMonoPcm(outScratch, outLen)
        }
    }

    private fun readMonoFrame(samples: ShortArray, frameIndex: Int, channels: Int): Long {
        if (channels == 1) return samples[frameIndex].toLong()
        val base = frameIndex * channels
        var sum = 0L
        for (c in 0 until channels) {
            sum += samples[base + c]
        }
        return sum / channels
    }

    private fun computeDecimFactor(inputRate: Int, outputRate: Int): Int =
        if (inputRate > outputRate && outputRate > 0 && inputRate % outputRate == 0) {
            inputRate / outputRate
        } else {
            1
        }
}

/**
 * Pure-Kotlin native Android container remuxing, multi-chunk merging, and stream crash-recovery
 * utilities (zero NDK/C++, zero external dependencies).
 *
 * Capabilities:
 * - Lossless streaming ADTS (.aac) -> MP4/M4A (.m4a) remuxing via [MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4]
 *   supporting both single chunks and multi-chunk session exports in O(1) RAM.
 * - Multi-chunk Ogg Opus and FLAC seamless merging via decode + automatic resampling + re-encode.
 * - Streaming Ogg page-boundary crash recovery (O(1) RAM, trims partial trailing pages on power loss).
 * - FLAC header validation for crash recovery.
 */
object AudioRemuxer {

    private const val TAG = "AudioRemuxer"
    private val ADTS_SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    /**
     * Losslessly remuxes one or more crash-safe ADTS `.aac` files into a single universal `.m4a`
     * container using streaming I/O (O(1) RAM, never loads entire files into memory).
     *
     * @return true if remuxing succeeded and [destinationM4a] is non-empty.
     */
    fun remuxAdtsToM4a(adtsSources: List<File>, destinationM4a: File): Boolean {
        if (adtsSources.isEmpty()) return false
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            destinationM4a.parentFile?.mkdirs()
            if (destinationM4a.exists()) destinationM4a.delete()

            var sampleRate = 16_000
            var channels = 1
            var trackIndex = -1
            var totalFramesWritten = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            val header = ByteArray(7)
            val crcScratch = ByteArray(2)
            var framePayloadBuf = ByteArray(8192)

            for (source in adtsSources) {
                if (!source.exists() || source.length() < 7L) continue
                BufferedInputStream(FileInputStream(source), 32_768).use { input ->
                    while (true) {
                        val headerRead = readFullyOrEof(input, header, 0, 7)
                        if (headerRead < 7) break

                        val b0 = header[0].toInt() and 0xFF
                        val b1 = header[1].toInt() and 0xFF
                        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) break

                        val protectionAbsent = b1 and 0x01
                        val headerSize = if (protectionAbsent == 1) 7 else 9
                        if (headerSize == 9 && readFullyOrEof(input, crcScratch, 0, 2) < 2) break

                        val b2 = header[2].toInt() and 0xFF
                        val b3 = header[3].toInt() and 0xFF
                        val b4 = header[4].toInt() and 0xFF
                        val b5 = header[5].toInt() and 0xFF

                        val profile = ((b2 and 0xC0) shr 6) + 1 // 2 = AAC-LC
                        val freqIndex = (b2 and 0x3C) shr 2
                        val channelConfig = ((b2 and 0x01) shl 2) or ((b3 and 0xC0) shr 6)
                        val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) shr 5)

                        val payloadSize = frameLength - headerSize
                        if (payloadSize <= 0 || payloadSize > 65_536) break
                        if (framePayloadBuf.size < payloadSize) {
                            framePayloadBuf = ByteArray(payloadSize)
                        }
                        if (readFullyOrEof(input, framePayloadBuf, 0, payloadSize) < payloadSize) break

                        val activeMuxer = muxer ?: run {
                            sampleRate = ADTS_SAMPLE_RATES.getOrElse(freqIndex) { 16_000 }
                            channels = channelConfig.coerceIn(1, 2)
                            val csd0 = buildAacAudioSpecificConfig(profile, freqIndex, channels)
                            val format = MediaFormat.createAudioFormat(
                                MediaFormat.MIMETYPE_AUDIO_AAC,
                                sampleRate,
                                channels
                            ).apply {
                                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                            }
                            MediaMuxer(
                                destinationM4a.absolutePath,
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                            ).also { created ->
                                trackIndex = created.addTrack(format)
                                created.start()
                                muxer = created
                                muxerStarted = true
                            }
                        }

                        val frameBuffer = ByteBuffer.wrap(framePayloadBuf, 0, payloadSize)
                        val ptsUs = (totalFramesWritten * 1024L * 1_000_000L) / sampleRate
                        bufferInfo.set(0, payloadSize, ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        activeMuxer.writeSampleData(trackIndex, frameBuffer, bufferInfo)
                        totalFramesWritten++
                    }
                }
            }

            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null

            totalFramesWritten > 0L && destinationM4a.exists() && destinationM4a.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "Lossless ADTS -> M4A remux failed", e)
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { destinationM4a.delete() }
            false
        }
    }

    /**
     * Merges multiple encoded chunks (OGG Opus or FLAC) into a single clean continuous container
     * by decoding each chunk into 16 kHz mono PCM frames and streaming them into [targetWriter].
     */
    fun mergeEncodedChunksViaPcm(
        sources: List<File>,
        destination: File,
        targetWriter: AudioChunkWriter,
        sampleRate: Int = 16_000,
        channels: Int = 1
    ): Boolean {
        if (sources.isEmpty()) return false
        return try {
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.delete()
            targetWriter.open(destination, sampleRate, channels)

            var wroteAny = false
            for (src in sources) {
                if (!src.exists() || src.length() == 0L) continue
                val ok = decodeFileIntoConsumer(src, targetSampleRate = sampleRate) { samples, length ->
                    if (length > 0) {
                        targetWriter.writePcm(samples, 0, length)
                        wroteAny = true
                    }
                }
                if (!ok) {
                    Log.w(TAG, "Skipping unreadable chunk during merge: ${src.name}")
                }
            }

            val result = targetWriter.closeAndFinalize()
            wroteAny && result.file.exists() && result.sizeBytes > 0L
        } catch (e: Exception) {
            Log.w(TAG, "Multi-chunk merge failed for ${destination.name}", e)
            runCatching { targetWriter.closeAndFinalize() }
            runCatching { destination.delete() }
            false
        }
    }

    /**
     * Streaming crash recovery for Ogg Opus files (O(1) RAM): walks `"OggS"` page headers
     * via [RandomAccessFile] and truncates any partial/corrupted trailing page left behind by power loss.
     *
     * @return estimated duration in ms (0 if no valid pages remain).
     */
    fun repairOggStreamIfNeeded(file: File, sampleRate: Int = 48_000): Long {
        if (!file.exists() || file.length() < 27L) return 0L
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val fileLen = raf.length()
                val header = ByteArray(27)
                val lacingTable = ByteArray(255)
                val granuleBuf = ByteBuffer.wrap(header, 6, 8).order(ByteOrder.LITTLE_ENDIAN)

                var offset = 0L
                var lastValidEnd = 0L
                var maxGranulePos = 0L

                while (offset + 27L <= fileLen) {
                    raf.seek(offset)
                    if (raf.read(header) < 27) break

                    if (header[0] != 'O'.code.toByte() ||
                        header[1] != 'g'.code.toByte() ||
                        header[2] != 'g'.code.toByte() ||
                        header[3] != 'S'.code.toByte()
                    ) {
                        break
                    }

                    granuleBuf.position(6)
                    val granule = granuleBuf.long
                    val segmentCount = header[26].toInt() and 0xFF
                    val headerEnd = offset + 27L + segmentCount
                    if (headerEnd > fileLen) break

                    if (segmentCount > 0) {
                        var readSegments = 0
                        while (readSegments < segmentCount) {
                            val r = raf.read(lacingTable, readSegments, segmentCount - readSegments)
                            if (r <= 0) break
                            readSegments += r
                        }
                        if (readSegments < segmentCount) break
                    }

                    var bodyBytes = 0L
                    for (i in 0 until segmentCount) {
                        bodyBytes += lacingTable[i].toInt() and 0xFF
                    }
                    val pageEnd = headerEnd + bodyBytes
                    if (pageEnd > fileLen) break

                    if (granule > maxGranulePos) maxGranulePos = granule
                    lastValidEnd = pageEnd
                    offset = pageEnd
                }

                if (lastValidEnd in 1L until fileLen) {
                    raf.setLength(lastValidEnd)
                    raf.channel.force(false)
                }

                if (lastValidEnd == 0L) {
                    0L
                } else if (maxGranulePos > 0L) {
                    // Opus Ogg granule positions are always measured at 48 kHz per RFC 7845
                    (maxGranulePos * 1000L) / sampleRate
                } else {
                    1000L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ogg page repair failed for ${file.name}", e)
            0L
        }
    }

    /**
     * Crash validation for FLAC files: verifies `"fLaC"` stream marker and non-empty payload.
     */
    fun isValidFlacHeader(file: File): Boolean {
        if (!file.exists() || file.length() <= 42L) return false
        return try {
            val magic = ByteArray(4)
            FileInputStream(file).use { input ->
                if (input.read(magic) < 4) return false
            }
            magic[0] == 'f'.code.toByte() &&
                magic[1] == 'L'.code.toByte() &&
                magic[2] == 'a'.code.toByte() &&
                magic[3] == 'C'.code.toByte()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Decodes any supported audio file into mono 16-bit PCM normalized to [targetSampleRate] (16 kHz default),
     * adapting dynamically to `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED` (including Android's 48 kHz Opus decoder).
     */
    fun decodeFileIntoConsumer(
        file: File,
        targetSampleRate: Int = 16_000,
        onPcm: (samples: ShortArray, length: Int) -> Unit
    ): Boolean {
        if (file.extension.equals("wav", ignoreCase = true)) {
            return decodeWavIntoConsumer(file, targetSampleRate, onPcm)
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = tf
                    break
                }
            }
            if (audioTrack < 0 || format == null) return false
            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val fallbackRate = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                48_000
            } else {
                format.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 16_000)
            }
            val initialChannels = format.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            val resampler = PcmResampler(
                inputSampleRate = fallbackRate,
                inputChannels = initialChannels,
                targetSampleRate = targetSampleRate
            )
            var outputFormatApplied = false

            // Query initial codec outputFormat if already resolved synchronously
            runCatching { codec.outputFormat }.getOrNull()?.let { outFmt ->
                val outRate = outFmt.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, fallbackRate)
                val outCh = outFmt.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, initialChannels)
                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    resampler.updateInputFormat(outRate, outCh)
                    outputFormatApplied = true
                }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmScratch = ShortArray(4096)

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val outRate = outFmt.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, fallbackRate)
                        val outCh = outFmt.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, initialChannels)
                        resampler.updateInputFormat(outRate, outCh)
                        outputFormatApplied = true
                    }
                    outIdx >= 0 -> {
                        if (!outputFormatApplied) {
                            runCatching { codec.outputFormat }.getOrNull()?.let { outFmt ->
                                val outRate = outFmt.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, fallbackRate)
                                val outCh = outFmt.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, initialChannels)
                                resampler.updateInputFormat(outRate, outCh)
                                outputFormatApplied = true
                            }
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val count = sb.remaining()
                            if (pcmScratch.size < count) pcmScratch = ShortArray(count)
                            sb.get(pcmScratch, 0, count)
                            resampler.process(pcmScratch, count, onPcm)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Decode failed for ${file.name}", e)
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeWavIntoConsumer(
        file: File,
        targetSampleRate: Int,
        onPcm: (samples: ShortArray, length: Int) -> Unit
    ): Boolean {
        if (!file.exists() || file.length() <= WavChunkWriter.HEADER_SIZE) return false
        return try {
            FileInputStream(file).use { input ->
                val headerSize = WavChunkWriter.HEADER_SIZE.toInt()
                val header = ByteArray(headerSize)
                if (readFullyOrEof(input, header, 0, headerSize) < headerSize) {
                    return false
                }
                val hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val channels = hdrBuf.getShort(22).toInt().coerceIn(1, 8)
                val sampleRate = hdrBuf.getInt(24).coerceIn(8_000, 192_000)
                val resampler = PcmResampler(
                    inputSampleRate = sampleRate,
                    inputChannels = channels,
                    targetSampleRate = targetSampleRate
                )

                val byteBuf = ByteArray(8192)
                val shortBuf = ShortArray(4096)
                while (true) {
                    val read = input.read(byteBuf)
                    if (read <= 0) break
                    val samples = read / 2
                    ByteBuffer.wrap(byteBuf, 0, samples * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuf, 0, samples)
                    resampler.process(shortBuf, samples, onPcm)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "WAV decode failed for ${file.name}", e)
            false
        }
    }

    private fun readFullyOrEof(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val r = input.read(buffer, offset + total, length - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun buildAacAudioSpecificConfig(
        audioObjectType: Int,
        samplingFreqIndex: Int,
        channelConfig: Int
    ): ByteArray {
        val byte0 = ((audioObjectType shl 3) and 0xF8) or ((samplingFreqIndex shr 1) and 0x07)
        val byte1 = ((samplingFreqIndex shl 7) and 0x80) or ((channelConfig shl 3) and 0x78)
        return byteArrayOf(byte0.toByte(), byte1.toByte())
    }
}
