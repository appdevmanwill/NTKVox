package com.example

import com.example.audio.EnergyVadDetector
import com.example.audio.PreRollRingBuffer
import com.example.audio.VoxAction
import com.example.audio.VoxState
import com.example.audio.VoxStateMachine
import com.example.model.AudioFormatOption
import com.example.model.RecordingMode
import com.example.model.RecordingSession
import com.example.model.SessionStatus
import com.example.model.VoiceSensitivity
import com.example.speech.RuleBasedSummarizer
import com.example.storage.AacAdtsChunkWriter
import com.example.storage.AudioRemuxer
import com.example.storage.PcmResampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

class VoxEngineUnitTest {

    @Test
    fun preRollRingBuffer_holdsTwoSecondsAt16kHzMonoAndWrapsChronologically() {
        val defaultRing = PreRollRingBuffer()
        assertEquals(32_000, defaultRing.capacitySamples)
        assertEquals(64_000, defaultRing.capacityBytes)
        assertTrue(defaultRing.isEmpty)

        val buffer = PreRollRingBuffer(sampleRate = 10, durationSeconds = 1.0f) // capacity 10
        buffer.write(shortArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(6, buffer.sizeSamples)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6), buffer.peekToArray())

        buffer.write(shortArrayOf(7, 8, 9, 10, 11, 12))
        assertTrue(buffer.isFull)
        assertEquals(1000L, buffer.durationMillis)

