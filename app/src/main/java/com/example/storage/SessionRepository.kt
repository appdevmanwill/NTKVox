package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.audio.AudioAcousticProfiler
import com.example.audio.ProminentVoiceCleaner
import com.example.model.AudioChunk
import com.example.model.AudioFormatOption
import com.example.model.RecordingCategory
import com.example.model.RecordingMode
import com.example.model.RecordingSession
import com.example.model.SessionBookmark
import com.example.model.SessionNotes
import com.example.model.SessionStatus
import com.example.model.SilenceRemovalResult
import com.example.model.StorageSummary
import com.example.model.VoiceCleanConfig
import com.example.model.VoiceSensitivity
import com.example.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Local crash-safe session repository storing each session in its own folder under
 * `filesDir/vox_sessions/session_<id>/` with an atomically written `manifest.json`.
 *
 * Supports multi-format crash recovery (WAV, AAC/M4A, Ogg Opus, FLAC, MP3), 30-day Recently
 * Deleted trash bin, bookmarks, favorites, categories, storage analytics, `.txt` note exports,
 * and non-destructive Trim / Split audio editing.
 */
class SessionRepository(private val context: Context) {

    private val sessionsRoot: File = File(context.filesDir, "vox_sessions").apply { mkdirs() }
    private val exportCacheDir: File = File(context.cacheDir, "vox_exports").apply { mkdirs() }

    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    private val lock = Any()

    fun getSessionDirectory(folderName: String): File = File(sessionsRoot, folderName)

    fun getChunkFile(session: RecordingSession, chunk: AudioChunk): File =
        File(getSessionDirectory(session.folderName), chunk.fileName)

    /**
     * Silent, idempotent crash recovery executed on app launch.
     * - Repairs incomplete WAV headers, AAC ADTS tails, Ogg page boundaries, validates FLAC/MP3.
     * - Promotes interrupted ACTIVE sessions to READY cleanly.
     * - Auto-purges trashed sessions older than 30 days.
     */
    suspend fun recoverAndLoadSessions() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val dirs = sessionsRoot.listFiles { file -> file.isDirectory } ?: emptyArray()
            val loaded = mutableListOf<RecordingSession>()

            for (dir in dirs) {
                val manifestFile = File(dir, MANIFEST_NAME)
                if (!manifestFile.exists()) continue

                val parsed = runCatching { readManifest(manifestFile) }.getOrNull() ?: continue

                // Auto-purge trashed sessions older than 30 days
                val deletedAt = parsed.deletedAtMillis
                if (deletedAt != null && (now - deletedAt) >= TRASH_RETENTION_MILLIS) {
                    runCatching { dir.deleteRecursively() }
                    continue
                }

                val session = if (parsed.status == SessionStatus.ACTIVE) {
                    recoverInterruptedSession(dir, parsed)
                } else {
                    parsed
                }
                if (session != null) {
                    loaded.add(session)
                }
            }

