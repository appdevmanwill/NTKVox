package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin, zero-allocation, offline Classical Digital Signal Processing (DSP) primitives.
 *
 * Provides:
 * 1. [BiquadFilter] & [VoiceBandFilter] — 2nd-order Butterworth High-Pass (120 Hz rumble cut) and
 *    Low-Pass (3,400 Hz speech isolation) filters.
 * 2. [Radix2Fft] — Fast 512-point in-place radix-2 Cooley-Tukey FFT with Hann windowing and
 *    voice-band Spectral Flatness (distinguishes tonal human speech harmonics from flat noise).
 * 3. [SpectralNoiseReducer] — 50% Overlap-Add spectral Wiener/oversubtraction noise reduction with
 *    adaptive spectral floor and 100 Hz rumble filter to attenuate steady room noise (fans, AC,
 *    hum, hiss) underneath speech without robotic/underwater artifacts.
 */
class BiquadFilter(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float
) {
    private var z1: Float = 0f
    private var z2: Float = 0f

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    /** Direct Form II Transposed — numerically stable and allocation-free. */
    fun processSample(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun processInPlace(buffer: FloatArray, length: Int = buffer.size) {
        for (i in 0 until length) {
            buffer[i] = processSample(buffer[i])
        }
    }

    companion object {
        private const val BUTTERWORTH_Q = 0.70710678f

        fun highPass(sampleRate: Int, cutoffHz: Float, q: Float = BUTTERWORTH_Q): BiquadFilter {
            val w0 = (2.0 * PI * cutoffHz / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2f * q)
            val a0 = 1f + alpha
            return BiquadFilter(
                b0 = ((1f + cosW0) * 0.5f) / a0,
                b1 = -(1f + cosW0) / a0,
                b2 = ((1f + cosW0) * 0.5f) / a0,
                a1 = (-2f * cosW0) / a0,
                a2 = (1f - alpha) / a0
            )
        }

        fun lowPass(sampleRate: Int, cutoffHz: Float, q: Float = BUTTERWORTH_Q): BiquadFilter {
            val w0 = (2.0 * PI * cutoffHz / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2f * q)
            val a0 = 1f + alpha
            return BiquadFilter(
                b0 = ((1f - cosW0) * 0.5f) / a0,
                b1 = (1f - cosW0) / a0,
                b2 = ((1f - cosW0) * 0.5f) / a0,
                a1 = (-2f * cosW0) / a0,
                a2 = (1f - alpha) / a0
            )
        }
    }
}

/**
 * Cascaded 120 Hz–3,400 Hz telephony/speech band-pass filter used to measure pure speech-band
 * energy independently of low-frequency rumble (AC, wind, table bumps) and high-frequency hiss.
 */
class VoiceBandFilter(sampleRate: Int = 16_000) {
    private val highPass = BiquadFilter.highPass(sampleRate, cutoffHz = 120f)
    private val lowPass = BiquadFilter.lowPass(sampleRate, cutoffHz = 3_400f)

    fun reset() {
        highPass.reset()
        lowPass.reset()
    }

    fun processSample(x: Float): Float = lowPass.processSample(highPass.processSample(x))

    fun filterPcmToNormalized(
        pcm: ShortArray,
        length: Int,
        outVoiceBand: FloatArray
    ) {
        val count = min(length, min(pcm.size, outVoiceBand.size))
        for (i in 0 until count) {
            val norm = pcm[i] / 32768f
            outVoiceBand[i] = lowPass.processSample(highPass.processSample(norm))
        }
    }
}

/**
 * Zero-allocation in-place Radix-2 FFT (size = 512 by default = 32 ms @ 16 kHz).
 * Pre-computes bit-reversal table, twiddle factors, and Hann window once at construction.
 */
class Radix2Fft(val size: Int = 512) {
    init {
        require(size > 0 && (size and (size - 1)) == 0) { "FFT size must be a power of 2" }
    }

    val halfBins: Int = size / 2 + 1
    private val bitRev = IntArray(size)
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    val hannWindow = FloatArray(size)

    init {
        val logN = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            bitRev[i] = Integer.reverse(i) ushr (32 - logN)
        }
        for (i in 0 until size / 2) {
            val angle = (-2.0 * PI * i / size)
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        for (i in 0 until size) {
            hannWindow[i] = (0.5 * (1.0 - cos(2.0 * PI * i / size))).toFloat()
        }
    }

    /** Forward in-place complex FFT. */
    fun forward(real: FloatArray, imag: FloatArray) {
        for (i in 0 until size) {
            val j = bitRev[i]
            if (j > i) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var halfSize = 1
        while (halfSize < size) {
            val step = size / (halfSize * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in 0 until halfSize) {
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val idx1 = i + j
                    val idx2 = idx1 + halfSize
                    val tReal = real[idx2] * c - imag[idx2] * s
                    val tImag = real[idx2] * s + imag[idx2] * c
                    real[idx2] = real[idx1] - tReal
                    imag[idx2] = imag[idx1] - tImag
                    real[idx1] += tReal
                    imag[idx1] += tImag
                    k += step
                }
                i += halfSize * 2
            }
            halfSize *= 2
        }
    }

    /** Inverse in-place complex FFT (conjugate -> forward -> conjugate / N). */
    fun inverse(real: FloatArray, imag: FloatArray) {
        for (i in 0 until size) {
            imag[i] = -imag[i]
        }
        forward(real, imag)
        val invN = 1f / size
        for (i in 0 until size) {
            real[i] *= invN
            imag[i] = -imag[i] * invN
        }
    }

    /**
     * Computes single-sided magnitude spectrum (0..size/2) for a real-valued normalized buffer.
     * Applies Hann windowing cleanly without allocating.
     */
    fun computeMagnitudeSpectrum(
        samples: FloatArray,
        length: Int,
        workReal: FloatArray,
        workImag: FloatArray,
        outMagnitudes: FloatArray
    ) {
        val count = min(length, size)
        for (i in 0 until count) {
            workReal[i] = samples[i] * hannWindow[i]
            workImag[i] = 0f
        }
        for (i in count until size) {
            workReal[i] = 0f
            workImag[i] = 0f
        }
        forward(workReal, workImag)
        for (k in 0 until halfBins) {
            val r = workReal[k]
            val im = workImag[k]
            outMagnitudes[k] = sqrt(r * r + im * im)
        }
    }

    /**
     * Computes Spectral Flatness (Wiener entropy: geometric mean / arithmetic mean of power)
     * across the human speech band (~200 Hz – 3,600 Hz).
     *
     * - Tonal human vowels & harmonic speech: low flatness (~0.01 – 0.35).
     * - Broadband steady noise (white noise, rain, fan airflow, static): high flatness (~0.55 – 0.98).
     */
    fun computeVoiceBandFlatness(magnitudes: FloatArray, sampleRate: Int = 16_000): Float {
        val binHz = sampleRate.toFloat() / size.toFloat()
        val startBin = (200f / binHz).toInt().coerceIn(1, halfBins - 2)
        val endBin = (3_600f / binHz).toInt().coerceIn(startBin + 1, halfBins - 1)
        val count = endBin - startBin + 1

        var sumPower = 0.0
        var sumLogPower = 0.0
        val eps = 1e-12
        for (k in startBin..endBin) {
            val mag = magnitudes[k].toDouble()
            val power = mag * mag + eps
            sumPower += power
            sumLogPower += ln(power)
        }
        val arithmeticMean = sumPower / count
        if (arithmeticMean <= eps * 10.0) return 1f
        val geometricMean = exp(sumLogPower / count)
        return (geometricMean / arithmeticMean).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Pure-Kotlin 50% Overlap-Add Spectral Noise Reducer + 100 Hz High-Pass Rumble Filter.
 *
 * Uses the per-bin noise spectrum learned during Pass 1 (from the recording's quietest frames)
 * to gently attenuate steady background noise (AC, fan, room hum, static hiss) by ~10–14 dB
 * underneath foreground speech. A protective spectral floor (0.20) and temporal gain smoothing
 * prevent robotic/underwater "musical noise" artifacts.
 */
class SpectralNoiseReducer(
    private val sampleRate: Int = 16_000,
    noiseSpectrum: FloatArray,
    private val oversubtraction: Float = 1.45f,
    private val spectralFloor: Float = 0.20f
) {
    private val fft = Radix2Fft(FFT_SIZE)
    private val noiseMag = FloatArray(fft.halfBins) { idx ->
        noiseSpectrum.getOrElse(idx) { 0f }.coerceAtLeast(0f)
    }
    private val prevGains = FloatArray(fft.halfBins) { 1f }
    private val rumbleFilter = BiquadFilter.highPass(sampleRate, cutoffHz = 100f)

    // 50% overlap-add ring buffers (hop = 256 samples = 16 ms @ 16 kHz)
    private val inputRing = FloatArray(FFT_SIZE)
    private var inputFill = 0
    private val overlapTail = FloatArray(HOP_SIZE)
    private val workReal = FloatArray(FFT_SIZE)
    private val workImag = FloatArray(FFT_SIZE)
    private val outShortHop = ShortArray(HOP_SIZE)

    fun reset() {
        rumbleFilter.reset()
        inputRing.fill(0f)
        overlapTail.fill(0f)
        prevGains.fill(1f)
        inputFill = 0
    }

    fun processPcm(
        samples: ShortArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
        onEmit: (ShortArray, Int, Int) -> Unit
    ) {
        var idx = offset
        val end = offset + length
        while (idx < end) {
            val filtered = rumbleFilter.processSample(samples[idx] / 32768f)
            inputRing[inputFill++] = filtered
            idx++

            if (inputFill == FFT_SIZE) {
                processWindowedFrame(onEmit)
                // Shift second half into first half (50% overlap)
                System.arraycopy(inputRing, HOP_SIZE, inputRing, 0, HOP_SIZE)
                inputFill = HOP_SIZE
            }
        }
    }

    fun flush(onEmit: (ShortArray, Int, Int) -> Unit) {
        if (inputFill > 0) {
            for (i in inputFill until FFT_SIZE) {
                inputRing[i] = 0f
            }
            processWindowedFrame(onEmit)
            // Emit remaining tail
            val remaining = min(inputFill, HOP_SIZE)
            for (i in 0 until remaining) {
                outShortHop[i] = floatToPcmShort(overlapTail[i])
            }
            if (remaining > 0) {
                onEmit(outShortHop, 0, remaining)
            }
        }
        reset()
    }

    private fun processWindowedFrame(onEmit: (ShortArray, Int, Int) -> Unit) {
        for (i in 0 until FFT_SIZE) {
            workReal[i] = inputRing[i] * fft.hannWindow[i]
            workImag[i] = 0f
        }
        fft.forward(workReal, workImag)

        // Apply gentle Wiener / Spectral-Subtraction gain per positive frequency bin
        for (k in 0 until fft.halfBins) {
            val r = workReal[k]
            val im = workImag[k]
            val mag = sqrt(r * r + im * im)
            val nMag = noiseMag[k]

            val targetGain = if (mag <= 1e-7f || nMag <= 1e-7f) {
                1f
            } else {
                val excess = (mag - oversubtraction * nMag) / mag
                excess.coerceIn(spectralFloor, 1f)
            }
            // Temporal smoothing avoids musical chirping artifacts
            val smoothGain = prevGains[k] * 0.45f + targetGain * 0.55f
            prevGains[k] = smoothGain

            workReal[k] = r * smoothGain
            workImag[k] = im * smoothGain

            // Mirror Hermitian symmetry for negative frequency bins
            if (k in 1 until (FFT_SIZE / 2)) {
                val mirror = FFT_SIZE - k
                workReal[mirror] *= smoothGain
                workImag[mirror] *= smoothGain
            }
        }

        fft.inverse(workReal, workImag)

        // 50% overlap-add with Hann window has exact COLA sum = 1.0 (no extra scaling needed)
        for (i in 0 until HOP_SIZE) {
            val sample = overlapTail[i] + workReal[i]
            outShortHop[i] = floatToPcmShort(sample)
            overlapTail[i] = workReal[i + HOP_SIZE]
        }
        onEmit(outShortHop, 0, HOP_SIZE)
    }

    companion object {
        const val FFT_SIZE = 512
        const val HOP_SIZE = 256

        fun floatToPcmShort(sample: Float): Short {
            val clamped = max(-1f, min(1f, sample))
            return (clamped * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
