package com.example.audio

import com.example.model.VoiceCleanConfig
import com.example.model.VoiceSensitivity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whole-recording acoustic profile computed during Pass 1 of post-recording voice cleanup.
 *
 * - [noiseFloorDb]: 15th-percentile voice-band dB level representing the room's steady noise floor.
 * - [prominentPeakDb]: 92nd-percentile speech-frame dB level representing the close/main speaker.
 * - [noiseSpectrum]: Average 257-bin FFT magnitude spectrum of the quietest frames (used to gently
 *   attenuate steady fan/AC/hum/hiss in Pass 2).
 */
data class AudioAcousticProfile(
    val noiseFloorDb: Float,
    val prominentPeakDb: Float,
    val noiseSpectrum: FloatArray,
    val totalFrames: Int,
    val speechFrames: Int
)

/**
 * Pass 1 streaming analyzer (O(1) RAM): scans decoded 16 kHz mono PCM to measure the room's
 * ambient noise floor, the prominent foreground speaker's loudness, and the room's noise spectrum.
 */
class AudioAcousticProfiler(
    private val sampleRate: Int = 16_000
) {
    private val voiceFilter = VoiceBandFilter(sampleRate)
    private val fft = Radix2Fft(FRAME_SAMPLES)
    private val frameAccum = ShortArray(FRAME_SAMPLES)
    private var frameAccumCount = 0

    private val rawFloat = FloatArray(FRAME_SAMPLES)
    private val voiceFloat = FloatArray(FRAME_SAMPLES)
    private val fftReal = FloatArray(FRAME_SAMPLES)
    private val fftImag = FloatArray(FRAME_SAMPLES)
    private val fftMags = FloatArray(fft.halfBins)

    // 1 dB resolution histograms spanning -90 dB..0 dB (91 buckets)
    private val allFrameHistogram = IntArray(HIST_BUCKETS)
    private val tonalFrameHistogram = IntArray(HIST_BUCKETS)

    // Accumulate spectral noise signatures stratified by loudness bucket so we can pick the
    // quietest ~15-20% frames in a single streaming pass without storing the entire file!
    private val noiseAccumLow = DoubleArray(fft.halfBins)   // frames <= -50 dB
    private var noiseCountLow = 0
    private val noiseAccumMid = DoubleArray(fft.halfBins)   // frames -50..-35 dB
    private var noiseCountMid = 0
    private val noiseAccumAny = DoubleArray(fft.halfBins)   // fallback all non-tonal frames
    private var noiseCountAny = 0

    private var totalFrames = 0
    private var tonalFrames = 0

    fun processPcm(samples: ShortArray, length: Int = samples.size) {
        var offset = 0
        while (offset < length) {
            val needed = FRAME_SAMPLES - frameAccumCount
            val toCopy = min(needed, length - offset)
            System.arraycopy(samples, offset, frameAccum, frameAccumCount, toCopy)
            frameAccumCount += toCopy
            offset += toCopy

            if (frameAccumCount == FRAME_SAMPLES) {
                analyzeFrame(frameAccum, FRAME_SAMPLES)
                frameAccumCount = 0
            }
        }
    }

    fun finish(): AudioAcousticProfile {
        if (frameAccumCount >= 64) {
            analyzeFrame(frameAccum, frameAccumCount)
        }
        frameAccumCount = 0

        val noiseFloorDb = percentileFromHistogram(allFrameHistogram, totalFrames, 0.15f)
            .coerceIn(-78f, -24f)

        val prominentPeakDb = if (tonalFrames >= 5) {
            percentileFromHistogram(tonalFrameHistogram, tonalFrames, 0.92f)
        } else {
            percentileFromHistogram(allFrameHistogram, totalFrames, 0.92f)
        }.coerceIn(noiseFloorDb + 4f, -3f)

        val chosenSpectrum = FloatArray(fft.halfBins)
        val (srcAccum, srcCount) = when {
            noiseCountLow >= 8 -> noiseAccumLow to noiseCountLow
            noiseCountMid >= 8 -> noiseAccumMid to noiseCountMid
            noiseCountAny > 0 -> noiseAccumAny to noiseCountAny
            else -> null to 0
        }
        if (srcAccum != null && srcCount > 0) {
            val inv = 1.0 / srcCount.toDouble()
            for (k in 0 until fft.halfBins) {
                chosenSpectrum[k] = (srcAccum[k] * inv).toFloat()
            }
        }

        return AudioAcousticProfile(
            noiseFloorDb = noiseFloorDb,
            prominentPeakDb = prominentPeakDb,
            noiseSpectrum = chosenSpectrum,
            totalFrames = totalFrames,
            speechFrames = tonalFrames
        )
    }

    private fun analyzeFrame(frame: ShortArray, count: Int) {
        var fullSumSq = 0.0
        var voiceSumSq = 0.0
        for (i in 0 until count) {
            val raw = frame[i] / 32768f
            rawFloat[i] = raw
            fullSumSq += raw * raw
            val v = voiceFilter.processSample(raw)
            voiceFloat[i] = v
            voiceSumSq += v * v
        }
        val voiceRms = sqrt(voiceSumSq / count).toFloat()
        val voiceDb = linearToDb(voiceRms)
        val voiceRatio = if (fullSumSq > 1e-10) (voiceSumSq / fullSumSq).toFloat() else 0f

        val bucket = dbToBucket(voiceDb)
        allFrameHistogram[bucket]++
        totalFrames++

        // Full-band magnitude spectrum for noise profiling & flatness check
        fft.computeMagnitudeSpectrum(rawFloat, count, fftReal, fftImag, fftMags)
        val flatness = fft.computeVoiceBandFlatness(fftMags, sampleRate)

        val looksTonalSpeech = voiceDb > -58f && voiceRatio >= 0.30f && flatness <= 0.62f
        if (looksTonalSpeech) {
            tonalFrameHistogram[bucket]++
            tonalFrames++
        }

        // Collect steady noise profile from quiet or flat-spectrum non-speech frames
        if (!looksTonalSpeech || voiceDb <= -50f) {
            when {
                voiceDb <= -50f -> {
                    for (k in 0 until fft.halfBins) noiseAccumLow[k] += fftMags[k].toDouble()
                    noiseCountLow++
                }
                voiceDb <= -35f -> {
                    for (k in 0 until fft.halfBins) noiseAccumMid[k] += fftMags[k].toDouble()
                    noiseCountMid++
                }
            }
            if (!looksTonalSpeech) {
                for (k in 0 until fft.halfBins) noiseAccumAny[k] += fftMags[k].toDouble()
                noiseCountAny++
            }
        }
    }

    private fun dbToBucket(db: Float): Int = (db.toInt() + 90).coerceIn(0, HIST_BUCKETS - 1)

    private fun percentileFromHistogram(hist: IntArray, total: Int, p: Float): Float {
        if (total <= 0) return -62f
        val target = max(1, (total * p).toInt())
        var running = 0
        for (i in 0 until HIST_BUCKETS) {
            running += hist[i]
            if (running >= target) {
                return (i - 90).toFloat()
            }
        }
        return -20f
    }

    private fun linearToDb(amplitude: Float): Float {
        if (amplitude <= 1e-6f) return -90f
        return (20.0 * ln(amplitude.toDouble()) / LN10).toFloat().coerceIn(-90f, 0f)
    }

    companion object {
        private val LN10 = ln(10.0)
        private const val FRAME_SAMPLES = 512
        private const val HIST_BUCKETS = 91 // -90 dB .. 0 dB
    }
}