            _sessions.value = loaded.sortedByDescending { it.startTimeMillis }
        }
    }

    private fun recoverInterruptedSession(dir: File, activeSession: RecordingSession): RecordingSession? {
        val knownChunkNames = activeSession.chunks.map { it.fileName }.toMutableSet()
        val recoveredChunks = mutableListOf<AudioChunk>()

        for (chunk in activeSession.chunks) {
            val file = File(dir, chunk.fileName)
            val repaired = repairSingleChunk(file, chunk, activeSession.format)
            if (repaired != null) {
                recoveredChunks.add(repaired)
            }
        }

        val extraFiles = dir.listFiles { f ->
            f.isFile && f.name.startsWith("part_") && f.name !in knownChunkNames
        }?.sortedBy { it.name } ?: emptyList()

        for (extra in extraFiles) {
            var targetFile = extra
            var ext = targetFile.extension.lowercase(Locale.US)
            val durationMs = repairOrMeasureFile(targetFile, ext)
            if (durationMs > 0L && targetFile.length() > 0L) {
                // If an interrupted M4A recording left a raw ADTS .aac file, losslessly wrap it to .m4a
                if (activeSession.format == AudioFormatOption.M4A_AAC && ext == "aac") {
                    val m4aFile = File(dir, "${targetFile.nameWithoutExtension}.m4a")
                    if (AudioRemuxer.remuxAdtsToM4a(listOf(targetFile), m4aFile)) {
                        runCatching { targetFile.delete() }
                        targetFile = m4aFile
                        ext = "m4a"
                    }
                }
                val nextIndex = (recoveredChunks.maxOfOrNull { it.index } ?: 0) + 1
                recoveredChunks.add(
                    AudioChunk(
                        index = nextIndex,
                        fileName = targetFile.name,
                        startTimeMillis = (targetFile.lastModified() - durationMs)
                            .coerceAtLeast(activeSession.startTimeMillis),
                        durationMillis = durationMs,
                        sizeBytes = targetFile.length(),
                        actualExtension = ext
                    )
                )
            } else {
                runCatching { extra.delete() }
            }
        }

        if (recoveredChunks.isEmpty()) {
            runCatching { dir.deleteRecursively() }
            return null
        }

        val lastChunkEnd = recoveredChunks.maxOf { it.startTimeMillis + it.durationMillis }
        val finalized = activeSession.copy(
            status = SessionStatus.READY,
            endTimeMillis = maxOf(activeSession.endTimeMillis, lastChunkEnd),
            chunks = recoveredChunks.sortedBy { it.index }
        )
        writeManifestAtomic(dir, finalized)
        return finalized
    }

    private fun repairSingleChunk(
        file: File,
        chunk: AudioChunk,
        format: AudioFormatOption
    ): AudioChunk? {
        if (!file.exists() || file.length() == 0L) {
            runCatching { file.delete() }
            return null
        }
        var currentFile = file
        var ext = currentFile.extension.lowercase(Locale.US)
        val repairedDuration = repairOrMeasureFile(currentFile, ext)
        if (repairedDuration <= 0L && chunk.durationMillis <= 0L) {
            runCatching { currentFile.delete() }
            return null
        }
        if (format == AudioFormatOption.M4A_AAC && ext == "aac") {
            val m4aFile = File(currentFile.parentFile, "${currentFile.nameWithoutExtension}.m4a")
            if (AudioRemuxer.remuxAdtsToM4a(listOf(currentFile), m4aFile)) {
                runCatching { currentFile.delete() }
                currentFile = m4aFile
                ext = "m4a"
            }
        }
        return chunk.copy(
            fileName = currentFile.name,
            durationMillis = if (repairedDuration > 0L) repairedDuration else chunk.durationMillis,
            sizeBytes = currentFile.length(),
            actualExtension = ext
        )
    }

    private fun repairOrMeasureFile(file: File, ext: String): Long {
        return when (ext) {
            "wav" -> WavChunkWriter.repairWavHeaderIfNeeded(file)
            "aac" -> AacAdtsChunkWriter.repairAdtsTailIfNeeded(file)
            "ogg" -> AudioRemuxer.repairOggStreamIfNeeded(file)
            "flac" -> {
                if (!AudioRemuxer.isValidFlacHeader(file)) 0L else measureWithRetriever(file)
            }
            else -> measureWithRetriever(file)
        }
    }

    private fun measureWithRetriever(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun createSession(
        format: AudioFormatOption,
        recordingMode: RecordingMode = RecordingMode.VOICE_ACTIVATED,
        startTimeMillis: Long = System.currentTimeMillis(),
        customTitle: String? = null
    ): RecordingSession {
        synchronized(lock) {
            val id = UUID.randomUUID().toString().take(8)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startTimeMillis))
            val folderName = "session_${stamp}_$id"
            val dir = File(sessionsRoot, folderName).apply { mkdirs() }
            val title = customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: buildDefaultTitle(startTimeMillis)
            val session = RecordingSession(
                id = id,
                title = title,
                folderName = folderName,
                startTimeMillis = startTimeMillis,
                endTimeMillis = startTimeMillis,
                format = format,
                recordingMode = recordingMode,
                status = SessionStatus.ACTIVE,
                chunks = emptyList(),
                bookmarks = emptyList(),
                isFavorite = false,
                category = RecordingCategory.GENERAL,
                deletedAtMillis = null,
                notes = null
            )
            writeManifestAtomic(dir, session)
            refreshListLocked(session)
            return session
        }
    }

    fun appendChunk(session: RecordingSession, chunk: AudioChunk): RecordingSession {
        synchronized(lock) {
            val latest = _sessions.value.firstOrNull { it.id == session.id } ?: session
            val dir = getSessionDirectory(latest.folderName)
            val updated = latest.copy(
                endTimeMillis = maxOf(latest.endTimeMillis, chunk.startTimeMillis + chunk.durationMillis),
                chunks = (latest.chunks.filterNot { it.index == chunk.index } + chunk).sortedBy { it.index }
            )
            writeManifestAtomic(dir, updated)
            refreshListLocked(updated)
            return updated
        }
    }

    fun addBookmark(
        sessionId: String,
        offsetFromSessionStartMillis: Long,
        label: String = "",
        timestampMillis: Long = System.currentTimeMillis()
    ): SessionBookmark? {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return null
            val nextNumber = existing.bookmarks.size + 1
            val finalLabel = label.trim().ifBlank { "Moment #$nextNumber" }
            val bookmark = SessionBookmark(
                id = UUID.randomUUID().toString().take(8),
                timestampMillis = timestampMillis,
                offsetFromSessionStartMillis = offsetFromSessionStartMillis.coerceAtLeast(0L),
                label = finalLabel
            )
            val updated = existing.copy(
                bookmarks = (existing.bookmarks + bookmark).sortedBy { it.offsetFromSessionStartMillis }
            )
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
            return bookmark
        }
    }

    suspend fun updateBookmarkLabel(
        sessionId: String,
        bookmarkId: String,
        newLabel: String
    ) = withContext(Dispatchers.IO) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty()) return@withContext
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(
                bookmarks = existing.bookmarks.map { bm ->
                    if (bm.id == bookmarkId) bm.copy(label = trimmed) else bm
                }
            )
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    suspend fun deleteBookmark(sessionId: String, bookmarkId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(
                bookmarks = existing.bookmarks.filterNot { it.id == bookmarkId }
            )
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    fun finalizeSession(session: RecordingSession, endTimeMillis: Long = System.currentTimeMillis()): RecordingSession? {
        synchronized(lock) {
            val latest = _sessions.value.firstOrNull { it.id == session.id } ?: session
            val dir = getSessionDirectory(latest.folderName)
            if (latest.chunks.isEmpty()) {
                runCatching { dir.deleteRecursively() }
                _sessions.value = _sessions.value.filterNot { it.id == latest.id }
                return null
            }
            val updated = latest.copy(
                endTimeMillis = maxOf(endTimeMillis, latest.endTimeMillis),
                status = SessionStatus.READY
            )
            writeManifestAtomic(dir, updated)
            refreshListLocked(updated)
            return updated
        }
    }

    suspend fun renameSession(sessionId: String, newTitle: String) = withContext(Dispatchers.IO) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@withContext
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(title = trimmed)
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    suspend fun toggleFavorite(sessionId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(isFavorite = !existing.isFavorite)
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    suspend fun updateCategory(sessionId: String, category: RecordingCategory) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(category = category)
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    suspend fun saveSessionNotes(sessionId: String, notes: SessionNotes) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(notes = notes)
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    /** Soft-deletes a session into the 30-day Recently Deleted trash bin. */
    suspend fun moveToTrash(sessionId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(deletedAtMillis = System.currentTimeMillis())
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    /** Restores a trashed session back to active recordings. */
    suspend fun restoreFromTrash(sessionId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            val updated = existing.copy(deletedAtMillis = null)
            writeManifestAtomic(getSessionDirectory(updated.folderName), updated)
            refreshListLocked(updated)
        }
    }

    /** Permanently deletes a single session and its folder from disk. */
    suspend fun deleteSessionPermanently(sessionId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val existing = _sessions.value.firstOrNull { it.id == sessionId } ?: return@withContext
            runCatching { getSessionDirectory(existing.folderName).deleteRecursively() }
            _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        }
    }

    /** Permanently deletes all sessions currently in Recently Deleted. */
    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val trashed = _sessions.value.filter { it.isTrashed }
            for (item in trashed) {
                runCatching { getSessionDirectory(item.folderName).deleteRecursively() }
            }
            _sessions.value = _sessions.value.filterNot { it.isTrashed }
        }
    }

    /** Backward-compatible alias for permanent deletion. */
    suspend fun deleteSession(sessionId: String) = deleteSessionPermanently(sessionId)

    suspend fun batchSetFavorite(sessionIds: Set<String>, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) return@withContext
        synchronized(lock) {
            val updatedList = _sessions.value.map { session ->
                if (session.id in sessionIds && session.isFavorite != favorite) {
                    val copy = session.copy(isFavorite = favorite)
                    writeManifestAtomic(getSessionDirectory(copy.folderName), copy)
                    copy
                } else {
                    session
                }
            }
            _sessions.value = updatedList.sortedByDescending { it.startTimeMillis }
        }
    }

    suspend fun batchUpdateCategory(sessionIds: Set<String>, category: RecordingCategory) = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) return@withContext
        synchronized(lock) {
            val updatedList = _sessions.value.map { session ->
                if (session.id in sessionIds && session.category != category) {
                    val copy = session.copy(category = category)
                    writeManifestAtomic(getSessionDirectory(copy.folderName), copy)
                    copy
                } else {
                    session
                }
            }
            _sessions.value = updatedList.sortedByDescending { it.startTimeMillis }
        }
    }

    suspend fun batchMoveToTrash(sessionIds: Set<String>) = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val updatedList = _sessions.value.map { session ->
                if (session.id in sessionIds && !session.isTrashed) {
                    val copy = session.copy(deletedAtMillis = now)
                    writeManifestAtomic(getSessionDirectory(copy.folderName), copy)
                    copy
                } else {
                    session
                }
            }
            _sessions.value = updatedList.sortedByDescending { it.startTimeMillis }
        }
    }

    /** Computes live storage breakdown for the Settings Storage Manager. */
    suspend fun computeStorageSummary(): StorageSummary = withContext(Dispatchers.IO) {
        val currentSessions = synchronized(lock) { _sessions.value }
        val active = currentSessions.filterNot { it.isTrashed }
        val trashed = currentSessions.filter { it.isTrashed }

        val activeBytes = active.sumOf { folderSizeBytes(getSessionDirectory(it.folderName)) }
        val trashedBytes = trashed.sumOf { folderSizeBytes(getSessionDirectory(it.folderName)) }
        val cacheBytes = folderSizeBytes(exportCacheDir)

        var availableBytes = 0L
        var totalBytes = 1L
        runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            availableBytes = stat.availableBytes
            totalBytes = stat.totalBytes.coerceAtLeast(1L)
        }

        StorageSummary(
            recordingsBytes = activeBytes,
            trashedBytes = trashedBytes,
            cacheBytes = cacheBytes,
            availableDeviceBytes = availableBytes,
            totalDeviceBytes = totalBytes,
            sessionCount = active.size,
            trashedCount = trashed.size,
            totalClipCount = active.sumOf { it.clipCount }
        )
    }

    /** Clears all temporary shared audio & `.txt` files from cacheDir. */
    suspend fun clearExportCache(): StorageSummary = withContext(Dispatchers.IO) {
        exportCacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        computeStorageSummary()
    }

    /**
     * Merges session chunks into a single universal shareable file in cache and returns a
     * FileProvider Uri + MIME type.
     */
    suspend fun exportSessionForShare(session: RecordingSession): Pair<Uri, String>? = withContext(Dispatchers.IO) {
        val (exportedFile, mimeType) = prepareMergedExportFile(session) ?: return@withContext null
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportedFile
        )
        uri to mimeType
    }

    /**
     * Saves a playable copy of [session] directly into the phone's `Music/NTK VOX/` folder using
     * zero-permission Android scoped [MediaStore.Audio.Media] (Android 10+ compliant).
     */
    suspend fun saveSessionToMusicFolder(session: RecordingSession): String? =
        saveSessionToPublicStorage(session, com.example.model.PublicExportDestination.MUSIC)

    /**
     * Saves a playable merged copy of [session] into device public storage (`Downloads/NTK VOX/`
     * or `Music/NTK VOX/`) via zero-permission Android 10+ scoped MediaStore (`IS_PENDING`).
     *
     * @return the saved display filename if successful, or `null` on failure.
     */
    suspend fun saveSessionToPublicStorage(
        session: RecordingSession,
        destination: com.example.model.PublicExportDestination
    ): String? = withContext(Dispatchers.IO) {
        val (sourceFile, mimeType) = prepareMergedExportFile(session) ?: return@withContext null
        val displayName = sourceFile.name
        val resolver = context.contentResolver

        val (collectionUri, values) = when (destination) {
            com.example.model.PublicExportDestination.MUSIC -> {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.TITLE, session.title)
                    put(MediaStore.Audio.Media.ARTIST, "NTK VOX")
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NTK VOX")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            com.example.model.PublicExportDestination.DOWNLOADS -> {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NTK VOX")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
        }

        val itemUri = resolver.insert(collectionUri, values) ?: return@withContext null

        return@withContext try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out, bufferSize = 16_384)
                }
            } ?: error("Could not open MediaStore output stream")
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, publishValues, null, null)
            // Remove temporary staging copy from exportCacheDir so disk usage stays clean
            runCatching { sourceFile.delete() }
            displayName
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving recording to ${destination.folderLabel}", e)
            runCatching { resolver.delete(itemUri, null, null) }
            null
        }
    }

    private fun prepareMergedExportFile(session: RecordingSession): Pair<File, String>? {
        val chunkFiles = session.chunks
            .map { getChunkFile(session, it) }
            .filter { it.exists() && it.length() > 0L }

        if (chunkFiles.isEmpty()) return null

        val safeBase = sanitizeFileName(session.title)
        exportCacheDir.mkdirs()

        val allWav = chunkFiles.all { it.extension.equals("wav", ignoreCase = true) }
        val allAac = chunkFiles.all { it.extension.equals("aac", ignoreCase = true) }
        val allMp3 = chunkFiles.all { it.extension.equals("mp3", ignoreCase = true) }

        val exportedFile: File
        val mimeType: String

        when {
            chunkFiles.size == 1 && !allAac -> {
                val single = chunkFiles.first()
                val ext = single.extension.lowercase(Locale.US).ifBlank { "wav" }
                exportedFile = File(exportCacheDir, "${safeBase}.$ext")
                single.copyTo(exportedFile, overwrite = true)
                mimeType = mimeForExtension(ext)
            }
            allAac -> {
                val m4aOut = File(exportCacheDir, "${safeBase}.m4a")
                if (AudioRemuxer.remuxAdtsToM4a(chunkFiles, m4aOut)) {
                    exportedFile = m4aOut
                    mimeType = "audio/mp4"
                } else {
                    exportedFile = File(exportCacheDir, "${safeBase}.aac")
                    concatenateRawFiles(chunkFiles, exportedFile)
                    mimeType = "audio/aac"
                }
            }
            allWav -> {
                exportedFile = File(exportCacheDir, "${safeBase}.wav")
                mergeWavFiles(chunkFiles, exportedFile)
                mimeType = "audio/wav"
            }
            allMp3 -> {
                exportedFile = File(exportCacheDir, "${safeBase}.mp3")
                concatenateRawFiles(chunkFiles, exportedFile)
                mimeType = "audio/mpeg"
            }
            else -> {
                val targetFormat = session.format
                val targetExt = targetFormat.extension
                val mergedOut = File(exportCacheDir, "${safeBase}.$targetExt")
                val mergeOk = mergeViaFormatWriter(chunkFiles, targetFormat, mergedOut)
                if (mergeOk) {
                    exportedFile = mergedOut
                    mimeType = targetFormat.mimeType
                } else {
                    val wavFallback = File(exportCacheDir, "${safeBase}.wav")
                    val wavWriter = WavChunkWriter()
                    if (AudioRemuxer.mergeEncodedChunksViaPcm(chunkFiles, wavFallback, wavWriter)) {
                        exportedFile = wavFallback
                        mimeType = "audio/wav"
                    } else {
                        val firstExt = chunkFiles.first().extension.lowercase(Locale.US).ifBlank { "ogg" }
                        exportedFile = File(exportCacheDir, "${safeBase}.$firstExt")
                        chunkFiles.first().copyTo(exportedFile, overwrite = true)
                        mimeType = mimeForExtension(firstExt)
                    }
                }
            }
        }
        return exportedFile to mimeType
    }

    /**
     * Exports a nicely formatted plain-text `.txt` document containing session metadata,
     * bookmarks, concise summary, key takeaways, keywords, and full transcript.
     */
    suspend fun exportNotesAsTxt(session: RecordingSession): Pair<Uri, String>? = withContext(Dispatchers.IO) {
        val safeBase = sanitizeFileName(session.title)
        exportCacheDir.mkdirs()
        val outFile = File(exportCacheDir, "${safeBase}_Notes.txt")

        val text = buildString {
            appendLine("NTK VOX — Recording Notes")
            appendLine("========================================")
            appendLine("Title:    ${session.title}")
            appendLine("Date:     ${FormatUtils.formatDateTimeShort(session.startTimeMillis)}")
            appendLine("Mode:     ${session.recordingMode.displayName}")
            appendLine("Category: ${session.category.displayName}")
            appendLine("Duration: ${FormatUtils.formatCompactDuration(session.totalSpeechMillis)}")
            appendLine("Format:   ${session.format.displayName}")
            appendLine()

            if (session.bookmarks.isNotEmpty()) {
                appendLine("BOOKMARKED MOMENTS")
                appendLine("----------------------------------------")
                session.bookmarks.forEach { bm ->
                    val offset = FormatUtils.formatHms(bm.offsetFromSessionStartMillis)
                    val clock = FormatUtils.formatWallClockTime(bm.timestampMillis)
                    appendLine("• [$offset | $clock] ${bm.label}")
                }
                appendLine()
            }

            val notes = session.notes
            if (notes != null) {
                if (notes.summary.isNotBlank()) {
                    appendLine("SUMMARY")
                    appendLine("----------------------------------------")
                    appendLine(notes.summary.trim())
                    appendLine()
                }
                if (notes.highlights.isNotEmpty()) {
                    appendLine("KEY TAKEAWAYS")
                    appendLine("----------------------------------------")
                    notes.highlights.forEach { item ->
                        appendLine("• ${item.trim()}")
                    }
                    appendLine()
                }
                if (notes.keywords.isNotEmpty()) {
                    appendLine("TOP KEYWORDS")
                    appendLine("----------------------------------------")
                    appendLine(notes.keywords.joinToString(", "))
                    appendLine()
                }
                if (notes.transcript.isNotBlank()) {
                    appendLine("FULL TRANSCRIPT")
                    appendLine("----------------------------------------")
                    appendLine(notes.transcript.trim())
                    appendLine()
                }
            } else {
                appendLine("No transcript or summary has been generated yet.")
            }
        }

        outFile.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        uri to "text/plain"
    }

    /**
     * Non-destructive Trim: decodes the session timeline, retains `[startMs, endMs]`, encodes in
     * the session's native format (including lossless ADTS->M4A wrap), and either creates a new
     * copy (default) or replaces the original session's audio.
     */
    suspend fun trimSession(
        session: RecordingSession,
        startMs: Long,
        endMs: Long,
        saveAsNewCopy: Boolean = true
    ): RecordingSession? = withContext(Dispatchers.IO) {
        val totalMs = session.totalSpeechMillis.coerceAtLeast(1L)
        val clampedStart = startMs.coerceIn(0L, totalMs)
        val clampedEnd = endMs.coerceIn(clampedStart + 500L, totalMs)
        if (clampedEnd <= clampedStart) return@withContext null

        val startSample = (clampedStart * SAMPLE_RATE) / 1000L
        val endSample = (clampedEnd * SAMPLE_RATE) / 1000L

        val now = System.currentTimeMillis()
        val targetId = if (saveAsNewCopy) UUID.randomUUID().toString().take(8) else session.id
        val targetStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val targetFolderName = if (saveAsNewCopy) "session_${targetStamp}_$targetId" else session.folderName
        val targetDir = File(sessionsRoot, targetFolderName).apply { mkdirs() }

        val workDir = if (saveAsNewCopy) targetDir else File(targetDir, "trim_tmp_$targetStamp").apply { mkdirs() }
        val chunk = writeSlicedSegment(
            sourceSession = session,
            destinationDir = workDir,
            startSample = startSample,
            endSample = endSample,
            chunkStartTimeMillis = session.startTimeMillis + clampedStart
        ) ?: run {
            if (saveAsNewCopy) runCatching { targetDir.deleteRecursively() }
            else runCatching { workDir.deleteRecursively() }
            return@withContext null
        }

        synchronized(lock) {
            val shiftedBookmarks = session.bookmarks
                .filter { it.offsetFromSessionStartMillis in clampedStart..clampedEnd }
                .map { it.copy(offsetFromSessionStartMillis = it.offsetFromSessionStartMillis - clampedStart) }

            if (saveAsNewCopy) {
                val copySession = RecordingSession(
                    id = targetId,
                    title = "${session.title} (Trimmed)",
                    folderName = targetFolderName,
                    startTimeMillis = now,
                    endTimeMillis = now + chunk.durationMillis,
                    format = session.format,
                    recordingMode = session.recordingMode,
                    status = SessionStatus.READY,
                    chunks = listOf(chunk),
                    bookmarks = shiftedBookmarks,
                    isFavorite = false,
                    category = session.category,
                    deletedAtMillis = null,
                    notes = null
                )
                writeManifestAtomic(targetDir, copySession)
                refreshListLocked(copySession)
                copySession
            } else {
                // Swap new file into original folder and remove old chunk files
                val newSrcFile = File(workDir, chunk.fileName)
                val finalChunkFile = File(targetDir, chunk.fileName)
                session.chunks.forEach { old ->
                    runCatching { File(targetDir, old.fileName).delete() }
                }
                if (!newSrcFile.renameTo(finalChunkFile)) {
                    newSrcFile.copyTo(finalChunkFile, overwrite = true)
                    newSrcFile.delete()
                }
                runCatching { workDir.deleteRecursively() }

                val replaced = session.copy(
                    endTimeMillis = session.startTimeMillis + chunk.durationMillis,
                    chunks = listOf(chunk.copy(sizeBytes = finalChunkFile.length())),
                    bookmarks = shiftedBookmarks,
                    notes = null
                )
                writeManifestAtomic(targetDir, replaced)
                refreshListLocked(replaced)
                replaced
            }
        }
    }

    /**
     * Non-destructive Split: splits a recording at [splitMs] into two new recordings (Part 1 & Part 2),
     * leaving the original untouched unless [keepOriginal] is false.
     */
    suspend fun splitSession(
        session: RecordingSession,
        splitMs: Long,
        keepOriginal: Boolean = true
    ): Pair<RecordingSession, RecordingSession>? = withContext(Dispatchers.IO) {
        val totalMs = session.totalSpeechMillis.coerceAtLeast(1L)
        val clampedSplit = splitMs.coerceIn(500L, (totalMs - 500L).coerceAtLeast(500L))
        if (clampedSplit <= 0L || clampedSplit >= totalMs) return@withContext null

        val part1 = trimSession(session, 0L, clampedSplit, saveAsNewCopy = true) ?: return@withContext null
        val part2 = trimSession(session, clampedSplit, totalMs, saveAsNewCopy = true) ?: return@withContext null

        renameSession(part1.id, "${session.title} (Part 1)")
        renameSession(part2.id, "${session.title} (Part 2)")

        if (!keepOriginal) {
            moveToTrash(session.id)
        }

        val updated1 = synchronized(lock) { _sessions.value.firstOrNull { it.id == part1.id } ?: part1 }
        val updated2 = synchronized(lock) { _sessions.value.firstOrNull { it.id == part2.id } ?: part2 }
        updated1 to updated2
    }

    /**
     * Two-Pass Offline Prominent-Voice & Background-Noise Cleaner (zero AI, O(1) RAM):
     * - Pass 1 ([AudioAcousticProfiler]): profiles the whole recording's ambient noise floor,
     *   prominent foreground speaker peak level, and per-bin steady noise spectrum.
     * - Pass 2 ([ProminentVoiceCleaner]): trims out silence, background noise, and quieter distant
     *   voices below the prominence cutoff, optionally reduces steady fan/AC/hum noise underneath
     *   kept speech via 50% overlap-add FFT, and re-encodes in the session's native format.
     */
    suspend fun removeSilenceFromSession(
        session: RecordingSession,
        config: VoiceCleanConfig = VoiceCleanConfig(),
        saveAsNewCopy: Boolean = true
    ): SilenceRemovalResult? = withContext(Dispatchers.IO) {
        val sources = session.chunks
            .map { getChunkFile(session, it) }
            .filter { it.exists() && it.length() > 0L }
        if (sources.isEmpty()) return@withContext null

        // PASS 1: Whole-recording acoustic profiling (O(1) RAM streaming)
        val profiler = AudioAcousticProfiler(sampleRate = SAMPLE_RATE)
        try {
            for (src in sources) {
                AudioRemuxer.decodeFileIntoConsumer(src) { samples, length ->
                    if (length > 0) {
                        profiler.processPcm(samples, length)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pass 1 acoustic profiling failed for session ${session.id}", e)
            return@withContext null
        }
        val profile = profiler.finish()

        val now = System.currentTimeMillis()
        val targetId = if (saveAsNewCopy) UUID.randomUUID().toString().take(8) else session.id
        val targetStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val targetFolderName = if (saveAsNewCopy) "session_${targetStamp}_$targetId" else session.folderName
        val targetDir = File(sessionsRoot, targetFolderName).apply { mkdirs() }
        val workDir = if (saveAsNewCopy) targetDir else File(targetDir, "clean_tmp_$targetStamp").apply { mkdirs() }

        val writer = AudioChunkWriterFactory.createAndOpen(
            directory = workDir,
            baseFileName = "part_0001",
            format = session.format,
            sampleRate = SAMPLE_RATE,
            channels = 1
        )

        var wroteAny = false
        val safeConfig = config.copy(
            keepPauseMillis = config.keepPauseMillis.coerceIn(250L, 5_000L),
            prominenceOffsetDb = config.prominenceOffsetDb.coerceIn(-24f, -4f)
        )
        val cleaner = ProminentVoiceCleaner(
            profile = profile,
            config = safeConfig,
            sampleRate = SAMPLE_RATE
        ) { samples, offset, length ->
            if (length > 0) {
                writer.writePcm(samples, offset, length)
                wroteAny = true
            }
        }

        // PASS 2: Prominent-voice trimming + optional steady noise reduction
        val chunkResult = try {
            for (src in sources) {
                AudioRemuxer.decodeFileIntoConsumer(src) { samples, length ->
                    if (length > 0) {
                        cleaner.processPcm(samples, length)
                    }
                }
            }
            cleaner.finish()
            val finalized = writer.closeAndFinalize()
            if (!wroteAny || finalized.sizeBytes <= 0L || finalized.durationMillis <= 0L) {
                runCatching { finalized.file.delete() }
                null
            } else {
                var finalFile = finalized.file
                var finalExt = finalized.actualExtension
                if (session.format == AudioFormatOption.M4A_AAC &&
                    finalExt.equals("aac", ignoreCase = true)
                ) {
                    val m4aFile = File(workDir, "${finalFile.nameWithoutExtension}.m4a")
                    if (AudioRemuxer.remuxAdtsToM4a(listOf(finalFile), m4aFile)) {
                        runCatching { finalFile.delete() }
                        finalFile = m4aFile
                        finalExt = "m4a"
                    }
                }
                AudioChunk(
                    index = 1,
                    fileName = finalFile.name,
                    startTimeMillis = session.startTimeMillis,
                    durationMillis = finalized.durationMillis,
                    sizeBytes = if (finalFile.exists()) finalFile.length() else finalized.sizeBytes,
                    actualExtension = finalExt
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed cleaning voice/noise from session ${session.id}", e)
            runCatching { writer.closeAndFinalize() }
            null
        }

        if (chunkResult == null) {
            if (saveAsNewCopy) runCatching { targetDir.deleteRecursively() }
            else runCatching { workDir.deleteRecursively() }
            return@withContext null
        }

        val originalDuration = maxOf(cleaner.inputDurationMillis, session.totalSpeechMillis)
        val cleanedDuration = chunkResult.durationMillis

        val savedSession = synchronized(lock) {
            if (saveAsNewCopy) {
                val copySession = RecordingSession(
                    id = targetId,
                    title = "${session.title} (Cleaned)",
                    folderName = targetFolderName,
                    startTimeMillis = now,
                    endTimeMillis = now + chunkResult.durationMillis,
                    format = session.format,
                    recordingMode = session.recordingMode,
                    status = SessionStatus.READY,
                    chunks = listOf(chunkResult),
                    bookmarks = emptyList(),
                    isFavorite = false,
                    category = session.category,
                    deletedAtMillis = null,
                    notes = null
                )
                writeManifestAtomic(targetDir, copySession)
                refreshListLocked(copySession)
                copySession
            } else {
                val newSrcFile = File(workDir, chunkResult.fileName)
                val finalChunkFile = File(targetDir, chunkResult.fileName)
                session.chunks.forEach { old ->
                    runCatching { File(targetDir, old.fileName).delete() }
                }
                if (!newSrcFile.renameTo(finalChunkFile)) {
                    newSrcFile.copyTo(finalChunkFile, overwrite = true)
                    newSrcFile.delete()
                }
                runCatching { workDir.deleteRecursively() }

                val replaced = session.copy(
                    endTimeMillis = session.startTimeMillis + chunkResult.durationMillis,
                    chunks = listOf(chunkResult.copy(sizeBytes = finalChunkFile.length())),
                    bookmarks = emptyList(),
                    notes = null
                )
                writeManifestAtomic(targetDir, replaced)
                refreshListLocked(replaced)
                replaced
            }
        }

        SilenceRemovalResult(
            session = savedSession,
            originalDurationMillis = originalDuration,
            cleanedDurationMillis = cleanedDuration,
            detectedNoiseFloorDb = profile.noiseFloorDb,
            detectedProminentPeakDb = profile.prominentPeakDb,
            noiseReductionApplied = safeConfig.reduceSteadyNoise
        )
    }

    private fun writeSlicedSegment(
        sourceSession: RecordingSession,
        destinationDir: File,
        startSample: Long,
        endSample: Long,
        chunkStartTimeMillis: Long
    ): AudioChunk? {
        val sources = sourceSession.chunks
            .map { getChunkFile(sourceSession, it) }
            .filter { it.exists() && it.length() > 0L }
        if (sources.isEmpty()) return null

        val baseName = "part_0001"
        val writer = AudioChunkWriterFactory.createAndOpen(
            directory = destinationDir,
            baseFileName = baseName,
            format = sourceSession.format,
            sampleRate = SAMPLE_RATE,
            channels = 1
        )

        var cursorSample = 0L
        var wroteAny = false

        try {
            for (src in sources) {
                if (cursorSample >= endSample) break
                AudioRemuxer.decodeFileIntoConsumer(src) { samples, length ->
                    if (length <= 0) return@decodeFileIntoConsumer
                    val segStart = cursorSample
                    val segEnd = cursorSample + length
                    cursorSample = segEnd

                    if (segEnd <= startSample || segStart >= endSample) return@decodeFileIntoConsumer

                    val localStart = (startSample - segStart).coerceAtLeast(0L).toInt()
                    val localEnd = (endSample - segStart).coerceAtMost(length.toLong()).toInt()
                    val count = localEnd - localStart
                    if (count > 0) {
                        writer.writePcm(samples, localStart, count)
                        wroteAny = true
                    }
                }
            }
            val result = writer.closeAndFinalize()
            if (!wroteAny || result.sizeBytes <= 0L || result.durationMillis <= 0L) {
                runCatching { result.file.delete() }
                return null
            }

            var finalFile = result.file
            var finalExt = result.actualExtension
            if (sourceSession.format == AudioFormatOption.M4A_AAC &&
                finalExt.equals("aac", ignoreCase = true)
            ) {
                val m4aFile = File(destinationDir, "${finalFile.nameWithoutExtension}.m4a")
                if (AudioRemuxer.remuxAdtsToM4a(listOf(finalFile), m4aFile)) {
                    runCatching { finalFile.delete() }
                    finalFile = m4aFile
                    finalExt = "m4a"
                }
            }

            return AudioChunk(
                index = 1,
                fileName = finalFile.name,
                startTimeMillis = chunkStartTimeMillis,
                durationMillis = result.durationMillis,
                sizeBytes = if (finalFile.exists()) finalFile.length() else result.sizeBytes,
                actualExtension = finalExt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing sliced segment", e)
            runCatching { writer.closeAndFinalize() }
            return null
        }
    }

    private fun mergeViaFormatWriter(
        sources: List<File>,
        format: AudioFormatOption,
        destination: File
    ): Boolean {
        return when (format) {
            AudioFormatOption.M4A_AAC -> {
                val tempAdts = File(exportCacheDir, "merge_tmp_${System.nanoTime()}.aac")
                val ok = AudioRemuxer.mergeEncodedChunksViaPcm(
                    sources = sources,
                    destination = tempAdts,
                    targetWriter = AacAdtsChunkWriter()
                )
                val remuxed = ok && AudioRemuxer.remuxAdtsToM4a(listOf(tempAdts), destination)
                runCatching { tempAdts.delete() }
                remuxed
            }
            AudioFormatOption.OGG_OPUS -> AudioRemuxer.mergeEncodedChunksViaPcm(
                sources = sources,
                destination = destination,
                targetWriter = OggOpusChunkWriter()
            )
            AudioFormatOption.FLAC -> AudioRemuxer.mergeEncodedChunksViaPcm(
                sources = sources,
                destination = destination,
                targetWriter = FlacChunkWriter()
            )
            AudioFormatOption.MP3 -> AudioRemuxer.mergeEncodedChunksViaPcm(
                sources = sources,
                destination = destination,
                targetWriter = Mp3ChunkWriter()
            )
            AudioFormatOption.WAV -> AudioRemuxer.mergeEncodedChunksViaPcm(
                sources = sources,
                destination = destination,
                targetWriter = WavChunkWriter()
            )
        }
    }

    private fun mergeWavFiles(sources: List<File>, destination: File) {
        var totalPcmBytes = 0L
        FileOutputStream(destination).use { out ->
            out.write(WavChunkWriter.buildWavHeader(0L, SAMPLE_RATE, 1))
            val buf = ByteArray(16_384)
            for (src in sources) {
                if (src.length() <= WavChunkWriter.HEADER_SIZE) continue
                FileInputStream(src).use { input ->
                    var skipped = 0L
                    while (skipped < WavChunkWriter.HEADER_SIZE) {
                        val s = input.skip(WavChunkWriter.HEADER_SIZE - skipped)
                        if (s <= 0L) break
                        skipped += s
                    }
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        totalPcmBytes += read
                    }
                }
            }
            out.flush()
            out.fd.sync()
        }
        RandomAccessFile(destination, "rw").use { raf ->
            raf.seek(0L)
            raf.write(WavChunkWriter.buildWavHeader(totalPcmBytes, SAMPLE_RATE, 1))
            raf.channel.force(false)
        }
    }

    private fun concatenateRawFiles(sources: List<File>, destination: File) {
        FileOutputStream(destination).use { out ->
            val buf = ByteArray(16_384)
            for (src in sources) {
                FileInputStream(src).use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
            }
            out.flush()
            out.fd.sync()
        }
    }

    private fun folderSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .trim()
            .replace(" ", "_")
            .ifBlank { "NTK_VOX_Recording" }
    }

    private fun mimeForExtension(ext: String): String = when (ext.lowercase(Locale.US)) {
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        else -> "audio/wav"
    }

    private fun refreshListLocked(updated: RecordingSession) {
        val current = _sessions.value.filterNot { it.id == updated.id } + updated
        _sessions.value = current.sortedByDescending { it.startTimeMillis }
    }

    private fun writeManifestAtomic(dir: File, session: RecordingSession) {
        dir.mkdirs()
        val tmpFile = File(dir, "$MANIFEST_NAME.tmp")
        val targetFile = File(dir, MANIFEST_NAME)
        val payload = serializeManifest(session).toString(2).toByteArray(Charsets.UTF_8)

        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(payload)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed atomic manifest write for ${session.id}", e)
        }
    }

    private fun serializeManifest(session: RecordingSession): JSONObject {
        val chunksJson = JSONArray()
        for (chunk in session.chunks) {
            chunksJson.put(
                JSONObject().apply {
                    put("index", chunk.index)
                    put("fileName", chunk.fileName)
                    put("startTimeMillis", chunk.startTimeMillis)
                    put("durationMillis", chunk.durationMillis)
                    put("sizeBytes", chunk.sizeBytes)
                    put("actualExtension", chunk.actualExtension)
                }
            )
        }

        val bookmarksJson = JSONArray()
        for (bm in session.bookmarks) {
            bookmarksJson.put(
                JSONObject().apply {
                    put("id", bm.id)
                    put("timestampMillis", bm.timestampMillis)
                    put("offsetFromSessionStartMillis", bm.offsetFromSessionStartMillis)
                    put("label", bm.label)
                }
            )
        }

        return JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("folderName", session.folderName)
            put("startTimeMillis", session.startTimeMillis)
            put("endTimeMillis", session.endTimeMillis)
            put("formatId", session.format.id)
            put("recordingModeId", session.recordingMode.id)
            put("status", session.status.name)
            put("chunks", chunksJson)
            put("bookmarks", bookmarksJson)
            put("isFavorite", session.isFavorite)
            put("categoryId", session.category.id)
            if (session.deletedAtMillis != null) {
                put("deletedAtMillis", session.deletedAtMillis)
            }
            session.notes?.let { notes ->
                put(
                    "notes",
                    JSONObject().apply {
                        put("transcript", notes.transcript)
                        put("summary", notes.summary)
                        put("highlights", JSONArray(notes.highlights))
                        put("keywords", JSONArray(notes.keywords))
                        put("generatedAtMillis", notes.generatedAtMillis)
                    }
                )
            }
        }
    }

    private fun readManifest(file: File): RecordingSession {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val chunksArray = root.optJSONArray("chunks") ?: JSONArray()
        val chunks = mutableListOf<AudioChunk>()
        for (i in 0 until chunksArray.length()) {
            val c = chunksArray.getJSONObject(i)
            chunks.add(
                AudioChunk(
                    index = c.optInt("index", i + 1),
                    fileName = c.getString("fileName"),
                    startTimeMillis = c.optLong("startTimeMillis", 0L),
                    durationMillis = c.optLong("durationMillis", 0L),
                    sizeBytes = c.optLong("sizeBytes", 0L),
                    actualExtension = c.optString("actualExtension", "wav")
                )
            )
        }

        val bookmarksArray = root.optJSONArray("bookmarks") ?: JSONArray()
        val bookmarks = mutableListOf<SessionBookmark>()
        for (i in 0 until bookmarksArray.length()) {
            val b = bookmarksArray.getJSONObject(i)
            bookmarks.add(
                SessionBookmark(
                    id = b.optString("id", UUID.randomUUID().toString().take(8)),
                    timestampMillis = b.optLong("timestampMillis", 0L),
                    offsetFromSessionStartMillis = b.optLong("offsetFromSessionStartMillis", 0L),
                    label = b.optString("label", "Moment #${i + 1}")
                )
            )
        }

        val notesObj = root.optJSONObject("notes")
        val notes = if (notesObj != null) {
            val highlightsArr = notesObj.optJSONArray("highlights") ?: JSONArray()
            val highlights = (0 until highlightsArr.length()).map { highlightsArr.getString(it) }
            val keywordsArr = notesObj.optJSONArray("keywords") ?: JSONArray()
            val keywords = (0 until keywordsArr.length()).map { keywordsArr.getString(it) }
            SessionNotes(
                transcript = notesObj.optString("transcript", ""),
                summary = notesObj.optString("summary", ""),
                highlights = highlights,
                keywords = keywords,
                generatedAtMillis = notesObj.optLong("generatedAtMillis", 0L)
            )
        } else {
            null
        }

        val deletedAt = if (root.has("deletedAtMillis") && !root.isNull("deletedAtMillis")) {
            root.optLong("deletedAtMillis", -1L).takeIf { it > 0L }
        } else {
            null
        }

        return RecordingSession(
            id = root.getString("id"),
            title = root.optString("title", buildDefaultTitle(root.optLong("startTimeMillis", 0L))),
            folderName = root.getString("folderName"),
            startTimeMillis = root.optLong("startTimeMillis", 0L),
            endTimeMillis = root.optLong("endTimeMillis", 0L),
            format = AudioFormatOption.fromId(root.optString("formatId")),
            recordingMode = RecordingMode.fromId(root.optString("recordingModeId")),
            status = runCatching { SessionStatus.valueOf(root.optString("status")) }
                .getOrDefault(SessionStatus.READY),
            chunks = chunks.sortedBy { it.index },
            bookmarks = bookmarks.sortedBy { it.offsetFromSessionStartMillis },
            isFavorite = root.optBoolean("isFavorite", false),
            category = RecordingCategory.fromId(root.optString("categoryId")),
            deletedAtMillis = deletedAt,
            notes = notes
        )
    }

    private fun buildDefaultTitle(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault())
        return formatter.format(Date(timestampMillis))
    }

    companion object {
        private const val TAG = "SessionRepository"
        private const val MANIFEST_NAME = "manifest.json"
        private const val SAMPLE_RATE = 16_000
        const val TRASH_RETENTION_DAYS = 30L
        const val TRASH_RETENTION_MILLIS = TRASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L
    }
}
