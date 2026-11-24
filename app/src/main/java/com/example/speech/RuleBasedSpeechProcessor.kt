package com.example.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.model.AudioChunk
import com.example.model.RecordingSession
import com.example.model.SessionNotes
import com.example.storage.AudioRemuxer
import com.example.storage.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 100% offline, rule-based audio transcription and extractive summarization engine.
 * Zero internet access, zero cloud calls, zero AI models.
 *
 * Transcription strategy:
 * 1. On Android 13+ (API 33+) when an offline on-device SpeechRecognizer is installed, streams
 *    decoded 16 kHz mono PCM from each chunk directly into Android's offline SpeechRecognizer via
 *    ParcelFileDescriptor pipe (`EXTRA_PREFER_OFFLINE = true`).
 * 2. On devices/emulators without an offline speech pack, runs a deterministic rule-based acoustic
 *    cadence & speech-burst analyzer over the real recorded waveform to produce structured
 *    timestamped spoken-segment entries that users can immediately inspect, dictate into offline,
 *    or edit.
 *
 * Summarization strategy ([RuleBasedSummarizer]):
 * Deterministic extractive NLP using stop-word filtering, Term Frequency (TF) scoring, sentence
 * position weighting, and rule-based action/decision cue matching.
 */
class RuleBasedSpeechProcessor(
    private val context: Context,
    private val sessionRepository: SessionRepository
) {
    private val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())

    suspend fun transcribeAndSummarize(
        session: RecordingSession,
        onStageChanged: (String) -> Unit = {}
    ): SessionNotes = withContext(Dispatchers.IO) {
        onStageChanged("transcribing")
        val transcriptLines = mutableListOf<String>()

        for (chunk in session.chunks) {
            val file = sessionRepository.getChunkFile(session, chunk)
            if (!file.exists() || file.length() == 0L) continue

            val timestamp = timeFormat.format(Date(chunk.startTimeMillis))
            val pcm = decodeFileToPcm16Mono(file)

            // Attempt offline Android SpeechRecognizer on API 33+ if available on device
            val recognizedText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && pcm.isNotEmpty()) {
                tryOfflineSpeechRecognizerOnPcm(pcm)
            } else {
                null
            }

            if (!recognizedText.isNullOrBlank()) {
                val cleaned = ensureSentencePunctuation(recognizedText.trim())
                transcriptLines.add("[$timestamp] $cleaned")
            } else {
                val acousticSummary = analyzeChunkAcousticsRuleBased(chunk, pcm)
                transcriptLines.add("[$timestamp] $acousticSummary")
            }
        }

        val fullTranscript = if (transcriptLines.isEmpty()) {
            "No spoken clips were found in this recording session."
        } else {
            transcriptLines.joinToString("\n\n")
        }

        onStageChanged("summarizing")
        RuleBasedSummarizer.summarize(
            transcript = fullTranscript,
            session = session
        )
    }

    fun summarizeEditedTranscript(session: RecordingSession, transcript: String): SessionNotes {
        return RuleBasedSummarizer.summarize(
            transcript = transcript.trim(),
            session = session
        )
    }

    /**
     * Offline-only Android SpeechRecognizer using ParcelFileDescriptor audio source (API 33+).
     * Uses `EXTRA_PREFER_OFFLINE = true` and `createOnDeviceSpeechRecognizer` so it never uses internet.
     */
    private suspend fun tryOfflineSpeechRecognizerOnPcm(pcm: ShortArray): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null

        // Limit single pipe recognition chunk to ~60s (16kHz * 60 = 960k samples) for reliability
        val sampleCount = min(pcm.size, 16_000 * 60)
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val readFd = pipe[0]
        val writeFd = pipe[1]

        // Stream 16-bit LE PCM bytes into pipe on background thread
        val writerThread = Thread {
            try {
                FileOutputStream(writeFd.fileDescriptor).use { fos ->
                    val byteBuf = ByteArray(4096)
                    var idx = 0
                    while (idx < sampleCount) {
                        val batch = min(2048, sampleCount - idx)
                        var b = 0
                        for (i in 0 until batch) {
                            val v = pcm[idx + i].toInt()
                            byteBuf[b++] = (v and 0xFF).toByte()
                            byteBuf[b++] = ((v shr 8) and 0xFF).toByte()
                        }
                        fos.write(byteBuf, 0, b)
                        idx += batch
                    }
                    fos.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "PCM pipe stream closed early", e)
            } finally {
                runCatching { writeFd.close() }
            }
        }
        writerThread.start()

        val result = withTimeoutOrNull(12_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    val recognizer = runCatching {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    }.getOrNull()

                    if (recognizer == null) {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val segments = mutableListOf<String>()
                    fun finish(text: String?) {
                        runCatching { recognizer.destroy() }
                        if (cont.isActive) cont.resume(text)
                    }

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onError(error: Int) {
                            finish(segments.joinToString(" ").takeIf { it.isNotBlank() })
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val best = matches?.firstOrNull()?.trim()
                            if (!best.isNullOrBlank()) segments.add(best)
                            finish(segments.joinToString(" ").takeIf { it.isNotBlank() })
                        }

                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                    }

                    cont.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post {
                            runCatching { recognizer.cancel() }
                            runCatching { recognizer.destroy() }
                        }
                    }

                    try {
                        recognizer.startListening(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Offline SpeechRecognizer start failed", e)
                        finish(null)
                    }
                }
            }
        }

        runCatching { readFd.close() }
        runCatching { writerThread.join(500L) }
        return result
    }

    /**
     * Pure rule-based acoustic phrase & voice-activity descriptor computed directly from the
     * recorded waveform when offline OS speech recognition pack is unavailable.
     */
    private fun analyzeChunkAcousticsRuleBased(chunk: AudioChunk, pcm: ShortArray): String {
        val durationSec = (chunk.durationMillis / 1000L).coerceAtLeast(1L)
        if (pcm.isEmpty()) {
            return "Spoken clip #${chunk.index} (${formatCompactSeconds(durationSec)}). Tap Edit Transcript or Dictate to type or speak notes."
        }

        val frameSize = 512 // 32 ms at 16 kHz
        val frameCount = pcm.size / frameSize
        if (frameCount == 0) {
            return "Brief voice activity captured (${formatCompactSeconds(durationSec)})."
        }

        var activeFrames = 0
        var phraseBursts = 0
        var inBurst = false
        var silentRun = 0
        var peakRmsDb = -90f
        var activeRmsSum = 0.0

        for (f in 0 until frameCount) {
            var sumSq = 0.0
            val base = f * frameSize
            for (i in 0 until frameSize) {
                val s = pcm[base + i] / 32768.0
                sumSq += s * s
            }
            val rms = sqrt(sumSq / frameSize).toFloat()
            val db = if (rms <= 1e-6f) -90f else (20.0 * ln(rms.toDouble()) / LN10).toFloat()
            if (db > peakRmsDb) peakRmsDb = db

            if (db > -44f) {
                activeFrames++
                activeRmsSum += db.toDouble()
                silentRun = 0
                if (!inBurst) {
                    inBurst = true
                    phraseBursts++
                }
            } else {
                silentRun++
                if (inBurst && silentRun >= 10) { // ~320 ms pause splits phrases
                    inBurst = false
                }
            }
        }

        val activePercent = ((activeFrames * 100f) / frameCount).roundToInt().coerceIn(1, 100)
        val avgActiveDb = if (activeFrames > 0) (activeRmsSum / activeFrames).toFloat() else peakRmsDb
        val volumeLabel = when {
            avgActiveDb >= -20f -> "Clear elevated voice"
            avgActiveDb >= -32f -> "Steady conversational voice"
            else -> "Soft-spoken voice"
        }
        val burstsText = if (phraseBursts <= 1) "1 continuous phrase" else "$phraseBursts spoken phrases"

        val header = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            "Acoustic Activity Log (clip #${chunk.index}, offline voice recognition requires Android 13+)"
        } else {
            "Acoustic Activity Log (clip #${chunk.index})"
        }
        return "$header: $volumeLabel (${formatCompactSeconds(durationSec)}, $burstsText, $activePercent% voice activity). Tap Dictate or Edit Notes below to add word-for-word text."
    }

    private fun decodeFileToPcm16Mono(file: File): ShortArray {
        val maxSamples = 16_000 * 90 // Cap at 90s @ 16 kHz mono for responsive analysis
        val collected = ArrayList<Short>(16_000 * 10)
        val ok = AudioRemuxer.decodeFileIntoConsumer(file, targetSampleRate = 16_000) { samples, length ->
            val remaining = maxSamples - collected.size
            if (remaining > 0) {
                val toCopy = min(length, remaining)
                for (i in 0 until toCopy) {
                    collected.add(samples[i])
                }
            }
        }
        return if (ok) collected.toShortArray() else ShortArray(0)
    }

    private fun ensureSentencePunctuation(text: String): String {
        val last = text.lastOrNull() ?: return text
        return if (last == '.' || last == '!' || last == '?') text else "$text."
    }

    private fun formatCompactSeconds(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    companion object {
        private const val TAG = "RuleBasedSpeech"
        private val LN10 = ln(10.0)
    }
}

