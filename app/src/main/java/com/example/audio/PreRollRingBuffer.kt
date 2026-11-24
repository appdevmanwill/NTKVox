package com.example.audio

import kotlin.math.min

/**
 * Thread-safe circular PCM ring buffer that holds up to 2.0 seconds of 16 kHz, 16-bit mono PCM
 * audio (32,000 samples / 64,000 bytes in RAM).
 *
 * Continuously stores incoming audio frames while listening so that when voice activity is
 * detected, the accumulated pre-roll history can be retrieved in exact chronological order
 * without clipping opening syllables.
 */
class PreRollRingBuffer(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    durationSeconds: Float = DEFAULT_DURATION_SECONDS
) {
    /** Maximum number of 16-bit mono PCM samples held by the ring buffer (default: 32,000). */
    val capacitySamples: Int = (sampleRate * durationSeconds).toInt().coerceAtLeast(1)

    /** Maximum byte footprint of the 16-bit PCM payload (default: 64,000 bytes). */
    val capacityBytes: Int = capacitySamples * BYTES_PER_SAMPLE

    private val buffer = ShortArray(capacitySamples)
    private var writeIndex = 0
    private var validCount = 0

    val sizeSamples: Int
        @Synchronized get() = validCount

    val isEmpty: Boolean
        @Synchronized get() = validCount == 0

    val isFull: Boolean
        @Synchronized get() = validCount == capacitySamples

    val durationMillis: Long
        @Synchronized get() = (validCount * 1000L) / sampleRate

    /**
     * Writes [length] 16-bit PCM samples from [samples] starting at [offset] into the ring buffer,
     * overwriting the oldest samples once capacity is reached. Performs zero heap allocations.
     */
    @Synchronized
    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        if (length <= 0 || offset < 0 || offset >= samples.size) return

        var srcPos = offset
        var remaining = min(length, samples.size - offset)
        if (remaining <= 0) return

        // If the incoming frame meets or exceeds total ring capacity, keep only the newest tail.
        if (remaining >= capacitySamples) {
            srcPos += remaining - capacitySamples
            System.arraycopy(samples, srcPos, buffer, 0, capacitySamples)
            writeIndex = 0
            validCount = capacitySamples
            return
        }

        val firstChunk = min(remaining, capacitySamples - writeIndex)
        System.arraycopy(samples, srcPos, buffer, writeIndex, firstChunk)
        writeIndex = (writeIndex + firstChunk) % capacitySamples

        val secondChunk = remaining - firstChunk
        if (secondChunk > 0) {
            System.arraycopy(samples, srcPos + firstChunk, buffer, 0, secondChunk)
            writeIndex = secondChunk
        }

        validCount = min(capacitySamples, validCount + remaining)
    }

    /**
     * Returns a chronological copy of all accumulated PCM history without clearing the buffer.
     */
    @Synchronized
    fun peekToArray(): ShortArray {
        if (validCount == 0) return ShortArray(0)
        val out = ShortArray(validCount)
        val startIndex = if (validCount < capacitySamples) 0 else writeIndex
        val firstPart = min(validCount, capacitySamples - startIndex)
        System.arraycopy(buffer, startIndex, out, 0, firstPart)
        val secondPart = validCount - firstPart
        if (secondPart > 0) {
            System.arraycopy(buffer, 0, out, firstPart, secondPart)
        }
        return out
    }

    /**
     * Retrieves all accumulated PCM history in exact chronological order and clears the buffer.
     */
    @Synchronized
    fun drainToArray(): ShortArray {
        val out = peekToArray()
        clear()
        return out
    }

    /**
     * Zero-allocation chronological flush directly into [consumer] (e.g., an audio chunk writer),
     * invoking [consumer] up to twice (tail segment then wrapped head segment), and clears the ring.
     *
     * @return total number of PCM samples flushed.
     */
    @Synchronized
    fun drainInto(consumer: (buffer: ShortArray, offset: Int, length: Int) -> Unit): Int {
        val total = validCount
        if (total == 0) return 0

        val startIndex = if (total < capacitySamples) 0 else writeIndex
        val firstPart = min(total, capacitySamples - startIndex)
        consumer(buffer, startIndex, firstPart)

        val secondPart = total - firstPart
        if (secondPart > 0) {
            consumer(buffer, 0, secondPart)
        }

        clear()
        return total
    }

    /**
     * Discards buffered audio history without reallocating the backing PCM array.
     */
    @Synchronized
    fun clear() {
        writeIndex = 0
        validCount = 0
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val DEFAULT_DURATION_SECONDS = 2.0f
        const val BYTES_PER_SAMPLE = 2
    }
}
