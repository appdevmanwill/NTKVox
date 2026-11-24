package com.example.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ClearVoxGraph
import com.example.model.AppThemeMode
import com.example.model.AudioFormatOption
import com.example.model.BatteryOptimizationProfile
import com.example.model.BatteryTelemetry
import com.example.model.RecordingCategory
import com.example.model.RecordingMode
import com.example.model.RecordingSchedule
import com.example.model.RecordingSession
import com.example.model.RecordingSortOrder
import com.example.model.ScheduleRepeat
import com.example.model.StorageSummary
import com.example.model.UserSettings
import com.example.model.VoiceCleanConfig
import com.example.model.VoiceSensitivity
import com.example.service.PlaybackServiceState
import com.example.service.RecorderServiceState
import com.example.service.ScheduleAlarmReceiver
import com.example.service.VoxPlaybackService
import com.example.service.VoxRecordingService
import com.example.util.FormatUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShareExportEvent(
    val uri: Uri,
    val mimeType: String,
    val title: String
)

sealed interface RecordingFilter {
    data object All : RecordingFilter
    data object Starred : RecordingFilter
    data class Category(val category: RecordingCategory) : RecordingFilter
}

class ClearVoxViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepository = ClearVoxGraph.settingsRepository(appContext)
    val sessionRepository = ClearVoxGraph.sessionRepository(appContext)
    val scheduleRepository = ClearVoxGraph.scheduleRepository(appContext)
    private val speechProcessor = ClearVoxGraph.speechProcessor(appContext)

    val settings: StateFlow<UserSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = UserSettings()
        )

    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    val sessions: StateFlow<List<RecordingSession>> = sessionRepository.sessions

    val activeSessions: StateFlow<List<RecordingSession>> = sessions
        .map { list -> list.filterNot { it.isTrashed } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trashedSessions: StateFlow<List<RecordingSession>> = sessions
        .map { list -> list.filter { it.isTrashed }.sortedByDescending { it.deletedAtMillis ?: 0L } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val schedules: StateFlow<List<RecordingSchedule>> = scheduleRepository.schedules

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow<RecordingFilter>(RecordingFilter.All)
    val selectedFilter: StateFlow<RecordingFilter> = _selectedFilter.asStateFlow()

    private val _sortOrder = MutableStateFlow(RecordingSortOrder.NEWEST)
    val sortOrder: StateFlow<RecordingSortOrder> = _sortOrder.asStateFlow()

    private val _selectedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedSessionIds: StateFlow<Set<String>> = _selectedSessionIds.asStateFlow()

    val filteredSessions: StateFlow<List<RecordingSession>> = combine(
        activeSessions,
        _searchQuery,
        _selectedFilter,
        _sortOrder
    ) { list, query, filter, sort ->
        val byFilter = when (filter) {
            RecordingFilter.All -> list
            RecordingFilter.Starred -> list.filter { it.isFavorite }
            is RecordingFilter.Category -> list.filter { it.category == filter.category }
        }
        val trimmed = query.trim()
        val searched = if (trimmed.isEmpty()) {
            byFilter
        } else {
            val q = trimmed.lowercase()
            byFilter.filter { session ->
                session.title.lowercase().contains(q) ||
                    session.category.displayName.lowercase().contains(q) ||
                    session.format.shortBadge.lowercase().contains(q) ||
                    session.recordingMode.displayName.lowercase().contains(q) ||
                    session.bookmarks.any { it.label.lowercase().contains(q) } ||
                    (session.notes?.summary?.lowercase()?.contains(q) == true) ||
                    (session.notes?.transcript?.lowercase()?.contains(q) == true) ||
                    (session.notes?.keywords?.any { it.lowercase().contains(q) } == true)
            }
        }
        when (sort) {
            RecordingSortOrder.NEWEST -> searched.sortedByDescending { it.startTimeMillis }
            RecordingSortOrder.OLDEST -> searched.sortedBy { it.startTimeMillis }
            RecordingSortOrder.LONGEST -> searched.sortedByDescending { it.totalSpeechMillis }
            RecordingSortOrder.LARGEST -> searched.sortedByDescending { it.totalSizeBytes }
            RecordingSortOrder.TITLE_AZ -> searched.sortedBy { it.title.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _batteryTelemetry = MutableStateFlow(BatteryTelemetry())
    val batteryTelemetry: StateFlow<BatteryTelemetry> = _batteryTelemetry.asStateFlow()

    private val _recorderState = MutableStateFlow(RecorderServiceState())
    val recorderState: StateFlow<RecorderServiceState> = _recorderState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackServiceState())
    val playbackState: StateFlow<PlaybackServiceState> = _playbackState.asStateFlow()

    private val _storageSummary = MutableStateFlow(StorageSummary())
    val storageSummary: StateFlow<StorageSummary> = _storageSummary.asStateFlow()

    private val _processingSessionId = MutableStateFlow<String?>(null)
    val processingSessionId: StateFlow<String?> = _processingSessionId.asStateFlow()

    private val _processingStage = MutableStateFlow<String?>(null)
    val processingStage: StateFlow<String?> = _processingStage.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _isEditingAudio = MutableStateFlow(false)
    val isEditingAudio: StateFlow<Boolean> = _isEditingAudio.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _shareEvent = MutableSharedFlow<ShareExportEvent>(extraBufferCapacity = 2)
    val shareEvent: SharedFlow<ShareExportEvent> = _shareEvent.asSharedFlow()

    private var boundService: VoxRecordingService? = null
    private var serviceCollectorJob: Job? = null

    private var boundPlaybackService: VoxPlaybackService? = null
    private var playbackCollectorJob: Job? = null
    private var pendingPlaybackSession: RecordingSession? = null
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (level >= 0) {
                val pct = ((level * 100) / scale).coerceIn(0, 100)
                _batteryTelemetry.value = BatteryTelemetry(
                    levelPercent = pct,
                    isCharging = plugged != 0
                )
            }
        }
    }

    init {
        runCatching {
            val sticky = ContextCompat.registerReceiver(
                appContext,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            batteryReceiverRegistered = true
            sticky?.let { batteryReceiver.onReceive(appContext, it) }
        }
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect {
                _settingsLoaded.value = true
            }
        }
        viewModelScope.launch {
            sessionRepository.recoverAndLoadSessions()
            refreshStorageSummary()
        }
        viewModelScope.launch {
            scheduleRepository.loadAndRescheduleAll()
        }
    }

    override fun onCleared() {
        if (batteryReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        super.onCleared()
    }

    fun attachService(service: VoxRecordingService) {
        boundService = service
        serviceCollectorJob?.cancel()
        serviceCollectorJob = viewModelScope.launch {
            service.state.collect { state ->
                _recorderState.value = state
            }
        }
    }

    fun detachService() {
        serviceCollectorJob?.cancel()
        serviceCollectorJob = null
        boundService = null
    }

    fun attachPlaybackService(service: VoxPlaybackService) {
        boundPlaybackService = service
        playbackCollectorJob?.cancel()
        playbackCollectorJob = viewModelScope.launch {
            service.state.collect { state ->
                _playbackState.value = state
            }
        }
        val pending = pendingPlaybackSession
        if (service.state.value.sessionId == null && pending != null) {
            service.loadSession(
                session = pending,
                autoPlay = false,
                initialSpeed = settings.value.defaultPlaybackSpeed
            )
        }
    }

    fun detachPlaybackService() {
        playbackCollectorJob?.cancel()
        playbackCollectorJob = null
        boundPlaybackService = null
    }

    // --- Recording Controls ---

    fun startRecording() {
        boundPlaybackService?.pause()
        val intent = Intent(appContext, VoxRecordingService::class.java).apply {
            action = VoxRecordingService.ACTION_START
        }
        appContext.startForegroundService(intent)
    }

    fun startScheduledRecordingNow(
        scheduleId: String?,
        label: String?,
        endTimeMillis: Long?,
        modeId: String?
    ) {
        boundPlaybackService?.pause()
        val intent = Intent(appContext, VoxRecordingService::class.java).apply {
            action = VoxRecordingService.ACTION_SCHEDULE_TRIGGER
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_LABEL, label)
            putExtra(ScheduleAlarmReceiver.EXTRA_END_TIME_MILLIS, endTimeMillis ?: 0L)
            putExtra(ScheduleAlarmReceiver.EXTRA_MODE_ID, modeId)
        }
        appContext.startForegroundService(intent)
    }

    fun pauseRecording() {
        val service = boundService
        if (service != null) {
            service.pauseManual()
        } else {
            val intent = Intent(appContext, VoxRecordingService::class.java).apply {
                action = VoxRecordingService.ACTION_PAUSE
            }
            appContext.startService(intent)
        }
    }

    fun resumeRecording() {
        boundPlaybackService?.pause()
        val service = boundService
        if (service != null) {
            service.resumeManual()
        } else {
            val intent = Intent(appContext, VoxRecordingService::class.java).apply {
                action = VoxRecordingService.ACTION_RESUME
            }
            appContext.startService(intent)
        }
    }

    fun markMoment() {
        val ok = boundService?.addBookmark() == true
        if (ok) {
            _snackbarMessage.tryEmit("Moment bookmarked")
        }
    }

    fun stopRecording() {
        val service = boundService
        if (service != null) {
            service.stopSession()
        } else {
            val intent = Intent(appContext, VoxRecordingService::class.java).apply {
                action = VoxRecordingService.ACTION_STOP
            }
            appContext.startService(intent)
        }
    }

    // --- Scheduled Recording & Standby ---

    fun canScheduleExactAlarms(): Boolean = scheduleRepository.canScheduleExactAlarms()

    fun openExactAlarmSettings() = scheduleRepository.openExactAlarmSettings()

    fun saveSchedule(
        existingId: String? = null,
        label: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        repeat: ScheduleRepeat,
        recordingMode: RecordingMode
    ) {
        viewModelScope.launch {
            scheduleRepository.upsertSchedule(
                existingId = existingId,
                label = label,
                startTimeMillis = startTimeMillis,
                endTimeMillis = endTimeMillis,
                repeat = repeat,
                recordingMode = recordingMode,
                isEnabled = true
            )
            _snackbarMessage.emit("Schedule saved")
        }
    }

    fun toggleSchedule(id: String, enabled: Boolean) {
        viewModelScope.launch {
            scheduleRepository.toggleSchedule(id, enabled)
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            scheduleRepository.deleteSchedule(id)
            _snackbarMessage.emit("Schedule removed")
        }
    }

    fun armStandbyForNextSchedule() {
        val next = scheduleRepository.getNextUpcomingSchedule()
        if (next == null) {
            _snackbarMessage.tryEmit("Enable an upcoming schedule first.")
            return
        }
        boundPlaybackService?.pause()
        val armIntent = Intent(appContext, VoxRecordingService::class.java).apply {
            action = VoxRecordingService.ACTION_ARM_SCHEDULE
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, next.id)
        }
        val started = runCatching {
            ContextCompat.startForegroundService(appContext, armIntent)
            true
        }.getOrDefault(false)
        if (started) {
            boundService?.armForSchedule(next)
            _snackbarMessage.tryEmit("Armed Standby for ${next.label}")
        } else {
            _snackbarMessage.tryEmit("Could not arm standby mode. Check microphone permission.")
        }
    }

    fun disarmStandby() {
        val service = boundService
        if (service != null) {
            service.disarmStandby()
        } else {
            val intent = Intent(appContext, VoxRecordingService::class.java).apply {
                action = VoxRecordingService.ACTION_DISARM_SCHEDULE
            }
            appContext.startService(intent)
        }
        _snackbarMessage.tryEmit("Standby disarmed")
    }

    // --- Settings ---

    fun updateRecordingMode(mode: RecordingMode) {
        viewModelScope.launch { settingsRepository.setRecordingMode(mode) }
    }

    fun updateAudioFormat(format: AudioFormatOption) {
        viewModelScope.launch { settingsRepository.setAudioFormat(format) }
    }

    fun updateVoiceSensitivity(sensitivity: VoiceSensitivity) {
        viewModelScope.launch { settingsRepository.setVoiceSensitivity(sensitivity) }
    }

    fun updateSilenceDelay(seconds: Int) {
        viewModelScope.launch { settingsRepository.setSilenceDelaySeconds(seconds) }
    }

    fun updateAutoStopMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setAutoStopMinutes(minutes) }
    }

    fun updateSilenceAutoStopMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setSilenceAutoStopMinutes(minutes) }
    }

    fun updateDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.setDefaultPlaybackSpeed(speed) }
        boundPlaybackService?.setPlaybackSpeed(speed)
    }

    fun updateBatteryProfile(profile: BatteryOptimizationProfile) {
        viewModelScope.launch { settingsRepository.setBatteryProfile(profile) }
    }

    fun updateLowBatteryStopPercent(percent: Int) {
        viewModelScope.launch { settingsRepository.setLowBatteryStopPercent(percent) }
    }

    fun updateThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun cycleThemeMode() {
        val next = settings.value.themeMode.next()
        viewModelScope.launch {
            settingsRepository.setThemeMode(next)
            _snackbarMessage.emit("Theme: ${next.displayName}")
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
    }

    // --- Search, Filters, Sort, Multi-Select, Bookmarks, Favorites, Categories & Trash ---

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateFilter(filter: RecordingFilter) {
        _selectedFilter.value = filter
    }

    fun updateSortOrder(order: RecordingSortOrder) {
        _sortOrder.value = order
    }

    fun toggleSessionSelection(sessionId: String) {
        val current = _selectedSessionIds.value
        _selectedSessionIds.value = if (sessionId in current) current - sessionId else current + sessionId
    }

    fun selectAllVisibleSessions() {
        _selectedSessionIds.value = filteredSessions.value.map { it.id }.toSet()
    }

    fun clearSessionSelection() {
        _selectedSessionIds.value = emptySet()
    }

    fun batchFavoriteSelected() {
        val ids = _selectedSessionIds.value
        if (ids.isEmpty()) return
        val selectedList = activeSessions.value.filter { it.id in ids }
        val targetFavorite = selectedList.any { !it.isFavorite }
        viewModelScope.launch {
            sessionRepository.batchSetFavorite(ids, targetFavorite)
            clearSessionSelection()
            _snackbarMessage.emit(
                if (targetFavorite) "Starred ${ids.size} recordings" else "Unstarred ${ids.size} recordings"
            )
        }
    }

    fun batchCategorizeSelected(category: RecordingCategory) {
        val ids = _selectedSessionIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            sessionRepository.batchUpdateCategory(ids, category)
            clearSessionSelection()
            _snackbarMessage.emit("Moved ${ids.size} recordings to ${category.displayName}")
        }
    }

    fun batchMoveSelectedToTrash() {
        val ids = _selectedSessionIds.value
        if (ids.isEmpty()) return
        if (_playbackState.value.sessionId in ids) {
            boundPlaybackService?.stopAndDismiss()
        }
        viewModelScope.launch {
            sessionRepository.batchMoveToTrash(ids)
            clearSessionSelection()
            refreshStorageSummary()
            _snackbarMessage.emit("Moved ${ids.size} recordings to Trash")
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch { sessionRepository.renameSession(sessionId, newTitle) }
    }

    fun toggleFavorite(sessionId: String) {
        viewModelScope.launch { sessionRepository.toggleFavorite(sessionId) }
    }

    fun updateCategory(sessionId: String, category: RecordingCategory) {
        viewModelScope.launch { sessionRepository.updateCategory(sessionId, category) }
    }

    fun addBookmarkAtPlaybackPosition(sessionId: String, offsetMillis: Long) {
        viewModelScope.launch {
            val created = sessionRepository.addBookmark(
                sessionId = sessionId,
                offsetFromSessionStartMillis = offsetMillis
            )
            if (created != null) {
                _snackbarMessage.emit("Bookmarked at ${FormatUtils.formatHms(offsetMillis)}")
            }
        }
    }

    fun updateBookmarkLabel(sessionId: String, bookmarkId: String, newLabel: String) {
        viewModelScope.launch {
            sessionRepository.updateBookmarkLabel(sessionId, bookmarkId, newLabel)
        }
    }

    fun deleteBookmark(sessionId: String, bookmarkId: String) {
        viewModelScope.launch {
            sessionRepository.deleteBookmark(sessionId, bookmarkId)
        }
    }

    fun moveToTrash(sessionId: String) {
        if (_playbackState.value.sessionId == sessionId) {
            boundPlaybackService?.stopAndDismiss()
        }
        viewModelScope.launch {
            sessionRepository.moveToTrash(sessionId)
            refreshStorageSummary()
            _snackbarMessage.emit("Moved to Recently Deleted")
        }
    }

    fun restoreFromTrash(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.restoreFromTrash(sessionId)
            refreshStorageSummary()
            _snackbarMessage.emit("Recording restored")
        }
    }

    fun deleteSessionPermanently(sessionId: String) {
        if (_playbackState.value.sessionId == sessionId) {
            boundPlaybackService?.stopAndDismiss()
        }
        viewModelScope.launch {
            sessionRepository.deleteSessionPermanently(sessionId)
            refreshStorageSummary()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val freedBytes = _storageSummary.value.trashedBytes
            sessionRepository.emptyTrash()
            val updated = sessionRepository.computeStorageSummary()
            _storageSummary.value = updated
            val message = if (freedBytes > 0L) {
                "Trash emptied • freed ${FormatUtils.formatFileSize(freedBytes)}"
            } else {
                "Trash emptied"
            }
            _snackbarMessage.emit(message)
        }
    }

    // --- Storage Manager ---

    fun refreshStorageSummary() {
        viewModelScope.launch {
            _storageSummary.value = sessionRepository.computeStorageSummary()
        }
    }

    fun clearExportCache() {
        viewModelScope.launch {
            _storageSummary.value = sessionRepository.clearExportCache()
            _snackbarMessage.emit("Temporary export cache cleared")
        }
    }

    // --- Background Playback Controls ---

    fun loadSessionForPlayback(session: RecordingSession, autoPlay: Boolean = false) {
        pendingPlaybackSession = session
        val defaultSpeed = settings.value.defaultPlaybackSpeed
        boundPlaybackService?.loadSession(session, autoPlay = autoPlay, initialSpeed = defaultSpeed)
    }

    fun togglePlayback() {
        val service = boundPlaybackService
        if (service != null) {
            if (service.state.value.sessionId == null && pendingPlaybackSession != null) {
                service.loadSession(
                    session = pendingPlaybackSession!!,
                    autoPlay = true,
                    initialSpeed = settings.value.defaultPlaybackSpeed
                )
            } else {
                service.togglePlayPause()
            }
        }
    }

    fun togglePlaybackForSession(session: RecordingSession) {
        pendingPlaybackSession = session
        val service = boundPlaybackService ?: return
        if (service.state.value.sessionId != session.id) {
            service.loadSession(
                session = session,
                autoPlay = true,
                initialSpeed = settings.value.defaultPlaybackSpeed
            )
        } else {
            service.togglePlayPause()
        }
    }

    fun pausePlayback() {
        boundPlaybackService?.pause()
    }

    fun seekPlaybackGlobal(targetMs: Long) {
        boundPlaybackService?.seekToGlobal(targetMs)
    }

    fun seekPlaybackToChunk(chunkIndex: Int, autoPlay: Boolean = true) {
        boundPlaybackService?.seekToChunk(chunkIndex, autoPlay = autoPlay)
    }

    fun skipPlaybackBy(deltaMs: Long) {
        boundPlaybackService?.skipBy(deltaMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        boundPlaybackService?.setPlaybackSpeed(speed)
        viewModelScope.launch { settingsRepository.setDefaultPlaybackSpeed(speed) }
    }

    fun setPlaybackBoostDb(db: Int) {
        boundPlaybackService?.setBoostDb(db)
    }

    fun togglePlaybackAbLoop() {
        boundPlaybackService?.toggleAbLoop()
    }

    fun clearPlaybackAbLoop() {
        boundPlaybackService?.clearAbLoop()
    }

    fun dismissMiniPlayer() {
        boundPlaybackService?.stopAndDismiss()
    }

    fun saveSessionToMusicFolder(session: RecordingSession) {
        saveSessionToPublicStorage(session, com.example.model.PublicExportDestination.MUSIC)
    }

    fun saveSessionToPublicStorage(
        session: RecordingSession,
        destination: com.example.model.PublicExportDestination
    ) {
        if (_isExporting.value) return
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val savedName = sessionRepository.saveSessionToPublicStorage(session, destination)
                if (savedName != null) {
                    refreshStorageSummary()
                    _snackbarMessage.emit("Saved to ${destination.folderLabel}/$savedName")
                } else {
                    _snackbarMessage.emit("No spoken clips available to save in this recording.")
                }
            } catch (e: Exception) {
                _snackbarMessage.emit(e.localizedMessage ?: "Could not save recording to storage.")
            } finally {
                _isExporting.value = false
            }
        }
    }

    // --- Export Audio, Export .txt Notes, Trim/Split, Rule-Based Transcription ---

    fun exportAndShareSession(session: RecordingSession) {
        if (_isExporting.value) return
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val exported = sessionRepository.exportSessionForShare(session)
                if (exported != null) {
                    _shareEvent.emit(
                        ShareExportEvent(
                            uri = exported.first,
                            mimeType = exported.second,
                            title = session.title
                        )
                    )
                    refreshStorageSummary()
                } else {
                    _snackbarMessage.emit("No spoken clips available to share in this recording.")
                }
            } catch (e: Exception) {
                _snackbarMessage.emit(e.localizedMessage ?: "Could not prepare audio export.")
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun exportAndShareNotes(session: RecordingSession) {
        if (_isExporting.value) return
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val exported = sessionRepository.exportNotesAsTxt(session)
                if (exported != null) {
                    _shareEvent.emit(
                        ShareExportEvent(
                            uri = exported.first,
                            mimeType = exported.second,
                            title = "${session.title} Notes"
                        )
                    )
                    refreshStorageSummary()
                } else {
                    _snackbarMessage.emit("Could not export notes.")
                }
            } catch (e: Exception) {
                _snackbarMessage.emit(e.localizedMessage ?: "Could not export notes.")
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun trimSession(
        session: RecordingSession,
        startMs: Long,
        endMs: Long,
        saveAsNewCopy: Boolean,
        onComplete: (RecordingSession?) -> Unit = {}
    ) {
        if (_isEditingAudio.value) return
        boundPlaybackService?.pause()
        viewModelScope.launch {
            _isEditingAudio.value = true
            try {
                val result = sessionRepository.trimSession(session, startMs, endMs, saveAsNewCopy)
                refreshStorageSummary()
                if (result != null) {
                    if (!saveAsNewCopy && _playbackState.value.sessionId == session.id) {
                        boundPlaybackService?.stopAndDismiss()
                        boundPlaybackService?.loadSession(result, autoPlay = false)
                    }
                    _snackbarMessage.emit(
                        if (saveAsNewCopy) "Saved trimmed copy" else "Recording trimmed"
                    )
                } else {
                    _snackbarMessage.emit("Could not trim this recording.")
                }
                onComplete(result)
            } catch (e: Exception) {
                _snackbarMessage.emit(e.localizedMessage ?: "Audio trim failed.")
                onComplete(null)
            } finally {
                _isEditingAudio.value = false
            }
        }
    }

    fun splitSession(
        session: RecordingSession,
        splitMs: Long,
        keepOriginal: Boolean,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (_isEditingAudio.value) return
        boundPlaybackService?.pause()
        viewModelScope.launch {
            _isEditingAudio.value = true
            try {
                val result = sessionRepository.splitSession(session, splitMs, keepOriginal)
                refreshStorageSummary()
                if (result != null) {
                    _snackbarMessage.emit("Split into 2 recordings")
                    onComplete(true)
                } else {
                    _snackbarMessage.emit("Could not split this recording.")
                    onComplete(false)
                }
            } catch (e: Exception) {
                _snackbarMessage.emit(e.localizedMessage ?: "Audio split failed.")
                onComplete(false)
            } finally {
                _isEditingAudio.value = false
            }
        }
    }

    fun removeSilenceFromSession(
        session: RecordingSession,
        config: VoiceCleanConfig,
        saveAsNewCopy: Boolean,
        onComplete: (RecordingSession?) -> Unit = {}
    ) {
        if (_isEditingAudio.value) return
        boundPlaybackService?.pause()
        viewModelScope.launch {
            _isEditingAudio.value = true
            try {
                val result = sessionRepository.removeSilenceFromSession(
                    session = session,
                    config = config,
                    saveAsNewCopy = saveAsNewCopy
                )
                refreshStorageSummary()
                if (result != null) {
                    if (!saveAsNewCopy && _playbackState.value.sessionId == session.id) {
                        boundPlaybackService?.stopAndDismiss()
                        boundPlaybackService?.loadSession(result.session, autoPlay = false)
                    }
                    val removedMs = result.removedSilenceMillis
                    val floorDb = result.detectedNoiseFloorDb.toInt()
                    val peakDb = result.detectedProminentPeakDb.toInt()
                    val msg = when {
                        removedMs >= 1_000L && result.noiseReductionApplied ->
                            "Cleaned: removed ${FormatUtils.formatCompactDuration(removedMs)} + steady noise reduced (Floor ${floorDb} dB, Peak ${peakDb} dB)"
                        removedMs >= 1_000L ->
                            "Cleaned: removed ${FormatUtils.formatCompactDuration(removedMs)} of noise/distant audio (Floor ${floorDb} dB, Peak ${peakDb} dB)"
                        result.noiseReductionApplied ->
                            "Steady background noise reduced (Floor ${floorDb} dB, Peak ${peakDb} dB)"
                        else ->
                            "Audio already clean — prominent voice kept (Peak ${peakDb} dB)"
                    }
                    _snackbarMessage.emit(msg)
                    onComplete(result.session)
                } else {
                    _snackbarMessage.emit("No prominent voice detected above the cutoff. Try a gentler preset or slider.")
                    onComplete(null)
                }
            } catch (e: Exception) {
                _snackbarMessage.emit(e.localizedMessage ?: "Voice cleanup failed.")
                onComplete(null)
            } finally {
                _isEditingAudio.value = false
            }
        }
    }

    fun transcribeAndSummarize(session: RecordingSession) {
        if (_processingSessionId.value != null) return
        viewModelScope.launch {
            _processingSessionId.value = session.id
            _processingStage.value = "transcribing"
            try {
                val notes = speechProcessor.transcribeAndSummarize(session) { stage ->
                    _processingStage.value = stage
                }
                sessionRepository.saveSessionNotes(session.id, notes)
            } catch (e: Exception) {
                _snackbarMessage.emit(e.localizedMessage ?: "Could not analyze recording.")
            } finally {
                _processingSessionId.value = null
                _processingStage.value = null
            }
        }
    }

    fun saveEditedTranscriptAndSummarize(session: RecordingSession, editedTranscript: String) {
        viewModelScope.launch {
            val notes = speechProcessor.summarizeEditedTranscript(session, editedTranscript)
            sessionRepository.saveSessionNotes(session.id, notes)
            _snackbarMessage.emit("Transcript saved and summary updated.")
        }
    }

    fun notifyMessage(message: String) {
        _snackbarMessage.tryEmit(message)
    }
}