/**
 * Pure deterministic, rule-based extractive text summarizer (zero AI, zero internet).
 *
 * Algorithm:
 * 1. Sentence segmentation & timestamp cleanup.
 * 2. Stop-word removal & word frequency (TF) scoring.
 * 3. Positional weighting (opening + closing context) and action/cue phrase boosts
 *    ("important", "action", "need", "will", "remember", "deadline", "schedule", numbers/times).
 * 4. Chronological extraction of top-ranked sentences into a concise summary, key takeaways,
 *    and top keywords.
 */
object RuleBasedSummarizer {

    private val STOP_WORDS = setOf(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any",
        "are", "as", "at", "be", "because", "been", "before", "being", "below", "between",
        "both", "but", "by", "can", "could", "did", "do", "does", "doing", "down", "during",
        "each", "few", "for", "from", "further", "had", "has", "have", "having", "he", "her",
        "here", "hers", "herself", "him", "himself", "his", "how", "i", "if", "in", "into",
        "is", "it", "its", "itself", "just", "like", "me", "more", "most", "my", "myself",
        "no", "nor", "not", "now", "of", "off", "on", "once", "only", "or", "other", "our",
        "ours", "ourselves", "out", "over", "own", "really", "same", "she", "should", "so",
        "some", "such", "than", "that", "the", "their", "theirs", "them", "themselves", "then",
        "there", "these", "they", "this", "those", "through", "to", "too", "uh", "um", "under",
        "until", "up", "very", "was", "we", "were", "what", "when", "where", "which", "while",
        "who", "whom", "why", "with", "would", "yeah", "yes", "you", "your", "yours", "tap",
        "edit", "transcript", "dictate", "add", "exact", "wording", "clip", "detected"
    )