/**
 * Pass 2 streaming Prominent-Voice & Background-Noise Cleaner (100% offline, zero AI, O(1) RAM).
 *
 * Combines:
 * 1. **Prominent-Voice Dynamic Cutoff** derived from Pass 1's [AudioAcousticProfile] + [VoiceCleanConfig]:
 *    cuts both steady background noise AND quieter distant background voices whenever they fall
 *    below `prominentPeakDb + prominenceOffsetDb`.
 * 2. **Spectral Speech Verification** (120 Hz–3.4 kHz voice-band ratio + FFT spectral flatness +
 *    zero-crossing rate) so non-speech bangs, rustling, wind, or fans are never kept.
 * 3. **Optional Overlap-Add Steady Noise Reduction** ([SpectralNoiseReducer]) that gently reduces
 *    room hum/AC/fan hiss underneath kept foreground speech.
 * 4. **160 ms Lead-In + Configurable Natural Pause + 12 ms Cosine Crossfades** so every speech
 *    cut sounds smooth and click-free.
 */
class ProminentVoiceCleaner(
    private val profile: AudioAcousticProfile,
    private val config: VoiceCleanConfig,
    private val sampleRate: Int = 16_000,
    private val onEmitPcm: (samples: ShortArray, offset: Int, length: Int) -> Unit
) {
    private val voiceFilter = VoiceBandFilter(sampleRate)
    private val fft = Radix2Fft(FRAME_SAMPLES)
    private val leadInBuffer = PreRollRingBuffer(sampleRate = sampleRate, durationSeconds = LEAD_IN_SECONDS)
    private val noiseReducer: SpectralNoiseReducer? = if (config.reduceSteadyNoise) {
        SpectralNoiseReducer(
            sampleRate = sampleRate,
            noiseSpectrum = profile.noiseSpectrum
        )
    } else {
        null
    }

    private val frameAccum = ShortArray(FRAME_SAMPLES)
    private var frameAccumCount = 0

    private val voiceScratch = FloatArray(FRAME_SAMPLES)
    private val fftReal = FloatArray(FRAME_SAMPLES)
    private val fftImag = FloatArray(FRAME_SAMPLES)
    private val fftMags = FloatArray(fft.halfBins)
    private val fadeWorkBuf = ShortArray(FRAME_SAMPLES)

    private val fadeSamples = ((sampleRate * FADE_MILLIS) / 1000L).toInt().coerceIn(32, FRAME_SAMPLES)
    private var pendingFadeInRemaining = 0

    // Compute dynamic prominent-voice cutoff in dBFS:
    // Must exceed BOTH the user's prominence cutoff relative to the main speaker peak AND
    // a safe SNR margin above the room's ambient noise floor.
    val effectiveCutoffDb: Float = run {
        val preset = config.focusPreset
        val relativeCutoff = profile.prominentPeakDb + config.prominenceOffsetDb.coerceIn(-24f, -4f)
        val floorCutoff = profile.noiseFloorDb + preset.snrMarginDb
        maxOf(relativeCutoff, floorCutoff, preset.minAbsoluteSpeechDb)
            .coerceAtMost(profile.prominentPeakDb - 2.5f)
    }

    // Slight hysteresis (3 dB lower to stay inside speech) prevents choppy mid-word cuts
    private val holdCutoffDb: Float = effectiveCutoffDb - 3.0f

    private var inSpeech = false
    private var silenceRunMs = 0L

    var totalInputSamples: Long = 0L
        private set

    var totalKeptSamples: Long = 0L
        private set

    val inputDurationMillis: Long
        get() = (totalInputSamples * 1000L) / sampleRate

    val keptDurationMillis: Long
        get() = (totalKeptSamples * 1000L) / sampleRate

    fun processPcm(samples: ShortArray, length: Int = samples.size) {
        var offset = 0
        while (offset < length) {
            val needed = FRAME_SAMPLES - frameAccumCount
            val toCopy = min(needed, length - offset)
            System.arraycopy(samples, offset, frameAccum, frameAccumCount, toCopy)
            frameAccumCount += toCopy
            offset += toCopy

            if (frameAccumCount == FRAME_SAMPLES) {
                evaluateFrame(frameAccum, FRAME_SAMPLES)
                frameAccumCount = 0
            }
        }
    }

    fun finish() {
        if (frameAccumCount > 0) {
            evaluateFrame(frameAccum, frameAccumCount)
            frameAccumCount = 0
        }
        noiseReducer?.flush { out, off, len ->
            onEmitPcm(out, off, len)
            totalKeptSamples += len
        }
        leadInBuffer.clear()
    }

    private fun evaluateFrame(frame: ShortArray, count: Int) {
        totalInputSamples += count
        val frameMs = (count * 1000L) / sampleRate
        val isProminentVoice = classifyFrameAsProminentVoice(frame, count)

        if (isProminentVoice) {
            if (!inSpeech) {
                inSpeech = true
                pendingFadeInRemaining = fadeSamples
                leadInBuffer.drainInto { buf, off, len ->
                    emitWithFadeIn(buf, off, len)
                }
            }
            silenceRunMs = 0L
            emitWithFadeIn(frame, 0, count)
        } else {
            if (inSpeech) {
                if (silenceRunMs < config.keepPauseMillis) {
                    val nextSilenceMs = silenceRunMs + frameMs
                    if (nextSilenceMs >= config.keepPauseMillis) {
                        // Last frame before cut — apply smooth 12 ms cosine fade-out (zero pop!)
                        emitWithFadeOut(frame, count)
                    } else {
                        emitWithFadeIn(frame, 0, count)
                    }
                    silenceRunMs = nextSilenceMs
                } else {
                    inSpeech = false
                    silenceRunMs = 0L
                    noiseReducer?.flush { out, off, len ->
                        onEmitPcm(out, off, len)
                        totalKeptSamples += len
                    }
                    leadInBuffer.clear()
                    leadInBuffer.write(frame, 0, count)
                }
            } else {
                leadInBuffer.write(frame, 0, count)
            }
        }
    }

    private fun classifyFrameAsProminentVoice(frame: ShortArray, count: Int): Boolean {
        var fullSumSq = 0.0
        var voiceSumSq = 0.0
        var zeroCrossings = 0
        var prevV = 0f

        for (i in 0 until count) {
            val raw = frame[i] / 32768f
            fullSumSq += raw * raw
            val v = voiceFilter.processSample(raw)
            voiceScratch[i] = v
            voiceSumSq += v * v
            if (i > 0 && ((v >= 0f && prevV < 0f) || (v < 0f && prevV >= 0f))) {
                zeroCrossings++
            }
            prevV = v
        }

        val voiceRms = sqrt(voiceSumSq / count).toFloat()
        val voiceDb = if (voiceRms <= 1e-6f) -90f else (20.0 * ln(voiceRms.toDouble()) / LN10).toFloat()
        val activeThreshold = if (inSpeech) holdCutoffDb else effectiveCutoffDb
        if (voiceDb < activeThreshold) return false

        val voiceRatio = if (fullSumSq > 1e-10) (voiceSumSq / fullSumSq).toFloat() else 0f
        if (voiceRatio < config.focusPreset.minVoiceBandRatio) return false

        val zcr = zeroCrossings.toFloat() / count.toFloat()
        if (zcr < 0.008f || zcr > 0.44f) return false

        fft.computeMagnitudeSpectrum(voiceScratch, count, fftReal, fftImag, fftMags)
        val flatness = fft.computeVoiceBandFlatness(fftMags, sampleRate)
        return flatness <= config.focusPreset.maxSpectralFlatness
    }

    private fun emitWithFadeIn(samples: ShortArray, offset: Int, length: Int) {
        if (pendingFadeInRemaining <= 0) {
            emitThroughOptionalDenoiser(samples, offset, length)
            return
        }
        var cursor = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = min(remaining, FRAME_SAMPLES)
            for (i in 0 until chunk) {
                val s = samples[cursor + i]
                fadeWorkBuf[i] = if (pendingFadeInRemaining > 0) {
                    val progress = 1f - (pendingFadeInRemaining.toFloat() / fadeSamples.toFloat())
                    val gain = (0.5 * (1.0 - cos(PI * progress))).toFloat()
                    pendingFadeInRemaining--
                    (s * gain).toInt().toShort()
                } else {
                    s
                }
            }
            emitThroughOptionalDenoiser(fadeWorkBuf, 0, chunk)
            cursor += chunk
            remaining -= chunk
        }
    }

    private fun emitWithFadeOut(frame: ShortArray, count: Int) {
        val fadeLen = min(fadeSamples, count)
        val fadeStart = count - fadeLen
        for (i in 0 until count) {
            fadeWorkBuf[i] = if (i >= fadeStart) {
                val progress = (i - fadeStart).toFloat() / fadeLen.toFloat()
                val gain = (0.5 * (1.0 + cos(PI * progress))).toFloat()
                (frame[i] * gain).toInt().toShort()
            } else {
                frame[i]
            }
        }
        emitThroughOptionalDenoiser(fadeWorkBuf, 0, count)
    }

    private fun emitThroughOptionalDenoiser(samples: ShortArray, offset: Int, length: Int) {
        val reducer = noiseReducer
        if (reducer != null) {
            reducer.processPcm(samples, offset, length) { out, off, len ->
                onEmitPcm(out, off, len)
                totalKeptSamples += len
            }
        } else {
            onEmitPcm(samples, offset, length)
            totalKeptSamples += length
        }
    }

    companion object {
        private val LN10 = ln(10.0)
        private const val FRAME_SAMPLES = 512 // 32 ms @ 16 kHz
        private const val LEAD_IN_SECONDS = 0.16f // 160 ms lead-in before voice onset
        private const val FADE_MILLIS = 12L // 12 ms raised-cosine crossfade
    }
}