        // Zero-copy drainInto delivers chronological segments then clears
        val streamed = mutableListOf<Short>()
        val flushedCount = buffer.drainInto { arr, offset, length ->
            for (i in offset until offset + length) {
                streamed.add(arr[i])
            }
        }
        assertEquals(10, flushedCount)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12), streamed.toShortArray())
        assertEquals(0, buffer.sizeSamples)

        // Oversized write retains only the newest capacitySamples
        buffer.write(shortArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120))
        assertArrayEquals(
            shortArrayOf(30, 40, 50, 60, 70, 80, 90, 100, 110, 120),
            buffer.drainToArray()
        )
    }

    @Test
    fun voxStateMachine_follows4StateHysteresisAnd10sHangover() {
        val sm = VoxStateMachine(
            confirmDurationMs = 96L,
            maxChunkDurationMs = 60_000L,
            silenceDelaySeconds = 10,
            sensitivity = VoiceSensitivity.QUIET_VOICES
        )
        sm.startListening()
        assertEquals(VoxState.LISTENING, sm.state)

        // Frame 1 & 2 above 0.35 -> SPEECH_CONFIRMING
        assertEquals(VoxAction.KeepListening, sm.onFrame(0.60f, 32L))
        assertEquals(VoxState.SPEECH_CONFIRMING, sm.state)
        assertEquals(VoxAction.KeepListening, sm.onFrame(0.60f, 32L))
        // Frame 3 reaches 96 ms -> RECORDING + OpenChunkAndFlushPreRoll
        assertEquals(VoxAction.OpenChunkAndFlushPreRoll, sm.onFrame(0.60f, 32L))
        assertEquals(VoxState.RECORDING, sm.state)

        // Silence drops below stop threshold -> HANGOVER_SILENCE
        assertEquals(VoxAction.WriteLiveFrame, sm.onFrame(0.05f, 32L))
        assertEquals(VoxState.HANGOVER_SILENCE, sm.state)

        // Speech returns before 10s -> cancels countdown and returns to RECORDING seamlessly
        assertEquals(VoxAction.WriteLiveFrame, sm.onFrame(0.55f, 32L))
        assertEquals(VoxState.RECORDING, sm.state)

        // Now sustain silence for 10 full seconds
        var finalized = false
        for (i in 0 until 400) {
            val action = sm.onFrame(0.02f, 32L)
            if (action == VoxAction.FinalizeChunk) {
                finalized = true
                break
            }
        }
        assertTrue("Expected chunk finalization after 10s silence", finalized)
        assertEquals(VoxState.LISTENING, sm.state)
    }

    @Test
    fun energyVad_detectsSoftSpeechAboveNoiseFloorAndRejectsSilence() {
        val vad = EnergyVadDetector(initialSensitivity = VoiceSensitivity.QUIET_VOICES)
        val quietFloor = ShortArray(512) { idx -> if (idx % 2 == 0) 6 else -6 }
        repeat(20) { vad.processFrame(quietFloor) }

        val silenceResult = vad.processFrame(quietFloor)
        assertTrue(
            "Expected ambient floor not to trigger speech (prob=${silenceResult.speechProbability})",
            !silenceResult.isSpeech
        )

        // Generate soft voice-level signal (~ -30 dBFS)
        val softVoice = ShortArray(512) { i ->
            (sin(2.0 * PI * 900.0 * i / 16_000.0) * 1000.0).toInt().toShort()
        }
        val speechResult = vad.processFrame(softVoice)
        assertTrue(
            "Expected soft voice signal to be detected as speech (prob=${speechResult.speechProbability})",
            speechResult.isSpeech
        )
    }

    @Test
    fun ruleBasedSummarizer_extractsConciseSummaryHighlightsAndKeywords() {
        val session = RecordingSession(
            id = "test1",
            title = "Morning Standup",
            folderName = "session_test",
            startTimeMillis = 1000L,
            endTimeMillis = 61000L,
            format = AudioFormatOption.OGG_OPUS,
            status = SessionStatus.READY,
            chunks = emptyList()
        )
        val transcript = """
            [09:00:00 AM] We reviewed the quarterly budget plan for the kitchen renovation project.
            [09:01:15 AM] Remember to call the electrician tomorrow morning before 10 AM to confirm the wiring schedule.
            [09:02:30 AM] The weather outside looks sunny and calm today.
            [09:03:10 AM] Important decision: we will keep the budget under five thousand dollars and finalize the schedule by Friday.
        """.trimIndent()

        val notes = RuleBasedSummarizer.summarize(transcript, session)
        assertTrue(notes.summary.isNotBlank())
        assertTrue(notes.highlights.isNotEmpty())
        assertTrue(notes.keywords.isNotEmpty())
        assertTrue(
            "Expected summary to capture budget/schedule decision",
            notes.summary.contains("budget", ignoreCase = true) ||
                notes.summary.contains("schedule", ignoreCase = true)
        )
    }

    @Test
    fun wavChunkWriter_writesValid44ByteHeaderAndRepairsInterruptedFile() {
        val tempFile = java.io.File.createTempFile("clearvox_test_", ".wav")
        try {
            val writer = com.example.storage.WavChunkWriter()
            writer.open(tempFile, sampleRate = 16_000, channels = 1)
            val oneSecondPcm = ShortArray(16_000) { i -> (i % 1000).toShort() }
            writer.writePcm(oneSecondPcm)
            writer.syncToDisk()
            val finalized = writer.closeAndFinalize()

            assertEquals(1000L, finalized.durationMillis)
            assertEquals(44L + 32_000L, finalized.sizeBytes)

            // Simulate sudden power loss leaving zeroed RIFF/data sizes + an odd trailing byte
            java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(0L)
                raf.write(com.example.storage.WavChunkWriter.buildWavHeader(0L, 16_000, 1))
                raf.seek(raf.length())
                raf.writeByte(0x7F)
            }

            val recoveredMs = com.example.storage.WavChunkWriter.repairWavHeaderIfNeeded(tempFile)
            assertEquals(1000L, recoveredMs)
            assertEquals(44L + 32_000L, tempFile.length())

            // Verify patched RIFF & data chunk sizes in 44-byte header
            val header = java.nio.ByteBuffer.wrap(tempFile.readBytes().copyOf(44))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(32_000 + 36, header.getInt(4))
            assertEquals(32_000, header.getInt(40))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun aacAdtsRepair_trimsPartialTrailingFrameCleanly() {
        val tempFile = java.io.File.createTempFile("clearvox_test_", ".aac")
        try {
            // Build two valid 10-byte ADTS frames followed by a 4-byte truncated tail
            fun makeAdtsFrame(len: Int): ByteArray {
                val frame = ByteArray(len)
                frame[0] = 0xFF.toByte()
                frame[1] = 0xF9.toByte()
                frame[2] = 0x60.toByte()
                frame[3] = ((1 shl 6) or (len shr 11)).toByte()
                frame[4] = ((len and 0x7FF) shr 3).toByte()
                frame[5] = (((len and 7) shl 5) or 0x1F).toByte()
                frame[6] = 0xFC.toByte()
                return frame
            }

            val payload = makeAdtsFrame(10) + makeAdtsFrame(10) + byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x60, 0x40)
            tempFile.writeBytes(payload)

            val recoveredMs = com.example.storage.AacAdtsChunkWriter.repairAdtsTailIfNeeded(tempFile)
            assertEquals(128L, recoveredMs) // 2 valid AAC-LC frames @ 16 kHz = 2 * 64 ms
            assertEquals(20L, tempFile.length())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun recordingMode_fromIdDefaultsToVoiceActivatedAndRoundTrips() {
        assertEquals(RecordingMode.VOICE_ACTIVATED, RecordingMode.fromId(null))
        assertEquals(RecordingMode.VOICE_ACTIVATED, RecordingMode.fromId("unknown_mode"))
        assertEquals(RecordingMode.VOICE_ACTIVATED, RecordingMode.fromId(RecordingMode.VOICE_ACTIVATED.id))
        assertEquals(RecordingMode.CONTINUOUS, RecordingMode.fromId(RecordingMode.CONTINUOUS.id))
    }

    @Test
    fun mp3ChunkWriter_encodesValidPureJavaMp3BitstreamAt16kHzMono() {
        val tempFile = java.io.File.createTempFile("ntkvox_test_", ".mp3")
        try {
            val writer = com.example.storage.Mp3ChunkWriter()
            writer.open(tempFile, sampleRate = 16_000, channels = 1)

            // Write 1.0 second of 440 Hz sine wave PCM in 512-sample frames
            val totalSamples = 16_000
            val frame = ShortArray(512)
            var idx = 0
            while (idx < totalSamples) {
                val count = minOf(512, totalSamples - idx)
                for (i in 0 until count) {
                    val sampleIdx = idx + i
                    frame[i] = (sin(2.0 * PI * 440.0 * sampleIdx / 16_000.0) * 12_000.0).toInt().toShort()
                }
                writer.writePcm(frame, 0, count)
                idx += count
            }
            writer.syncToDisk()
            val result = writer.closeAndFinalize()

            assertEquals("mp3", result.actualExtension)
            assertEquals(1000L, result.durationMillis)
            assertTrue("Expected non-empty MP3 file, got ${result.sizeBytes} bytes", result.sizeBytes > 2000L)

            val bytes = tempFile.readBytes()
            // Verify standard MPEG audio frame sync header (11 high bits: 0xFF followed by 0xE0 mask)
            val hasMpegSync = (0 until bytes.size - 1).any { pos ->
                (bytes[pos].toInt() and 0xFF) == 0xFF &&
                    ((bytes[pos + 1].toInt() and 0xE0) == 0xE0)
            }
            assertTrue("Expected valid MPEG Layer III frame sync bytes in encoded MP3", hasMpegSync)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun recordingCategoryAndScheduleRepeat_roundTripCleanly() {
        assertEquals(com.example.model.RecordingCategory.GENERAL, com.example.model.RecordingCategory.fromId(null))
        assertEquals(com.example.model.RecordingCategory.MEETINGS, com.example.model.RecordingCategory.fromId("meetings"))
        assertEquals(com.example.model.ScheduleRepeat.ONCE, com.example.model.ScheduleRepeat.fromId(null))
        assertEquals(com.example.model.ScheduleRepeat.WEEKLY, com.example.model.ScheduleRepeat.fromId("weekly"))
    }

    @Test
    fun silenceRemover_stripsLongQuietGapWhilePreservingSpeechAndNaturalPause() {
        val emitted = mutableListOf<Short>()
        val remover = com.example.audio.SilenceRemover(
            sampleRate = 16_000,
            sensitivity = VoiceSensitivity.QUIET_VOICES,
            keepPauseMillis = 500L
        ) { samples, offset, length ->
            for (i in offset until offset + length) {
                emitted.add(samples[i])
            }
        }

        val quietFrame = ShortArray(512) { idx -> if (idx % 2 == 0) 5 else -5 }
        val voiceFrame = ShortArray(512) { i ->
            (sin(2.0 * PI * 850.0 * i / 16_000.0) * 6_000.0).toInt().toShort()
        }

        // Warmup ambient noise floor (~0.5s)
        repeat(16) { remover.processPcm(quietFrame) }
        // First spoken segment (~1.0s = 32 frames)
        repeat(32) { remover.processPcm(voiceFrame) }
        // Long 4.0s silent gap (125 frames)
        repeat(125) { remover.processPcm(quietFrame) }
        // Second spoken segment (~1.0s = 32 frames)
        repeat(32) { remover.processPcm(voiceFrame) }
        // Trailing 2.0s silence (63 frames)
        repeat(63) { remover.processPcm(quietFrame) }
        remover.finish()

        val inputMs = remover.inputDurationMillis
        val keptMs = remover.keptDurationMillis

        assertTrue("Expected total input > 7500 ms, got $inputMs", inputMs > 7_500L)
        assertTrue("Expected both speech segments preserved (>= 1800 ms), got $keptMs", keptMs >= 1_800L)
        assertTrue(
            "Expected long silent gaps removed so keptMs ($keptMs) is well under 5000 ms",
            keptMs < 5_000L
        )
        assertEquals(remover.totalKeptSamples.toInt(), emitted.size)
    }

    @Test
    fun batteryOptimizationProfile_defaultsToAdaptiveEcoAndRoundTrips() {
        assertEquals(
            com.example.model.BatteryOptimizationProfile.ADAPTIVE_ECO,
            com.example.model.BatteryOptimizationProfile.fromId(null)
        )
        assertEquals(
            com.example.model.BatteryOptimizationProfile.ADAPTIVE_ECO,
            com.example.model.BatteryOptimizationProfile.fromId("unknown")
        )
        assertEquals(
            com.example.model.BatteryOptimizationProfile.ULTRA_SAVER,
            com.example.model.BatteryOptimizationProfile.fromId(
                com.example.model.BatteryOptimizationProfile.ULTRA_SAVER.id
            )
        )
        assertEquals(
            com.example.model.BatteryOptimizationProfile.BALANCED,
            com.example.model.BatteryOptimizationProfile.fromId(
                com.example.model.BatteryOptimizationProfile.BALANCED.id
            )
        )
    }

    @Test
    fun voiceBandFilter_attenuatesLowRumbleAndPreservesMidbandVoice() {
        val filter = com.example.audio.VoiceBandFilter(sampleRate = 16_000)
        // 50 Hz AC mains rumble should be heavily attenuated by 120 Hz HPF
        val rumble = FloatArray(1024) { i -> sin(2.0 * PI * 50.0 * i / 16_000.0).toFloat() }
        var rumbleOutSq = 0.0
        for (i in rumble.indices) {
            val y = filter.processSample(rumble[i])
            if (i >= 256) rumbleOutSq += y * y
        }
        val rumbleRms = kotlin.math.sqrt(rumbleOutSq / 768.0)

        filter.reset()
        // 1,000 Hz human voice harmonic should pass with near-unity gain (~0.707 RMS)
        val voice = FloatArray(1024) { i -> sin(2.0 * PI * 1_000.0 * i / 16_000.0).toFloat() }
        var voiceOutSq = 0.0
        for (i in voice.indices) {
            val y = filter.processSample(voice[i])
            if (i >= 256) voiceOutSq += y * y
        }
        val voiceRms = kotlin.math.sqrt(voiceOutSq / 768.0)

        assertTrue("Expected 50 Hz rumble attenuated (<0.20 RMS), got $rumbleRms", rumbleRms < 0.20)
        assertTrue("Expected 1 kHz voice preserved (>0.60 RMS), got $voiceRms", voiceRms > 0.60)
    }

    @Test
    fun radix2Fft_spectralFlatnessSeparatesTonalVoiceFromBroadbandNoise() {
        val fft = com.example.audio.Radix2Fft(512)
        val workR = FloatArray(512)
        val workI = FloatArray(512)
        val mags = FloatArray(fft.halfBins)

        // Tonal voice-like signal (400 Hz + 800 Hz + 1200 Hz harmonics)
        val tonal = FloatArray(512) { i ->
            (0.5 * sin(2.0 * PI * 400.0 * i / 16_000.0) +
                0.35 * sin(2.0 * PI * 800.0 * i / 16_000.0) +
                0.20 * sin(2.0 * PI * 1_200.0 * i / 16_000.0)).toFloat()
        }
        fft.computeMagnitudeSpectrum(tonal, 512, workR, workI, mags)
        val tonalFlatness = fft.computeVoiceBandFlatness(mags, 16_000)

        // Deterministic broadband pseudo-random white noise
        val rng = java.util.Random(42L)
        val whiteNoise = FloatArray(512) { (rng.nextFloat() * 2f - 1f) * 0.4f }
        fft.computeMagnitudeSpectrum(whiteNoise, 512, workR, workI, mags)
        val noiseFlatness = fft.computeVoiceBandFlatness(mags, 16_000)

        assertTrue("Expected low flatness for tonal speech (<0.25), got $tonalFlatness", tonalFlatness < 0.25f)
        assertTrue("Expected high flatness for white noise (>0.45), got $noiseFlatness", noiseFlatness > 0.45f)
        assertTrue("Expected noise flatness > tonal flatness", noiseFlatness > tonalFlatness * 2f)
    }

    @Test
    fun energyVad_prominentOnlyRejectsBroadbandNoiseAndFlagsNoiseRejected() {
        val vad = EnergyVadDetector(initialSensitivity = VoiceSensitivity.PROMINENT_ONLY)
        val quietFloor = ShortArray(512) { idx -> if (idx % 2 == 0) 8 else -8 }
        repeat(20) { vad.processFrame(quietFloor) }

        // Loud broadband white noise (simulating fan/static at ~ -28 dBFS)
        val rng = java.util.Random(99L)
        val fanNoise = ShortArray(512) { ((rng.nextFloat() * 2f - 1f) * 2_200f).toInt().toShort() }
        val noiseRes = vad.processFrame(fanNoise)
        assertTrue(
            "Expected PROMINENT_ONLY to reject broadband fan noise (prob=${noiseRes.speechProbability}, flat=${noiseRes.spectralFlatness})",
            !noiseRes.isSpeech
        )
        assertTrue("Expected rejected fan noise to set isNoiseRejected=true", noiseRes.isNoiseRejected)

        // Prominent foreground tonal voice (~ -16 dBFS)
        val loudVoice = ShortArray(512) { i ->
            (sin(2.0 * PI * 700.0 * i / 16_000.0) * 7_500.0 +
                sin(2.0 * PI * 1_400.0 * i / 16_000.0) * 3_500.0).toInt().toShort()
        }
        val voiceRes = vad.processFrame(loudVoice)
        assertTrue(
            "Expected loud foreground tonal voice to trigger PROMINENT_ONLY (prob=${voiceRes.speechProbability})",
            voiceRes.isSpeech
        )
    }

    @Test
    fun twoPassProminentVoiceCleaner_keepsCloseVoiceAndCutsDistantBackgroundVoiceAndNoise() {
        val rng = java.util.Random(7L)
        // Build synthetic 6-second recording:
        // 0.0-1.0s: steady room noise floor (~ -52 dBFS)
        // 1.0-2.2s: PROMINENT close speaker (~ -12 dBFS, tonal 500+1000 Hz)
        // 2.2-4.6s: DISTANT quiet background voice (~ -34 dBFS, 22 dB quieter than main speaker) + steady room noise
        // 4.6-5.8s: PROMINENT close speaker again (~ -12 dBFS)
        val totalFrames = 188 // ~6.0s
        val frames = Array(totalFrames) { frameIdx ->
            ShortArray(512) { i ->
                val globalI = frameIdx * 512 + i
                val roomNoise = (rng.nextFloat() * 2f - 1f) * 90f
                val signal = when {
                    frameIdx in 32..69 || frameIdx in 144..181 -> {
                        // Prominent close speaker
                        (sin(2.0 * PI * 500.0 * globalI / 16_000.0) * 8_000.0 +
                            sin(2.0 * PI * 1_000.0 * globalI / 16_000.0) * 4_000.0).toFloat()
                    }
                    frameIdx in 70..143 -> {
                        // Distant quieter background chatter (22 dB quieter)
                        (sin(2.0 * PI * 650.0 * globalI / 16_000.0) * 650.0).toFloat()
                    }
                    else -> 0f
                }
                (signal + roomNoise).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        // Pass 1: Acoustic profiling
        val profiler = com.example.audio.AudioAcousticProfiler(sampleRate = 16_000)
        for (f in frames) profiler.processPcm(f)
        val profile = profiler.finish()

        assertTrue(
            "Expected prominent peak (${profile.prominentPeakDb}) well above noise floor (${profile.noiseFloorDb})",
            profile.prominentPeakDb > profile.noiseFloorDb + 15f
        )

        // Pass 2: Prominent Only cleanup (-10 dB cutoff, steady noise reduction ON, 0.5s pause)
        val cleanedSamples = mutableListOf<Short>()
        val config = com.example.model.VoiceCleanConfig(
            focusPreset = VoiceSensitivity.PROMINENT_ONLY,
            prominenceOffsetDb = -10f,
            reduceSteadyNoise = true,
            keepPauseMillis = 500L
        )
        val cleaner = com.example.audio.ProminentVoiceCleaner(
            profile = profile,
            config = config,
            sampleRate = 16_000
        ) { buf, off, len ->
            for (k in off until off + len) cleanedSamples.add(buf[k])
        }
        for (f in frames) cleaner.processPcm(f)
        cleaner.finish()

        val inputMs = cleaner.inputDurationMillis
        val keptMs = cleaner.keptDurationMillis
        assertTrue("Expected input ~6000 ms, got $inputMs", inputMs > 5_800L)
        assertTrue("Expected both prominent speaker segments preserved (>= 2200 ms), got $keptMs", keptMs >= 2_200L)
        assertTrue(
            "Expected distant voice + room noise trimmed so keptMs ($keptMs) is well below 4500 ms",
            keptMs < 4_500L
        )
        assertEquals(cleaner.totalKeptSamples.toInt(), cleanedSamples.size)
    }

    @Test
    fun themeSortAndEstimatedHoursHelpers_workCleanly() {
        assertEquals(com.example.model.AppThemeMode.SYSTEM, com.example.model.AppThemeMode.fromId(null))
        assertEquals(com.example.model.AppThemeMode.LIGHT, com.example.model.AppThemeMode.SYSTEM.next())
        assertEquals(com.example.model.AppThemeMode.DARK, com.example.model.AppThemeMode.LIGHT.next())
        assertEquals(com.example.model.AppThemeMode.SYSTEM, com.example.model.AppThemeMode.DARK.next())

        assertEquals(com.example.model.RecordingSortOrder.NEWEST, com.example.model.RecordingSortOrder.fromId(null))
        assertEquals(com.example.model.RecordingSortOrder.LONGEST, com.example.model.RecordingSortOrder.fromId("longest"))

        // Under 100 MB safety reserve -> "< 1m"
        assertEquals("< 1m", com.example.util.FormatUtils.formatEstimatedHours(50L * 1024L * 1024L, 11L * 1024L * 1024L))
        // 1.1 GB available minus 100 MB reserve ~= 1 GB @ 15 MB/hr -> ~68h
        val hoursLabel = com.example.util.FormatUtils.formatEstimatedHours(
            availableBytes = 1_124L * 1024L * 1024L,
            bytesPerHour = AudioFormatOption.OGG_OPUS.estimatedBytesPerHour
        )
        assertTrue("Expected estimated hours string starting with '~' and containing 'h', got $hoursLabel", hoursLabel.startsWith("~") && hoursLabel.contains("h"))
    }

    @Test
    fun pcmResampler_decimates48kHzOpusOutputToExact16kHzMonoAcrossArbitraryChunks() {
        val resampler = PcmResampler(
            inputSampleRate = 48_000,
            inputChannels = 1,
            targetSampleRate = 16_000
        )

        // Generate 1.0 second (48,000 samples) of 440 Hz tone at 48 kHz
        val totalIn = 48_000
        val input = ShortArray(totalIn) { i ->
            (sin(2.0 * PI * 440.0 * i / 48_000.0) * 12_000.0).toInt().toShort()
        }

        // Feed in irregular chunk sizes (e.g. 960, 1, 7, 511, 2048...) to verify zero sample drift
        val output = mutableListOf<Short>()
        val chunkSizes = intArrayOf(960, 1, 7, 511, 13, 2048, 997)
        var offset = 0
        var sizeIdx = 0
        while (offset < totalIn) {
            val len = minOf(chunkSizes[sizeIdx % chunkSizes.size], totalIn - offset)
            val slice = input.copyOfRange(offset, offset + len)
            resampler.process(slice, len) { outBuf, outLen ->
                for (k in 0 until outLen) output.add(outBuf[k])
            }
            offset += len
            sizeIdx++
        }

        assertEquals("Expected exact 16,000 samples (1.0s @ 16kHz), got ${output.size}", 16_000, output.size)
        val peak = output.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("Expected preserved tone amplitude (> 9000), got $peak", peak > 9_000)

        // Also test stereo 44.1 kHz -> mono 16 kHz resampling
        val stereoResampler = PcmResampler(
            inputSampleRate = 44_100,
            inputChannels = 2,
            targetSampleRate = 16_000
        )
        val stereoFrames = 44_100
        val stereoIn = ShortArray(stereoFrames * 2) { idx ->
            val f = idx / 2
            (sin(2.0 * PI * 500.0 * f / 44_100.0) * 10_000.0).toInt().toShort()
        }
        var resampledCount = 0
        stereoResampler.process(stereoIn, stereoIn.size) { _, len -> resampledCount += len }
        assertTrue(
            "Expected ~16,000 samples from 1s stereo 44.1kHz -> 16kHz, got $resampledCount",
            resampledCount in 15_950..16_050
        )
    }

    @Test
    fun streamingOggAndAdtsRepair_truncatesCorruptedTailWithoutReadingWholeFileIntoRam() {
        val dir = createTempDirectory("clearvox_repair_test").toFile()
        try {
            // 1. Build 2 valid Ogg pages (granule 48,000 = 1,000 ms @ 48 kHz) + 1 truncated trailing page
            fun makeOggPage(granulePos: Long, bodySize: Int): ByteArray {
                val page = ByteArray(27 + 1 + bodySize)
                page[0] = 'O'.code.toByte()
                page[1] = 'g'.code.toByte()
                page[2] = 'g'.code.toByte()
                page[3] = 'S'.code.toByte()
                ByteBuffer.wrap(page, 6, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(granulePos)
                page[26] = 1
                page[27] = bodySize.toByte()
                return page
            }

            val page1 = makeOggPage(granulePos = 24_000L, bodySize = 32)
            val page2 = makeOggPage(granulePos = 96_000L, bodySize = 48)
            val validLen = (page1.size + page2.size).toLong()
            val corruptedTail = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), 0, 1, 2, 3)

            val oggFile = File(dir, "partial_crash.ogg")
            oggFile.writeBytes(page1 + page2 + corruptedTail)

            val oggDurationMs = AudioRemuxer.repairOggStreamIfNeeded(oggFile)
            assertEquals("Expected partial tail truncated to exact valid page boundary", validLen, oggFile.length())
            assertEquals("Expected 2000ms for granule 96,000 @ 48kHz", 2_000L, oggDurationMs)

            // 2. Build 2 valid ADTS AAC frames + partial garbage tail
            fun makeAdtsFrame(frameLen: Int): ByteArray {
                val frame = ByteArray(frameLen)
                frame[0] = 0xFF.toByte()
                frame[1] = 0xF9.toByte()
                frame[2] = 0x60.toByte() // AAC-LC, 16kHz (idx 8)
                frame[3] = ((1 shl 6) or ((frameLen shr 11) and 0x03)).toByte()
                frame[4] = ((frameLen shr 3) and 0xFF).toByte()
                frame[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
                frame[6] = 0xFC.toByte()
                return frame
            }

            val f1 = makeAdtsFrame(42)
            val f2 = makeAdtsFrame(55)
            val adtsValidLen = (f1.size + f2.size).toLong()
            val partialAdts = byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x60.toByte(), 0x40, 0x05)

            val aacFile = File(dir, "partial_crash.aac")
            aacFile.writeBytes(f1 + f2 + partialAdts)

            val aacDurationMs = AacAdtsChunkWriter.repairAdtsTailIfNeeded(aacFile)
            assertEquals("Expected partial ADTS tail truncated", adtsValidLen, aacFile.length())
            assertEquals("Expected 128ms for 2 ADTS frames", 128L, aacDurationMs)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun asyncChunkDiskWriter_handlesBackpressureAndShutdownCleanly() {
        val dir = createTempDirectory("clearvox_writer_test").toFile()
        val finalized = mutableListOf<com.example.model.AudioChunk>()
        try {
            val writer = com.example.audio.AsyncChunkDiskWriter(
                sampleRate = 16_000,
                channels = 1,
                onChunkFinalized = { chunk -> synchronized(finalized) { finalized.add(chunk) } }
            )
            writer.start()

            val frame = ShortArray(512) { i -> ((i % 32) * 100).toShort() }
            writer.enqueueOpenChunk(
                directory = dir,
                chunkIndex = 1,
                format = AudioFormatOption.WAV,
                startTimeMillis = 1_700_000_000_000L,
                preRollSamples = ShortArray(0),
                firstFrame = frame,
                firstFrameLength = frame.size
            )
            repeat(20) {
                writer.enqueueWriteFrame(frame, frame.size)
            }
            writer.enqueueFinalizeChunk()
            writer.drainAndShutdown(timeoutMs = 5_000L)

            assertEquals("Expected 1 cleanly finalized WAV chunk", 1, finalized.size)
            assertTrue("Expected positive duration", finalized[0].durationMillis > 0L)
            assertTrue("Expected finalized file on disk", File(dir, finalized[0].fileName).length() > 44L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storageSummaryAndPublicExportDestination_computeTotalsAndRoundTrip() {
        assertEquals(
            com.example.model.PublicExportDestination.DOWNLOADS,
            com.example.model.PublicExportDestination.fromId(null)
        )
        assertEquals(
            com.example.model.PublicExportDestination.MUSIC,
            com.example.model.PublicExportDestination.fromId("music")
        )
        assertEquals(
            com.example.model.PublicExportDestination.DOWNLOADS,
            com.example.model.PublicExportDestination.fromId("downloads")
        )

        val summary = com.example.model.StorageSummary(
            recordingsBytes = 12_000_000L,
            trashedBytes = 3_500_000L,
            cacheBytes = 500_000L,
            availableDeviceBytes = 5_000_000_000L,
            totalDeviceBytes = 64_000_000_000L,
            sessionCount = 4,
            trashedCount = 2,
            totalClipCount = 18
        )
        assertEquals(15_500_000L, summary.totalRecordingsBytes)
        assertEquals(16_000_000L, summary.totalAppBytes)
        assertTrue(summary.hasTrashedItems)

        val emptyTrashSummary = summary.copy(trashedBytes = 0L, trashedCount = 0)
        assertTrue(!emptyTrashSummary.hasTrashedItems)
        assertEquals(12_500_000L, emptyTrashSummary.totalAppBytes)
    }
}