    private val ACTION_CUES = listOf(
        "important", "remember", "action", "decision", "decided", "need to", "have to",
        "will ", "must ", "deadline", "schedule", "scheduled", "meeting", "follow up",
        "tomorrow", "today", "next week", "call ", "email ", "send ", "buy ", "finish",
        "complete", "confirm", "note ", "todo", "reminder", "plan", "goal", "priority",
        "budget", "project", "update", "review", "issue", "problem", "solution", "agree"
    )

    private val TIMESTAMP_PREFIX = Regex("^\\[[0-9:]+\\s?(?:AM|PM|am|pm)?]\\s*")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+|\\n+")
    private val WORD_REGEX = Regex("[a-zA-Z0-9']+")

    data class ScoredSentence(
        val index: Int,
        val text: String,
        val score: Double,
        val isActionable: Boolean
    )

    fun summarize(transcript: String, session: RecordingSession): SessionNotes {
        val rawSentences = transcript
            .split(SENTENCE_SPLIT)
            .map { it.replace(TIMESTAMP_PREFIX, "").trim() }
            .filter { it.length >= 12 }
            .distinct()

        if (rawSentences.isEmpty()) {
            val fallbackSummary = buildSessionStatsLine(session)
            return SessionNotes(
                transcript = transcript,
                summary = fallbackSummary,
                highlights = listOf(fallbackSummary),
                keywords = emptyList(),
                generatedAtMillis = System.currentTimeMillis()
            )
        }

        // 1. Compute global term frequency excluding stop words
        val wordFreq = mutableMapOf<String, Int>()
        for (sentence in rawSentences) {
            for (token in tokenize(sentence)) {
                if (token.length >= 3 && token !in STOP_WORDS) {
                    wordFreq[token] = (wordFreq[token] ?: 0) + 1
                }
            }
        }
        val maxFreq = (wordFreq.values.maxOrNull() ?: 1).toDouble()

        // 2. Score each sentence deterministically
        val scored = rawSentences.mapIndexed { idx, sentence ->
            val lower = sentence.lowercase(Locale.US)
            val tokens = tokenize(sentence).filter { it.length >= 3 && it !in STOP_WORDS }

            val tfSum = tokens.sumOf { (wordFreq[it] ?: 0).toDouble() / maxFreq }
            val tfScore = if (tokens.isNotEmpty()) tfSum / sqrt(tokens.size.toDouble()) else 0.0

            val positionBonus = when (idx) {
                0 -> 0.45
                rawSentences.lastIndex -> 0.25
                else -> 0.0
            }

            val cueMatches = ACTION_CUES.count { lower.contains(it) }
            val hasNumbers = sentence.any { it.isDigit() }
            val cueBonus = cueMatches * 0.6 + if (hasNumbers) 0.2 else 0.0

            // Ideal concise length bonus (6 to 28 words)
            val wordCount = sentence.split(Regex("\\s+")).size
            val lengthBonus = if (wordCount in 6..28) 0.2 else 0.0

            ScoredSentence(
                index = idx,
                text = sentence,
                score = tfScore + positionBonus + cueBonus + lengthBonus,
                isActionable = cueMatches > 0
            )
        }

        // 3. Pick top 2..3 sentences in chronological order for Concise Summary
        val targetCount = when {
            scored.size <= 2 -> scored.size
            scored.size <= 6 -> 2
            else -> 3
        }
        val summarySentences = scored
            .sortedByDescending { it.score }
            .take(targetCount)
            .sortedBy { it.index }
            .map { it.text }

        val statsLead = buildSessionStatsLine(session)
        val summaryText = if (summarySentences.isEmpty()) {
            statsLead
        } else {
            summarySentences.joinToString(" ")
        }

        // 4. Key Takeaways: prioritize actionable sentences first, then highest-scored sentences
        val highlights = scored
            .sortedWith(compareByDescending<ScoredSentence> { it.isActionable }.thenByDescending { it.score })
            .take(4)
            .sortedBy { it.index }
            .map { it.text }
            .ifEmpty { listOf(statsLead) }

        // 5. Top Keywords by deterministic frequency
        val keywords = wordFreq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(6)
            .map { it.key.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) } }

        return SessionNotes(
            transcript = transcript,
            summary = summaryText,
            highlights = highlights,
            keywords = keywords,
            generatedAtMillis = System.currentTimeMillis()
        )
    }

    private fun tokenize(text: String): List<String> =
        WORD_REGEX.findAll(text.lowercase(Locale.US)).map { it.value }.toList()

    private fun buildSessionStatsLine(session: RecordingSession): String {
        val totalSecs = (session.totalSpeechMillis / 1000L).coerceAtLeast(0L)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        val durationLabel = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        val clipWord = if (session.clipCount == 1) "clip" else "clips"
        return "Captured $durationLabel of speech across ${session.clipCount} spoken $clipWord."
    }
}