/**
 * Single-pass streaming silence remover kept for lightweight callers and unit tests.
 */
class SilenceRemover(
    private val sampleRate: Int = 16_000,
    sensitivity: VoiceSensitivity = VoiceSensitivity.QUIET_VOICES,
    private val keepPauseMillis: Long = 1_000L,
    private val onEmitPcm: (samples: ShortArray, offset: Int, length: Int) -> Unit
) {
    private val vad = EnergyVadDetector(initialSensitivity = sensitivity, sampleRate = sampleRate)
    private val leadInBuffer = PreRollRingBuffer(sampleRate = sampleRate, durationSeconds = LEAD_IN_SECONDS)
    private val frameAccum = ShortArray(FRAME_SAMPLES)
    private var frameAccumCount = 0

    private var inSpeech = false
    private var silenceRunMs = 0L

    var totalInputSamples: Long = 0L
        private set

    var totalKeptSamples: Long = 0L
        private set

    val inputDurationMillis: Long
        get() = (totalInputSamples * 1000L) / sampleRate

    val keptDurationMillis: Long
        get() = (totalKeptSamples * 1000L) / sampleRate

    fun processPcm(samples: ShortArray, length: Int = samples.size) {
        var offset = 0
        while (offset < length) {
            val needed = FRAME_SAMPLES - frameAccumCount
            val toCopy = minOf(needed, length - offset)
            System.arraycopy(samples, offset, frameAccum, frameAccumCount, toCopy)
            frameAccumCount += toCopy
            offset += toCopy

            if (frameAccumCount == FRAME_SAMPLES) {
                evaluateFrame(frameAccum, FRAME_SAMPLES)
                frameAccumCount = 0
            }
        }
    }

    fun finish() {
        if (frameAccumCount > 0) {
            evaluateFrame(frameAccum, frameAccumCount)
            frameAccumCount = 0
        }
        leadInBuffer.clear()
    }

    private fun evaluateFrame(frame: ShortArray, count: Int) {
        totalInputSamples += count
        val frameMs = (count * 1000L) / sampleRate
        val result = vad.processFrame(frame, count)

        if (result.isSpeech) {
            if (!inSpeech) {
                inSpeech = true
                leadInBuffer.drainInto { buf, off, len ->
                    onEmitPcm(buf, off, len)
                    totalKeptSamples += len
                }
            }
            silenceRunMs = 0L
            onEmitPcm(frame, 0, count)
            totalKeptSamples += count
        } else {
            if (inSpeech) {
                if (silenceRunMs < keepPauseMillis) {
                    onEmitPcm(frame, 0, count)
                    totalKeptSamples += count
                    silenceRunMs += frameMs
                } else {
                    inSpeech = false
                    silenceRunMs = 0L
                    leadInBuffer.clear()
                    leadInBuffer.write(frame, 0, count)
                }
            } else {
                leadInBuffer.write(frame, 0, count)
            }
        }
    }

    companion object {
        private const val FRAME_SAMPLES = 512
        private const val LEAD_IN_SECONDS = 0.16f
    }
}
