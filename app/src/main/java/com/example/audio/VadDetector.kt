package com.example.audio

import com.example.model.VoiceSensitivity
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Output of a single PCM frame evaluation (typically 512 samples / 32 ms at 16 kHz).
 */
data class VadResult(
    val speechProbability: Float,
    val isSpeech: Boolean,
    val normalizedLevel: Float,
    val rmsDb: Float,
    val noiseFloorDb: Float,
    val isNoiseRejected: Boolean = false,
    val voiceBandRatio: Float = 1f,
    val spectralFlatness: Float = 0f
)

/**
 * Swappable Voice Activity Detection interface.
 * Keeps audio capture, state transitions, and UI decoupled from the detection algorithm.
 */
interface VadDetector {
    fun processFrame(pcmFrame: ShortArray, length: Int = pcmFrame.size): VadResult
    fun updateSensitivity(sensitivity: VoiceSensitivity)
    fun reset()
}

/**
 * Pure-Kotlin, zero-AI, multi-feature Classical DSP Voice Activity Detector.
 *
 * Battery-conscious two-stage algorithm (zero allocations per frame):
 * 1. **Stage 1 (Fast Voice-Band Energy Gate)**: Runs a 120 Hz–3,400 Hz biquad filter to strip
 *    low-frequency AC/wind/table rumble and high-frequency hiss, computing both full-band and
 *    voice-band RMS dBFS. Silent frames skip Stage 2 FFT completely for near-zero CPU draw.
 * 2. **Stage 2 (Spectral Speech Classifier — only when energy exceeds ambient floor)**:
 *    - **Voice-Band Energy Ratio**: Rejects out-of-band rumble and high-frequency clicks/hiss.
 *    - **Zero-Crossing Rate (ZCR)**: Rejects broadband static and high-frequency rustling.
 *    - **512-pt FFT Spectral Flatness**: Distinguishes tonal human speech harmonics (low flatness)
 *      from flat broadband noise like fans, rain, and airflow (high flatness).
 *    - **Prominence Margin ([VoiceSensitivity])**: Supports Quiet Voices, Normal Room, and
 *      Prominent Only (ignores distant hallway chatter and steady background noise).
 */
