package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AsyncChunkDiskWriter
import com.example.audio.EnergyVadDetector
import com.example.audio.PreRollRingBuffer
import com.example.audio.VadDetector
import com.example.audio.VoxAction
import com.example.audio.VoxState
import com.example.audio.VoxStateMachine
import com.example.data.ClearVoxGraph
import com.example.data.SettingsRepository
import com.example.model.AudioFormatOption
import com.example.model.BatteryOptimizationProfile
import com.example.model.RecordingMode
import com.example.model.RecordingSchedule
import com.example.model.RecordingSession
import com.example.model.UserSettings
import com.example.storage.ScheduleRepository
import com.example.storage.SessionRepository
import com.example.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class RecorderStatus {
    READY,
    ARMED_STANDBY,
    LISTENING,
    RECORDING,
    PAUSED_MANUAL,
    PAUSED_CALL,
    LOW_STORAGE_STOPPED,
    LOW_BATTERY_STOPPED
}

data class RecorderServiceState(
    val status: RecorderStatus = RecorderStatus.READY,
    val isSessionActive: Boolean = false,
    val isPausedManual: Boolean = false,
    val sessionElapsedMillis: Long = 0L,
    val speechCapturedMillis: Long = 0L,
    val clipCount: Int = 0,
    val bookmarkCount: Int = 0,
    val audioLevel: Float = 0f,
    val isNoiseRejected: Boolean = false,
    val activeFormat: AudioFormatOption = AudioFormatOption.OGG_OPUS,
    val activeRecordingMode: RecordingMode = RecordingMode.VOICE_ACTIVATED,
    val autoStopRemainingMillis: Long? = null,
    val silenceCountdownMillis: Long? = null,
    val armedScheduleLabel: String? = null,
    val armedStartTimeMillis: Long? = null,
    val scheduledStopTimeMillis: Long? = null,
    val errorMessage: String? = null
)

/**
 * Service-owned Voice-Activated (VOX) and Continuous Normal Audio Recording engine.
 *
 * Offloads all chunk writes, MP3 encoding, flash `fsync()`, and lossless ADTS->M4A remuxing to
 * [AsyncChunkDiskWriter]. Supports manual Pause/Resume, one-tap Mark Moment bookmarks, duration
 * Auto-Stop timers, prolonged-silence Session Auto-Stop (in both VOX and Normal modes), and
 * Armed Standby for hands-free scheduled recordings under Android 14–16 privacy rules.
 */
class VoxRecordingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VoxRecordingService = this@VoxRecordingService
    }

    private enum class StopReason {
        MANUAL,
        LOW_STORAGE,
        LOW_BATTERY,
        AUTO_STOP_TIMER,
        SILENCE_AUTO_STOP,
        SCHEDULED_END,
        AUDIO_INIT_FAILED,
        SHUTDOWN
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private val _state = MutableStateFlow(RecorderServiceState())
    val state: StateFlow<RecorderServiceState> = _state.asStateFlow()

    private val isCapturing = AtomicBoolean(false)
    private val isArmedStandby = AtomicBoolean(false)
    private val isPausedForFocus = AtomicBoolean(false)
    private val isPausedManual = AtomicBoolean(false)
    private val continuousChunkOpened = AtomicBoolean(false)

    private var captureThread: Thread? = null
    private var tickerJob: Job? = null
    private var standbyJob: Job? = null
    private var settingsJob: Job? = null
    private var diskWriter: AsyncChunkDiskWriter? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private val vadDetector: VadDetector = EnergyVadDetector()
    private val preRollBuffer = PreRollRingBuffer(SAMPLE_RATE, PRE_ROLL_SECONDS)
    private val stateMachine = VoxStateMachine()
    private val sessionLock = Any()

    @Volatile
    private var currentSession: RecordingSession? = null
    @Volatile
    private var activeChunkIndex: Int = 0
    @Volatile
    private var sessionStartWallClock: Long = 0L
    @Volatile
    private var activeRecordingDurationAccumMs: Long = 0L
    @Volatile
    private var lastActiveTickWallClock: Long = 0L

    private val completedSpeechMillis = AtomicLong(0L)
    private val completedClipCount = AtomicInteger(0)
    private val bookmarkCount = AtomicInteger(0)

    @Volatile
    private var activeFormat: AudioFormatOption = AudioFormatOption.OGG_OPUS
    @Volatile
    private var activeRecordingMode: RecordingMode = RecordingMode.VOICE_ACTIVATED
    @Volatile
    private var activeAutoStopDurationMillis: Long? = null
    @Volatile
    private var activeSilenceAutoStopMillis: Long = 0L
    @Volatile
    private var activeSilenceStopThreshold: Float = 0.22f
    private val continuousSilenceMillis = AtomicLong(0L)

    @Volatile
    private var activeBatteryProfile: BatteryOptimizationProfile = BatteryOptimizationProfile.ADAPTIVE_ECO
    @Volatile
    private var activeLowBatteryStopPercent: Int = 5
    @Volatile
    private var lastLowBatteryPercent: Int = 5
    private val isScreenInteractive = AtomicBoolean(true)
    private var powerReceiverRegistered = false

    @Volatile
    private var armedSchedule: RecordingSchedule? = null
    @Volatile
    private var activeScheduledId: String? = null
    @Volatile
    private var activeScheduledStopMillis: Long? = null

    private val powerAndScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive.set(true)
                    if (isCapturing.get()) {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(_state.value))
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive.set(false)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    if (!isCapturing.get()) return
                    val threshold = activeLowBatteryStopPercent
                    if (threshold <= 0) return
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    if (plugged != 0) return // Never stop while charging
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    if (level < 0) return
                    val pct = (level * 100) / scale
                    if (pct in 1..threshold) {
                        lastLowBatteryPercent = pct
                        stopSession(StopReason.LOW_BATTERY)
                    }
                }
                Intent.ACTION_SHUTDOWN,
                "android.intent.action.QUICKBOOT_POWEROFF" -> {
                    if (isCapturing.get()) {
                        stopSession(StopReason.SHUTDOWN)
                    } else if (isArmedStandby.get()) {
                        disarmStandbyInternal(stopForeground = true)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = ClearVoxGraph.settingsRepository(applicationContext)
        sessionRepository = ClearVoxGraph.sessionRepository(applicationContext)
        scheduleRepository = ClearVoxGraph.scheduleRepository(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenInteractive.set(pm.isInteractive)
        createNotificationChannels()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                powerAndScreenReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            powerReceiverRegistered = true
        }

        settingsJob = serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                vadDetector.updateSensitivity(settings.voiceSensitivity)
                stateMachine.updateConfig(
                    silenceDelaySeconds = settings.silenceDelaySeconds,
                    sensitivity = settings.voiceSensitivity
                )
                activeSilenceStopThreshold = settings.voiceSensitivity.speechStopThreshold
                activeSilenceAutoStopMillis = if (settings.silenceAutoStopMinutes > 0) {
                    settings.silenceAutoStopMinutes * 60_000L
                } else {
                    0L
                }
                activeBatteryProfile = settings.batteryProfile
                activeLowBatteryStopPercent = settings.lowBatteryStopPercent
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!promoteMicForeground(buildNotification(_state.value))) {
                    _state.value = _state.value.copy(
                        status = RecorderStatus.READY,
                        isSessionActive = false,
                        errorMessage = "Could not start background microphone service."
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSessionFromVisibleUi()
            }
            ACTION_PAUSE -> pauseManual()
            ACTION_RESUME -> resumeManual()
            ACTION_STOP -> {
                val schedId = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID)
                if (schedId != null && schedId == activeScheduledId) {
                    stopSession(StopReason.SCHEDULED_END)
                } else if (isArmedStandby.get()) {
                    disarmStandby()
                } else {
                    stopSession(StopReason.MANUAL)
                }
            }
            ACTION_DISARM_SCHEDULE -> disarmStandby()
            ACTION_ARM_SCHEDULE -> {
                val promoted = promoteMicForeground(buildNotification(_state.value))
                if (!promoted) {
                    _state.value = _state.value.copy(
                        status = RecorderStatus.READY,
                        isSessionActive = false,
                        errorMessage = "Could not arm standby mode."
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                val scheduleId = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID)
                val cached = scheduleRepository.schedules.value.firstOrNull { it.id == scheduleId }
                if (cached != null) {
                    armForSchedule(cached)
                } else if (scheduleId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        scheduleRepository.loadAndRescheduleAll()
                        val loaded = scheduleRepository.schedules.value.firstOrNull { it.id == scheduleId }
                        if (loaded != null) {
                            armForSchedule(loaded)
                        } else {
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                } else {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_SCHEDULE_TRIGGER -> {
                val scheduleId = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID)
                val label = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_LABEL)
                val endMs = intent.getLongExtra(ScheduleAlarmReceiver.EXTRA_END_TIME_MILLIS, 0L)
                val modeId = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_MODE_ID)
                if (!promoteMicForeground(buildNotification(_state.value))) {
                    ScheduleAlarmReceiver.postUrgentStartNotification(
                        context = this,
                        scheduleId = scheduleId ?: "scheduled",
                        label = label ?: "Scheduled Recording",
                        endTimeMillis = endMs,
                        modeId = modeId
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                startScheduledSession(
                    scheduleId = scheduleId,
                    title = label,
                    endTimeMillis = endMs.takeIf { it > System.currentTimeMillis() },
                    modeOverride = modeId?.let { RecordingMode.fromId(it) }
                )
            }
        }
        return START_STICKY
    }

    fun startSessionFromVisibleUi() {
        if (isCapturing.get()) return
        if (isArmedStandby.get()) {
            disarmStandbyInternal(stopForeground = false)
        }

        if (!hasMicPermission()) {
            _state.value = _state.value.copy(
                status = RecorderStatus.READY,
                isSessionActive = false,
                errorMessage = getString(R.string.hint_permission_needed)
            )
            stopForegroundCompat()
            stopSelf()
            return
        }

        if (!hasEnoughStorage()) {
            postAlertNotification(
                title = getString(R.string.notification_low_storage_title),
                body = getString(R.string.notification_low_storage_body)
            )
            _state.value = _state.value.copy(
                status = RecorderStatus.LOW_STORAGE_STOPPED,
                isSessionActive = false,
                errorMessage = getString(R.string.status_low_storage)
            )
            stopForegroundCompat()
            stopSelf()
            return
        }

        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            launchCaptureSession(
                settings = settings,
                modeOverride = null,
                customTitle = null,
                scheduledId = null,
                scheduledStopMillis = null
            )
        }
    }

    fun startScheduledSession(
        scheduleId: String?,
        title: String?,
        endTimeMillis: Long?,
        modeOverride: RecordingMode?
    ) {
        if (isCapturing.get()) return
        if (isArmedStandby.get()) {
            disarmStandbyInternal(stopForeground = false)
        }
        if (!hasMicPermission() || !hasEnoughStorage()) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            launchCaptureSession(
                settings = settings,
                modeOverride = modeOverride,
                customTitle = title,
                scheduledId = scheduleId,
                scheduledStopMillis = endTimeMillis
            )
        }
    }

    /**
     * Enters zero-write Armed Standby foreground mode while the app is visible so Android 14–16
     * allows the microphone session to begin completely hands-free at the scheduled start time.
     */
    fun armForSchedule(schedule: RecordingSchedule) {
        if (isCapturing.get()) return
        if (!hasMicPermission()) {
            _state.value = _state.value.copy(errorMessage = getString(R.string.hint_permission_needed))
            stopForegroundCompat()
            stopSelf()
            return
        }
        armedSchedule = schedule
        isArmedStandby.set(true)

        val armedState = RecorderServiceState(
            status = RecorderStatus.ARMED_STANDBY,
            isSessionActive = false,
            activeRecordingMode = schedule.recordingMode,
            armedScheduleLabel = schedule.label,
            armedStartTimeMillis = schedule.startTimeMillis,
            scheduledStopTimeMillis = schedule.endTimeMillis
        )
        _state.value = armedState

        val promoted = promoteMicForeground(buildNotification(armedState))
        if (!promoted) {
            isArmedStandby.set(false)
            armedSchedule = null
            _state.value = RecorderServiceState(
                status = RecorderStatus.READY,
                errorMessage = "Could not arm standby mode."
            )
            stopSelf()
            return
        }

        // Deep-Sleep Armed Standby: do NOT hold a continuous CPU wake lock while waiting.
        // AlarmManager wakes the device right on time; this lightweight 60s check acts as backup.
        releaseWakeLock()
        standbyJob?.cancel()
        standbyJob = serviceScope.launch {
            while (isArmedStandby.get()) {
                val target = armedSchedule ?: break
                val now = System.currentTimeMillis()
                if (now >= target.startTimeMillis) {
                    if (now < target.endTimeMillis) {
                        val settings = settingsRepository.settingsFlow.first()
                        isArmedStandby.set(false)
                        armedSchedule = null
                        launchCaptureSession(
                            settings = settings,
                            modeOverride = target.recordingMode,
                            customTitle = target.label,
                            scheduledId = target.id,
                            scheduledStopMillis = target.endTimeMillis
                        )
                    } else {
                        scheduleRepository.markScheduleCompleted(target.id)
                        disarmStandby()
                    }
                    break
                }
                delay(60_000L)
            }
        }
    }

    fun disarmStandby() {
        disarmStandbyInternal(stopForeground = true)
    }

    private fun disarmStandbyInternal(stopForeground: Boolean) {
        isArmedStandby.set(false)
        armedSchedule = null
        standbyJob?.cancel()
        standbyJob = null
        if (!isCapturing.get()) {
            releaseWakeLock()
            _state.value = RecorderServiceState(
                status = RecorderStatus.READY,
                isSessionActive = false,
                activeFormat = activeFormat,
                activeRecordingMode = activeRecordingMode
            )
            if (stopForeground) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun launchCaptureSession(
        settings: UserSettings,
        modeOverride: RecordingMode?,
        customTitle: String?,
        scheduledId: String?,
        scheduledStopMillis: Long?
    ) {
        if (!isCapturing.compareAndSet(false, true)) return

        activeFormat = settings.audioFormat
        activeRecordingMode = modeOverride ?: settings.recordingMode
        activeAutoStopDurationMillis = if (scheduledStopMillis != null) {
            null
        } else if (settings.autoStopMinutes > 0) {
            settings.autoStopMinutes * 60_000L
        } else {
            null
        }
        activeSilenceAutoStopMillis = if (settings.silenceAutoStopMinutes > 0) {
            settings.silenceAutoStopMinutes * 60_000L
        } else {
            0L
        }
        activeSilenceStopThreshold = settings.voiceSensitivity.speechStopThreshold
        activeBatteryProfile = settings.batteryProfile
        activeLowBatteryStopPercent = settings.lowBatteryStopPercent
        activeScheduledId = scheduledId
        activeScheduledStopMillis = scheduledStopMillis

        vadDetector.reset()
        vadDetector.updateSensitivity(settings.voiceSensitivity)
        preRollBuffer.clear()
        stateMachine.updateConfig(settings.silenceDelaySeconds, settings.voiceSensitivity)
        stateMachine.startListening()

        sessionStartWallClock = System.currentTimeMillis()
        lastActiveTickWallClock = sessionStartWallClock
        activeRecordingDurationAccumMs = 0L
        completedSpeechMillis.set(0L)
        completedClipCount.set(0)
        bookmarkCount.set(0)
        continuousSilenceMillis.set(0L)
        activeChunkIndex = 0
        isPausedForFocus.set(false)
        isPausedManual.set(false)
        continuousChunkOpened.set(false)

        synchronized(sessionLock) {
            currentSession = sessionRepository.createSession(
                format = activeFormat,
                recordingMode = activeRecordingMode,
                startTimeMillis = sessionStartWallClock,
                customTitle = customTitle
            )
        }

        val writer = AsyncChunkDiskWriter(
            sampleRate = SAMPLE_RATE,
            channels = 1,
            onChunkFinalized = { chunk ->
                synchronized(sessionLock) {
                    currentSession?.let { session ->
                        currentSession = sessionRepository.appendChunk(session, chunk)
                    }
                }
                completedSpeechMillis.addAndGet(chunk.durationMillis)
                completedClipCount.incrementAndGet()
            }
        )
        writer.start()
        diskWriter = writer

        val initialStatus = if (activeRecordingMode == RecordingMode.CONTINUOUS) {
            RecorderStatus.RECORDING
        } else {
            RecorderStatus.LISTENING
        }

        val initialStopRemaining = when {
            scheduledStopMillis != null -> (scheduledStopMillis - sessionStartWallClock).coerceAtLeast(0L)
            activeAutoStopDurationMillis != null -> activeAutoStopDurationMillis
            else -> null
        }

        val initialState = RecorderServiceState(
            status = initialStatus,
            isSessionActive = true,
            isPausedManual = false,
            sessionElapsedMillis = 0L,
            speechCapturedMillis = 0L,
            clipCount = 0,
            bookmarkCount = 0,
            audioLevel = 0f,
            activeFormat = activeFormat,
            activeRecordingMode = activeRecordingMode,
            autoStopRemainingMillis = initialStopRemaining,
            silenceCountdownMillis = null,
            scheduledStopTimeMillis = scheduledStopMillis,
            errorMessage = null
        )
        _state.value = initialState

        if (!promoteMicForeground(buildNotification(initialState))) {
            isCapturing.set(false)
            diskWriter?.drainAndShutdown()
            diskWriter = null
            _state.value = RecorderServiceState(
                status = RecorderStatus.READY,
                isSessionActive = false,
                errorMessage = "Could not start background microphone service."
            )
            stopSelf()
            return
        }

        registerAudioDeviceWatcher()
        acquireWakeLock()
        requestAudioFocus()
        startTicker()
        startAudioThread()
    }

    fun pauseManual() {
        if (!isCapturing.get()) return
        if (!isPausedManual.compareAndSet(false, true)) return

        continuousChunkOpened.set(false)
        diskWriter?.enqueueFinalizeChunk()
        stateMachine.startListening()
        preRollBuffer.clear()
        continuousSilenceMillis.set(0L)

        val paused = _state.value.copy(
            status = RecorderStatus.PAUSED_MANUAL,
            isPausedManual = true,
            audioLevel = 0f,
            silenceCountdownMillis = null
        )
        _state.value = paused
        notificationManager.notify(NOTIFICATION_ID, buildNotification(paused))
    }

    fun resumeManual() {
        if (!isCapturing.get()) return
        if (!isPausedManual.compareAndSet(true, false)) return

        vadDetector.reset()
        preRollBuffer.clear()
        stateMachine.startListening()
        continuousChunkOpened.set(false)
        continuousSilenceMillis.set(0L)
        lastActiveTickWallClock = System.currentTimeMillis()

        val resumedStatus = when {
            isPausedForFocus.get() -> RecorderStatus.PAUSED_CALL
            activeRecordingMode == RecordingMode.CONTINUOUS -> RecorderStatus.RECORDING
            else -> RecorderStatus.LISTENING
        }
        val resumed = _state.value.copy(
            status = resumedStatus,
            isPausedManual = false
        )
        _state.value = resumed
        notificationManager.notify(NOTIFICATION_ID, buildNotification(resumed))
    }

    fun addBookmark(label: String = ""): Boolean {
        if (!isCapturing.get()) return false
        val sessionId = synchronized(sessionLock) { currentSession?.id } ?: return false
        val playableOffsetMs = completedSpeechMillis.get() + (diskWriter?.currentChunkDurationMillis ?: 0L)
        val created = sessionRepository.addBookmark(
            sessionId = sessionId,
            offsetFromSessionStartMillis = playableOffsetMs,
            label = label
        )
        if (created != null) {
            val newCount = bookmarkCount.incrementAndGet()
            _state.value = _state.value.copy(bookmarkCount = newCount)
            return true
        }
        return false
    }

    fun stopSession(lowStorage: Boolean = false) {
        stopSession(if (lowStorage) StopReason.LOW_STORAGE else StopReason.MANUAL)
    }

    private fun stopSession(reason: StopReason) {
        if (!isCapturing.getAndSet(false)) {
            if (reason == StopReason.MANUAL) {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }

        isPausedManual.set(false)
        continuousChunkOpened.set(false)
        tickerJob?.cancel()
        tickerJob = null

        val threadToJoin = captureThread
        captureThread = null
        val writerToClose = diskWriter
        diskWriter = null
        val finishedScheduleId = activeScheduledId
        activeScheduledId = null
        activeScheduledStopMillis = null

        serviceScope.launch(Dispatchers.IO) {
            runCatching { threadToJoin?.join(2500L) }
            writerToClose?.drainAndShutdown(6_000L)

            synchronized(sessionLock) {
                currentSession?.let { session ->
                    sessionRepository.finalizeSession(session)
                }
                currentSession = null
            }

            if (finishedScheduleId != null) {
                scheduleRepository.markScheduleCompleted(finishedScheduleId)
            }

            unregisterAudioDeviceWatcher()
            abandonAudioFocus()
            releaseWakeLock()

            when (reason) {
                StopReason.LOW_STORAGE -> postAlertNotification(
                    title = getString(R.string.notification_low_storage_title),
                    body = getString(R.string.notification_low_storage_body)
                )
                StopReason.LOW_BATTERY -> postAlertNotification(
                    title = getString(R.string.notification_low_battery_title),
                    body = getString(R.string.notification_low_battery_body, lastLowBatteryPercent)
                )
                StopReason.AUTO_STOP_TIMER -> postAlertNotification(
                    title = getString(R.string.notification_autostop_title),
                    body = getString(R.string.notification_autostop_body)
                )
                StopReason.SILENCE_AUTO_STOP -> {
                    val mins = (activeSilenceAutoStopMillis / 60_000L).coerceAtLeast(1L)
                    postAlertNotification(
                        title = getString(R.string.notification_silence_stop_title),
                        body = getString(R.string.notification_silence_stop_body, mins.toInt())
                    )
                }
                StopReason.SCHEDULED_END -> postAlertNotification(
                    title = getString(R.string.notification_schedule_done_title),
                    body = getString(R.string.notification_schedule_done_body)
                )
                StopReason.MANUAL, StopReason.AUDIO_INIT_FAILED, StopReason.SHUTDOWN -> Unit
            }

            _state.value = RecorderServiceState(
                status = when (reason) {
                    StopReason.LOW_STORAGE -> RecorderStatus.LOW_STORAGE_STOPPED
                    StopReason.LOW_BATTERY -> RecorderStatus.LOW_BATTERY_STOPPED
                    else -> RecorderStatus.READY
                },
                isSessionActive = false,
                isPausedManual = false,
                sessionElapsedMillis = 0L,
                speechCapturedMillis = completedSpeechMillis.get(),
                clipCount = completedClipCount.get(),
                bookmarkCount = bookmarkCount.get(),
                audioLevel = 0f,
                activeFormat = activeFormat,
                activeRecordingMode = activeRecordingMode,
                errorMessage = when (reason) {
                    StopReason.LOW_STORAGE -> getString(R.string.status_low_storage)
                    StopReason.LOW_BATTERY -> getString(R.string.status_low_battery)
                    StopReason.AUDIO_INIT_FAILED -> "Could not access microphone. Another app may be using it."
                    else -> null
                }
            )

            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            var tickCount = 0
            while (isCapturing.get()) {
                val now = System.currentTimeMillis()
                val paused = isPausedManual.get() || isPausedForFocus.get()
                if (!paused) {
                    val delta = (now - lastActiveTickWallClock).coerceIn(0L, 5_000L)
                    activeRecordingDurationAccumMs += delta
                }
                lastActiveTickWallClock = now

                val elapsed = (now - sessionStartWallClock).coerceAtLeast(0L)
                val activeChunkDur = diskWriter?.currentChunkDurationMillis ?: 0L
                val speechTotal = completedSpeechMillis.get() + activeChunkDur
                val totalClips = completedClipCount.get() + if (diskWriter?.isChunkOpen == true) 1 else 0

                // Check scheduled end wall-clock time first
                val schedStop = activeScheduledStopMillis
                if (schedStop != null && now >= schedStop) {
                    stopSession(StopReason.SCHEDULED_END)
                    break
                }

                // Check auto-stop duration timer (pauses while user manually pauses)
                val autoStopLimit = activeAutoStopDurationMillis
                if (autoStopLimit != null && activeRecordingDurationAccumMs >= autoStopLimit) {
                    stopSession(StopReason.AUTO_STOP_TIMER)
                    break
                }

                val remainingAutoStop = when {
                    schedStop != null -> (schedStop - now).coerceAtLeast(0L)
                    autoStopLimit != null -> (autoStopLimit - activeRecordingDurationAccumMs).coerceAtLeast(0L)
                    else -> null
                }

                val silenceLimit = activeSilenceAutoStopMillis
                val silenceElapsed = continuousSilenceMillis.get()
                val silenceRemaining = if (!paused && silenceLimit > 0L && silenceElapsed >= 3_000L) {
                    (silenceLimit - silenceElapsed).coerceAtLeast(0L)
                } else {
                    null
                }

                val screenOn = isScreenInteractive.get()
                val profile = activeBatteryProfile
                val shouldPushUiState = screenOn || profile == BatteryOptimizationProfile.BALANCED || tickCount % 5 == 0
                val notifInterval = if (screenOn) 2 else profile.screenOffNotifIntervalSeconds.coerceAtLeast(2)

                if (shouldPushUiState || tickCount % notifInterval == 0) {
                    val current = _state.value.copy(
                        sessionElapsedMillis = elapsed,
                        speechCapturedMillis = speechTotal,
                        clipCount = totalClips,
                        bookmarkCount = bookmarkCount.get(),
                        autoStopRemainingMillis = remainingAutoStop,
                        silenceCountdownMillis = silenceRemaining
                    )
                    _state.value = current
                    if (tickCount % notifInterval == 0) {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(current))
                    }
                }
                tickCount++
                delay(1000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioThread() {
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var audioRecord: AudioRecord? = null
            var agc: AutomaticGainControl? = null
            var ns: NoiseSuppressor? = null

            try {
                audioRecord = buildOptimalAudioRecord()
                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    stopSession(StopReason.AUDIO_INIT_FAILED)
                    return@Thread
                }

                val sessionId = audioRecord.audioSessionId
                if (AutomaticGainControl.isAvailable()) {
                    runCatching {
                        agc = AutomaticGainControl.create(sessionId)
                        agc?.enabled = false
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    runCatching {
                        ns = NoiseSuppressor.create(sessionId)
                        ns?.enabled = false
                    }
                }

                audioRecord.startRecording()

                val readBuffer = ShortArray(MAX_BATCH_SAMPLES)
                val frameBuffer = ShortArray(FRAME_SAMPLES)
                var framesSincePeriodicSync = 0
                var framesSinceLevelPush = 0
                var continuousChunkElapsedMs = 0L
                var smoothedLevel = 0f
                var noiseRejectedHoldFrames = 0

                while (isCapturing.get()) {
                    if (isPausedForFocus.get() || isPausedManual.get()) {
                        Thread.sleep(80L)
                        continue
                    }

                    val profile = activeBatteryProfile
                    val screenOn = isScreenInteractive.get()
                    val isActivelyWriting = activeRecordingMode == RecordingMode.CONTINUOUS ||
                        diskWriter?.isChunkOpen == true
                    val targetBatchSamples = when (profile) {
                        BatteryOptimizationProfile.BALANCED -> FRAME_SAMPLES // 32 ms
                        BatteryOptimizationProfile.ADAPTIVE_ECO -> when {
                            screenOn -> FRAME_SAMPLES // Smooth 32 ms live meter while screen is on
                            isActivelyWriting -> FRAME_SAMPLES * 2 // 64 ms batch while speaking screen-off
                            else -> MAX_BATCH_SAMPLES // 128 ms batch while quietly listening screen-off
                        }
                        BatteryOptimizationProfile.ULTRA_SAVER -> {
                            if (isActivelyWriting) FRAME_SAMPLES * 2 else MAX_BATCH_SAMPLES
                        }
                    }

                    val totalRead = audioRecord.read(
                        readBuffer,
                        0,
                        targetBatchSamples,
                        AudioRecord.READ_BLOCKING
                    )
                    if (totalRead <= 0) {
                        if (totalRead == AudioRecord.ERROR_DEAD_OBJECT) break
                        continue
                    }

                    val syncEveryFrames = ((profile.syncIntervalSeconds * 1000L) / 32L)
                        .toInt()
                        .coerceAtLeast(150)

                    // Process read batch in exact 512-sample (32 ms) sub-frames so VAD, pre-roll,
                    // and hysteresis timing stay 100% identical while cutting kernel CPU wakeups!
                    var batchOffset = 0
                    var stopLoop = false
                    while (batchOffset < totalRead && isCapturing.get() && !stopLoop) {
                        val read = minOf(FRAME_SAMPLES, totalRead - batchOffset)
                        System.arraycopy(readBuffer, batchOffset, frameBuffer, 0, read)
                        batchOffset += read

                        val vad = vadDetector.processFrame(frameBuffer, read)
                        smoothedLevel = smoothedLevel * 0.65f + vad.normalizedLevel * 0.35f
                        if (vad.isSpeech) {
                            noiseRejectedHoldFrames = 0
                        } else if (vad.isNoiseRejected) {
                            noiseRejectedHoldFrames = 18 // ~576 ms smooth UI hold
                        } else if (noiseRejectedHoldFrames > 0) {
                            noiseRejectedHoldFrames--
                        }
                        val frameMs = (read * 1000L) / SAMPLE_RATE

                        // Prolonged-silence Session Auto-Stop (works in BOTH VOX & Continuous/Normal modes)
                        val silenceLimitMs = activeSilenceAutoStopMillis
                        if (silenceLimitMs > 0L) {
                            if (vad.speechProbability >= activeSilenceStopThreshold) {
                                continuousSilenceMillis.set(0L)
                            } else {
                                val totalSilent = continuousSilenceMillis.addAndGet(frameMs)
                                if (totalSilent >= silenceLimitMs) {
                                    stopSession(StopReason.SILENCE_AUTO_STOP)
                                    stopLoop = true
                                    break
                                }
                            }
                        } else {
                            continuousSilenceMillis.set(0L)
                        }

                        if (activeRecordingMode == RecordingMode.CONTINUOUS) {
                            val writer = diskWriter
                            if (!continuousChunkOpened.get() || writer == null) {
                                if (!hasEnoughStorage()) {
                                    stopSession(StopReason.LOW_STORAGE)
                                    stopLoop = true
                                    break
                                }
                                continuousChunkOpened.set(true)
                                enqueueOpenChunk(frameBuffer, read, withPreRoll = false)
                                framesSincePeriodicSync = 0
                                continuousChunkElapsedMs = frameMs
                            } else {
                                writer.enqueueWriteFrame(frameBuffer, read)
                                continuousChunkElapsedMs += frameMs
                                framesSincePeriodicSync++
                                if (framesSincePeriodicSync >= syncEveryFrames) {
                                    writer.enqueueSync()
                                    framesSincePeriodicSync = 0
                                }
                                if (continuousChunkElapsedMs >= MAX_CONTINUOUS_CHUNK_MS) {
                                    if (!hasEnoughStorage()) {
                                        stopSession(StopReason.LOW_STORAGE)
                                        stopLoop = true
                                        break
                                    }
                                    enqueueRotateChunk(frameBuffer, read)
                                    continuousChunkElapsedMs = 0L
                                    framesSincePeriodicSync = 0
                                }
                            }

                            val uiStatus = when {
                                isPausedManual.get() -> RecorderStatus.PAUSED_MANUAL
                                isPausedForFocus.get() -> RecorderStatus.PAUSED_CALL
                                else -> RecorderStatus.RECORDING
                            }

                            framesSinceLevelPush++
                            val shouldEmitLevel = screenOn || profile == BatteryOptimizationProfile.BALANCED
                            if (shouldEmitLevel && framesSinceLevelPush >= 2) {
                                framesSinceLevelPush = 0
                                val activeDur = diskWriter?.currentChunkDurationMillis ?: 0L
                                _state.value = _state.value.copy(
                                    status = uiStatus,
                                    audioLevel = smoothedLevel,
                                    speechCapturedMillis = completedSpeechMillis.get() + activeDur,
                                    clipCount = completedClipCount.get() + if (diskWriter?.isChunkOpen == true) 1 else 0
                                )
                            }
                        } else {
                            val prevVoxState = stateMachine.state
                            val action = stateMachine.onFrame(vad.speechProbability, frameMs)

                            when (action) {
                                VoxAction.KeepListening -> {
                                    preRollBuffer.write(frameBuffer, 0, read)
                                }

                                VoxAction.OpenChunkAndFlushPreRoll -> {
                                    if (!hasEnoughStorage()) {
                                        stopSession(StopReason.LOW_STORAGE)
                                        stopLoop = true
                                        break
                                    }
                                    enqueueOpenChunk(frameBuffer, read, withPreRoll = true)
                                    framesSincePeriodicSync = 0
                                }

                                VoxAction.WriteLiveFrame -> {
                                    diskWriter?.enqueueWriteFrame(frameBuffer, read)
                                    framesSincePeriodicSync++
                                    if (framesSincePeriodicSync >= syncEveryFrames) {
                                        diskWriter?.enqueueSync()
                                        framesSincePeriodicSync = 0
                                    }
                                }

                                VoxAction.RotateChunkAndWriteLiveFrame -> {
                                    if (!hasEnoughStorage()) {
                                        stopSession(StopReason.LOW_STORAGE)
                                        stopLoop = true
                                        break
                                    }
                                    enqueueRotateChunk(frameBuffer, read)
                                    framesSincePeriodicSync = 0
                                }

                                VoxAction.FinalizeChunk -> {
                                    diskWriter?.enqueueFinalizeChunk()
                                    preRollBuffer.clear()
                                    preRollBuffer.write(frameBuffer, 0, read)
                                    framesSincePeriodicSync = 0
                                }
                            }

                            val newVoxState = stateMachine.state
                            val stateChanged = newVoxState != prevVoxState
                            val uiStatus = when {
                                isPausedManual.get() -> RecorderStatus.PAUSED_MANUAL
                                isPausedForFocus.get() -> RecorderStatus.PAUSED_CALL
                                newVoxState == VoxState.RECORDING || newVoxState == VoxState.HANGOVER_SILENCE ->
                                    RecorderStatus.RECORDING
                                else -> RecorderStatus.LISTENING
                            }

                            framesSinceLevelPush++
                            val shouldEmitLevel = screenOn || profile == BatteryOptimizationProfile.BALANCED
                            if (stateChanged || (shouldEmitLevel && framesSinceLevelPush >= 2)) {
                                framesSinceLevelPush = 0
                                val activeDur = diskWriter?.currentChunkDurationMillis ?: 0L
                                val updated = _state.value.copy(
                                    status = uiStatus,
                                    audioLevel = if (screenOn) smoothedLevel else 0f,
                                    isNoiseRejected = screenOn && uiStatus == RecorderStatus.LISTENING && noiseRejectedHoldFrames > 0,
                                    speechCapturedMillis = completedSpeechMillis.get() + activeDur,
                                    clipCount = completedClipCount.get() + if (diskWriter?.isChunkOpen == true) 1 else 0
                                )
                                _state.value = updated
                                if (stateChanged) {
                                    notificationManager.notify(NOTIFICATION_ID, buildNotification(updated))
                                }
                            }
                        }
                    }
                    if (stopLoop) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture loop error", e)
            } finally {
                runCatching { agc?.release() }
                runCatching { ns?.release() }
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                diskWriter?.enqueueFinalizeChunk()
            }
        }, "NTK_VOX-AudioCapture")
        captureThread = thread
        thread.start()
    }

    private fun enqueueOpenChunk(liveFrame: ShortArray, liveLength: Int, withPreRoll: Boolean) {
        val session = synchronized(sessionLock) { currentSession } ?: return
        val dir = sessionRepository.getSessionDirectory(session.folderName)
        activeChunkIndex += 1
        val preRoll = if (withPreRoll) preRollBuffer.drainToArray() else ShortArray(0)
        val preRollMs = (preRoll.size * 1000L) / SAMPLE_RATE
        val startTime = (System.currentTimeMillis() - preRollMs).coerceAtLeast(sessionStartWallClock)

        diskWriter?.enqueueOpenChunk(
            directory = dir,
            chunkIndex = activeChunkIndex,
            format = activeFormat,
            startTimeMillis = startTime,
            preRollSamples = preRoll,
            firstFrame = liveFrame,
            firstFrameLength = liveLength
        )
    }

    private fun enqueueRotateChunk(liveFrame: ShortArray, liveLength: Int) {
        val session = synchronized(sessionLock) { currentSession } ?: return
        val dir = sessionRepository.getSessionDirectory(session.folderName)
        activeChunkIndex += 1
        diskWriter?.enqueueRotateChunk(
            directory = dir,
            nextChunkIndex = activeChunkIndex,
            format = activeFormat,
            startTimeMillis = System.currentTimeMillis(),
            firstFrame = liveFrame,
            firstFrameLength = liveLength
        )
    }

    @SuppressLint("MissingPermission")
    private fun buildOptimalAudioRecord(): AudioRecord? {
        val supportsUnprocessed =
            audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val primarySource = if (supportsUnprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2)
        val bufferSize = maxOf(minBuf * 4, MAX_BATCH_SAMPLES * 2 * 4)

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val sourcesToTry = if (primarySource == MediaRecorder.AudioSource.UNPROCESSED) {
            listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        } else {
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }

        for (source in sourcesToTry) {
            val record = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.getOrNull()

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                return record
            }
            runCatching { record?.release() }
        }
        return null
    }

    private fun promoteMicForeground(notification: Notification): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground microphone service", e)
            false
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (isCapturing.get() && isPausedForFocus.compareAndSet(false, true)) {
                        continuousChunkOpened.set(false)
                        diskWriter?.enqueueFinalizeChunk()
                        stateMachine.startListening()
                        preRollBuffer.clear()
                        continuousSilenceMillis.set(0L)
                        val pausedState = _state.value.copy(
                            status = RecorderStatus.PAUSED_CALL,
                            audioLevel = 0f
                        )
                        _state.value = pausedState
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(pausedState))
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (isCapturing.get() && isPausedForFocus.compareAndSet(true, false)) {
                        vadDetector.reset()
                        preRollBuffer.clear()
                        stateMachine.startListening()
                        continuousChunkOpened.set(false)
                        lastActiveTickWallClock = System.currentTimeMillis()
                        val resumedStatus = when {
                            isPausedManual.get() -> RecorderStatus.PAUSED_MANUAL
                            activeRecordingMode == RecordingMode.CONTINUOUS -> RecorderStatus.RECORDING
                            else -> RecorderStatus.LISTENING
                        }
                        val resumedState = _state.value.copy(status = resumedStatus)
                        _state.value = resumedState
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(resumedState))
                    }
                }
            }
        }

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(listener)
            .build()
        audioFocusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun registerAudioDeviceWatcher() {
        unregisterAudioDeviceWatcher()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val hasMic = addedDevices.any { it.isSource }
                if (hasMic) {
                    Log.i(TAG, "Audio input device added during active session")
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val hadMic = removedDevices.any { it.isSource }
                if (hadMic) {
                    Log.i(TAG, "Audio input device removed during active session; continuing on device mic")
                }
            }
        }
        audioDeviceCallback = cb
        runCatching { audioManager.registerAudioDeviceCallback(cb, null) }
    }

    private fun unregisterAudioDeviceWatcher() {
        audioDeviceCallback?.let { cb ->
            runCatching { audioManager.unregisterAudioDeviceCallback(cb) }
        }
        audioDeviceCallback = null
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { req ->
            runCatching { audioManager.abandonAudioFocusRequest(req) }
        }
        audioFocusRequest = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NTK_VOX::VoxRecordingWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun hasEnoughStorage(): Boolean {
        return try {
            val stat = StatFs(filesDir.absolutePath)
            stat.availableBytes >= MIN_FREE_STORAGE_BYTES
        } catch (_: Exception) {
            true
        }
    }

    private fun createNotificationChannels() {
        runCatching { notificationManager.deleteNotificationChannel("clearvox_recording_channel") }
        val recordingChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(recordingChannel)

        val alertsChannel = NotificationChannel(
            ScheduleAlarmReceiver.ALERTS_CHANNEL_ID,
            getString(R.string.notification_alerts_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_alerts_desc)
        }
        notificationManager.createNotificationChannel(alertsChannel)
    }

    private fun postAlertNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            50,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, ScheduleAlarmReceiver.ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        notificationManager.notify(alertNotificationCounter.getAndIncrement(), notif)
    }

    private fun buildNotification(state: RecorderServiceState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (state.status == RecorderStatus.ARMED_STANDBY) {
            val disarmIntent = Intent(this, VoxRecordingService::class.java).apply {
                action = ACTION_DISARM_SCHEDULE
            }
            val disarmPending = PendingIntent.getService(
                this,
                3,
                disarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val startText = state.armedStartTimeMillis?.let { FormatUtils.formatDateTimeShort(it) } ?: ""
            val body = "${state.armedScheduleLabel ?: "Scheduled"} • Starts $startText"

            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.notification_title_armed))
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPending)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.action_disarm_standby),
                    disarmPending
                )
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        val stopIntent = Intent(this, VoxRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (state.isPausedManual) ACTION_RESUME else ACTION_PAUSE
        val pauseResumeLabel = if (state.isPausedManual) {
            getString(R.string.action_resume)
        } else {
            getString(R.string.action_pause)
        }
        val pauseResumeIcon = if (state.isPausedManual) {
            android.R.drawable.ic_media_play
        } else {
            android.R.drawable.ic_media_pause
        }
        val pauseResumePending = PendingIntent.getService(
            this,
            2,
            Intent(this, VoxRecordingService::class.java).apply { action = pauseResumeAction },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state.status) {
            RecorderStatus.RECORDING -> if (state.activeRecordingMode == RecordingMode.CONTINUOUS) {
                getString(R.string.notification_title_recording_continuous)
            } else {
                getString(R.string.notification_title_recording)
            }
            RecorderStatus.PAUSED_MANUAL -> getString(R.string.notification_title_paused_manual)
            RecorderStatus.PAUSED_CALL -> getString(R.string.notification_title_paused)
            else -> getString(R.string.notification_title_listening)
        }

        val sessionText = FormatUtils.formatHms(state.sessionElapsedMillis)
        val speechText = FormatUtils.formatHms(state.speechCapturedMillis)
        val body = if (state.activeRecordingMode == RecordingMode.CONTINUOUS) {
            "Recorded $speechText"
        } else {
            "Session $sessionText • Speech $speechText"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .addAction(pauseResumeIcon, pauseResumeLabel, pauseResumePending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                stopPending
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        isCapturing.set(false)
        isArmedStandby.set(false)
        continuousChunkOpened.set(false)
        if (powerReceiverRegistered) {
            runCatching { unregisterReceiver(powerAndScreenReceiver) }
            powerReceiverRegistered = false
        }
        tickerJob?.cancel()
        standbyJob?.cancel()
        settingsJob?.cancel()
        diskWriter?.drainAndShutdown(2_000L)
        diskWriter = null
        unregisterAudioDeviceWatcher()
        abandonAudioFocus()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoxRecordingService"
        private const val CHANNEL_ID = "ntkvox_recording_channel"
        private const val NOTIFICATION_ID = 1001
        private val alertNotificationCounter = AtomicInteger(1012)

        const val ACTION_START = "com.example.ntkvox.ACTION_START"
        const val ACTION_STOP = "com.example.ntkvox.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.ntkvox.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.ntkvox.ACTION_RESUME"
        const val ACTION_DISARM_SCHEDULE = "com.example.ntkvox.ACTION_DISARM_SCHEDULE"
        const val ACTION_ARM_SCHEDULE = "com.example.ntkvox.ACTION_ARM_SCHEDULE"
        const val ACTION_SCHEDULE_TRIGGER = "com.example.ntkvox.ACTION_SCHEDULE_TRIGGER"

        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 512 // 32 ms @ 16 kHz
        private const val MAX_BATCH_SAMPLES = 2048 // 128 ms @ 16 kHz (screen-off Eco/Ultra listening)
        private const val PRE_ROLL_SECONDS = 2.0f
        private const val MAX_CONTINUOUS_CHUNK_MS = 15L * 60L * 1000L // 15 minutes per segment
        private const val MIN_FREE_STORAGE_BYTES = 100L * 1024L * 1024L // 100 MB
    }
}