class EnergyVadDetector(
    initialSensitivity: VoiceSensitivity = VoiceSensitivity.QUIET_VOICES,
    private val sampleRate: Int = 16_000
) : VadDetector {

    @Volatile
    private var sensitivity: VoiceSensitivity = initialSensitivity

    private val voiceFilter = VoiceBandFilter(sampleRate)
    private val fft = Radix2Fft(FFT_SIZE)

    // Pre-allocated zero-GC scratch buffers
    private val voiceScratch = FloatArray(FFT_SIZE)
    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)
    private val fftMags = FloatArray(fft.halfBins)

    private var noiseFloorDb: Float = INITIAL_NOISE_FLOOR_DB
    private var warmupFrames: Int = 0

    override fun updateSensitivity(sensitivity: VoiceSensitivity) {
        this.sensitivity = sensitivity
    }

    override fun reset() {
        noiseFloorDb = INITIAL_NOISE_FLOOR_DB
        warmupFrames = 0
        voiceFilter.reset()
    }

    override fun processFrame(pcmFrame: ShortArray, length: Int): VadResult {
        val count = min(length, min(pcmFrame.size, FFT_SIZE))
        if (count <= 0) {
            return VadResult(
                speechProbability = 0f,
                isSpeech = false,
                normalizedLevel = 0f,
                rmsDb = MIN_DB,
                noiseFloorDb = noiseFloorDb,
                isNoiseRejected = false,
                voiceBandRatio = 0f,
                spectralFlatness = 1f
            )
        }

        // 1. Compute full-band & 120 Hz–3.4 kHz voice-band energy + Zero-Crossing Rate in one pass
        var fullSumSq = 0.0
        var voiceSumSq = 0.0
        var zeroCrossings = 0
        var prevVoiceSample = 0f

        for (i in 0 until count) {
            val raw = pcmFrame[i] / 32768f
            fullSumSq += raw * raw
            val v = voiceFilter.processSample(raw)
            voiceScratch[i] = v
            voiceSumSq += v * v
            if (i > 0 && ((v >= 0f && prevVoiceSample < 0f) || (v < 0f && prevVoiceSample >= 0f))) {
                zeroCrossings++
            }
            prevVoiceSample = v
        }

        val fullRms = sqrt(fullSumSq / count).toFloat()
        val voiceRms = sqrt(voiceSumSq / count).toFloat()
        val fullDb = linearToDb(fullRms)
        val voiceDb = linearToDb(voiceRms)
        val voiceBandRatio = if (fullSumSq > 1e-10) {
            (voiceSumSq / fullSumSq).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val zcr = zeroCrossings.toFloat() / count.toFloat()

        // 2. Calibrate ambient voice-band floor across the first ~15 frames (~480 ms)
        if (warmupFrames < WARMUP_FRAME_COUNT) {
            warmupFrames++
            val clampedFloorSample = min(voiceDb, -40f)
            noiseFloorDb = if (warmupFrames == 1) {
                clampedFloorSample
            } else {
                noiseFloorDb * 0.85f + clampedFloorSample * 0.15f
            }
        }

        val currentSensitivity = sensitivity
        val snrDb = voiceDb - noiseFloorDb
        val fullSnrDb = fullDb - noiseFloorDb

        // 3. Stage 1 fast energy pre-gate: silent frames skip FFT completely (saves battery!)
        val candidateEnergy = voiceDb >= (currentSensitivity.minAbsoluteSpeechDb - 4f) &&
            snrDb >= (currentSensitivity.snrMarginDb - 3f)

        var spectralFlatness = 0f
        var speechProbability = 0f
        var isSpeech = false
        var isNoiseRejected = false

        if (candidateEnergy) {
            // Stage 2: Spectral Flatness + Voice-Band Ratio + ZCR speech verification
            fft.computeMagnitudeSpectrum(voiceScratch, count, fftReal, fftImag, fftMags)
            spectralFlatness = fft.computeVoiceBandFlatness(fftMags, sampleRate)

            val rawEnergyScore = if (voiceDb < currentSensitivity.minAbsoluteSpeechDb) {
                0f
            } else {
                ((snrDb - (currentSensitivity.snrMarginDb - 2.5f)) / 12f).coerceIn(0f, 1f)
            }

            // Broadband noise / wind / rustling / rumble rejection
            val passesVoiceBand = voiceBandRatio >= currentSensitivity.minVoiceBandRatio
            val passesZcr = zcr in MIN_SPEECH_ZCR..MAX_SPEECH_ZCR
            val passesFlatness = spectralFlatness <= currentSensitivity.maxSpectralFlatness

            val spectralPenalty = when {
                !passesVoiceBand -> 0.25f
                !passesFlatness -> 0.30f
                !passesZcr -> 0.45f
                else -> 1.0f
            }

            speechProbability = (rawEnergyScore * spectralPenalty).coerceIn(0f, 1f)
            isSpeech = speechProbability >= currentSensitivity.speechStartThreshold

            // Flag audible background noise / distant sound that was actively filtered out
            if (!isSpeech && (fullSnrDb >= 5.5f || snrDb >= 5.0f) && fullDb > -52f) {
                isNoiseRejected = true
            }
        } else if (fullSnrDb >= 6.5f && fullDb > -48f) {
            // Audible out-of-band rumble/hiss or distant quiet chatter below prominent cutoff
            isNoiseRejected = true
        }

        // 4. Adapt ambient noise floor: fast downward, slow upward on non-speech frames
        //    (Allows steady fan/AC hum that fails spectral speech check to become part of noise floor!)
        noiseFloorDb = when {
            voiceDb < noiseFloorDb -> noiseFloorDb * 0.90f + voiceDb * 0.10f
            !isSpeech && (snrDb < currentSensitivity.snrMarginDb || isNoiseRejected) ->
                noiseFloorDb * 0.990f + voiceDb * 0.010f
            else -> noiseFloorDb
        }.coerceIn(MIN_NOISE_FLOOR_DB, MAX_NOISE_FLOOR_DB)

        // Normalized [0..1] visual meter level mapped across -65 dBFS .. -10 dBFS
        val normalizedLevel = ((fullDb + 65f) / 55f).coerceIn(0f, 1f)

        return VadResult(
            speechProbability = speechProbability,
            isSpeech = isSpeech,
            normalizedLevel = normalizedLevel,
            rmsDb = voiceDb,
            noiseFloorDb = noiseFloorDb,
            isNoiseRejected = isNoiseRejected,
            voiceBandRatio = voiceBandRatio,
            spectralFlatness = spectralFlatness
        )
    }

    private fun linearToDb(amplitude: Float): Float {
        if (amplitude <= 1e-6f) return MIN_DB
        return (20.0 * ln(amplitude.toDouble()) / LN10).toFloat().coerceIn(MIN_DB, 0f)
    }

    companion object {
        private val LN10 = ln(10.0)
        private const val FFT_SIZE = 512
        private const val MIN_DB = -90f
        private const val INITIAL_NOISE_FLOOR_DB = -62f
        private const val MIN_NOISE_FLOOR_DB = -78f
        private const val MAX_NOISE_FLOOR_DB = -26f
        private const val WARMUP_FRAME_COUNT = 15
        private const val MIN_SPEECH_ZCR = 0.008f
        private const val MAX_SPEECH_ZCR = 0.42f
    }
}
