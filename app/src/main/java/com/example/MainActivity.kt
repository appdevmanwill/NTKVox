package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AppThemeMode
import com.example.model.RecordingCategory
import com.example.model.RecordingMode
import com.example.service.RecorderStatus
import com.example.service.ScheduleAlarmReceiver
import com.example.service.VoxPlaybackService
import com.example.service.VoxRecordingService
import com.example.ui.screens.BatteryCheckSheet
import com.example.ui.screens.CategoryPickerDialog
import com.example.ui.screens.MiniPlayerBar
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.RecordScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SchedulesSheet
import com.example.ui.screens.SessionPlayerScreen
import com.example.ui.screens.SettingsSheet
import com.example.ui.screens.TrashBinSheet
import com.example.ui.theme.ClearVoxTheme
import com.example.util.BatteryOptimizationHelper
import com.example.viewmodel.ClearVoxViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Thin UI client bound to [VoxRecordingService] and [VoxPlaybackService].
 * Both foreground services outlive screen-off and Activity destruction cleanly.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ClearVoxViewModel by viewModels()
    private var isRecorderBound = false
    private var isPlaybackBound = false
    private val pendingOpenSessionId = MutableStateFlow<String?>(null)

    private val recorderConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? VoxRecordingService.LocalBinder ?: return
            viewModel.attachService(localBinder.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.detachService()
        }
    }

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? VoxPlaybackService.LocalBinder ?: return
            viewModel.attachPlaybackService(localBinder.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.detachPlaybackService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()

            DisposableEffect(settings.keepScreenOn, recorderState.isSessionActive) {
                if (settings.keepScreenOn && recorderState.isSessionActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            ClearVoxTheme(themeMode = settings.themeMode) {
                ClearVoxApp(
                    viewModel = viewModel,
                    pendingOpenSessionIdFlow = pendingOpenSessionId
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val openSessionId = intent.getStringExtra(VoxPlaybackService.EXTRA_OPEN_SESSION_ID)
        if (!openSessionId.isNullOrBlank()) {
            pendingOpenSessionId.value = openSessionId
            intent.removeExtra(VoxPlaybackService.EXTRA_OPEN_SESSION_ID)
        }
        if (intent.getBooleanExtra(ScheduleAlarmReceiver.EXTRA_AUTO_START_FROM_SCHEDULE, false)) {
            intent.removeExtra(ScheduleAlarmReceiver.EXTRA_AUTO_START_FROM_SCHEDULE)
            val schedId = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID)
            val label = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_LABEL)
            val endMs = intent.getLongExtra(ScheduleAlarmReceiver.EXTRA_END_TIME_MILLIS, 0L)
            val modeId = intent.getStringExtra(ScheduleAlarmReceiver.EXTRA_MODE_ID)
            viewModel.startScheduledRecordingNow(
                scheduleId = schedId,
                label = label,
                endTimeMillis = endMs.takeIf { it > System.currentTimeMillis() },
                modeId = modeId
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val recIntent = Intent(this, VoxRecordingService::class.java)
        isRecorderBound = bindService(recIntent, recorderConnection, Context.BIND_AUTO_CREATE)

        val pbIntent = Intent(this, VoxPlaybackService::class.java)
        isPlaybackBound = bindService(pbIntent, playbackConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (isRecorderBound) {
            viewModel.detachService()
            runCatching { unbindService(recorderConnection) }
            isRecorderBound = false
        }
        if (isPlaybackBound) {
            viewModel.detachPlaybackService()
            runCatching { unbindService(playbackConnection) }
            isPlaybackBound = false
        }
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearVoxApp(
    viewModel: ClearVoxViewModel,
    pendingOpenSessionIdFlow: MutableStateFlow<String?>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val activeSessions by viewModel.activeSessions.collectAsStateWithLifecycle()
    val filteredSessions by viewModel.filteredSessions.collectAsStateWithLifecycle()
    val trashedSessions by viewModel.trashedSessions.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val selectedSessionIds by viewModel.selectedSessionIds.collectAsStateWithLifecycle()
    val batteryTelemetry by viewModel.batteryTelemetry.collectAsStateWithLifecycle()
    val recorderState by viewModel.recorderState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val storageSummary by viewModel.storageSummary.collectAsStateWithLifecycle()
    val processingSessionId by viewModel.processingSessionId.collectAsStateWithLifecycle()
    val processingStage by viewModel.processingStage.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val isEditingAudio by viewModel.isEditingAudio.collectAsStateWithLifecycle()
    val pendingOpenSessionId by pendingOpenSessionIdFlow.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Record, 1 = Recordings
    var activeSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showBatterySheet by rememberSaveable { mutableStateOf(false) }
    var showSchedulesSheet by rememberSaveable { mutableStateOf(false) }
    var showTrashSheet by rememberSaveable { mutableStateOf(false) }
    var showBatchCategoryDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pendingOpenSessionId) {
        val target = pendingOpenSessionId
        if (!target.isNullOrBlank()) {
            activeSessionId = target
            selectedTab = 1
            pendingOpenSessionIdFlow.value = null
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 1 && selectedSessionIds.isNotEmpty()) {
            viewModel.clearSessionSelection()
        }
    }

    fun checkMicGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun checkNotifGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    var hasMicPermission by remember { mutableStateOf(checkMicGranted()) }
    var hasNotifPermission by remember { mutableStateOf(checkNotifGranted()) }
    var canExactAlarms by remember { mutableStateOf(viewModel.canScheduleExactAlarms()) }
    var isBatteryUnrestricted by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = checkMicGranted()
                hasNotifPermission = checkNotifGranted()
                canExactAlarms = viewModel.canScheduleExactAlarms()
                isBatteryUnrestricted = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                viewModel.refreshStorageSummary()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMicPermission = checkMicGranted()
        hasNotifPermission = checkNotifGranted()
    }

    fun requestRequiredPermissions() {
        val toRequest = buildList {
            if (!checkMicGranted()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !checkNotifGranted()) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (toRequest.isNotEmpty()) {
            permissionsLauncher.launch(toRequest.toTypedArray())
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(viewModel) {
        viewModel.shareEvent.collect { event ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = event.mimeType
                putExtra(Intent.EXTRA_STREAM, event.uri)
                putExtra(Intent.EXTRA_SUBJECT, event.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, shareChooserTitle).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            runCatching { context.startActivity(chooser) }
        }
    }

    if (!settingsLoaded) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    if (!settings.onboardingCompleted) {
        OnboardingScreen(
            hasMicPermission = hasMicPermission,
            hasNotificationPermission = hasNotifPermission,
            isBatteryUnrestricted = isBatteryUnrestricted,
            onRequestPermissions = { requestRequiredPermissions() },
            onRequestBatteryUnrestricted = {
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
            },
            onComplete = { viewModel.completeOnboarding() }
        )
        return
    }

    val openSession = activeSessionId?.let { id -> activeSessions.firstOrNull { it.id == id } }
    if (openSession != null) {
        SessionPlayerScreen(
            session = openSession,
            sessionRepository = viewModel.sessionRepository,
            playbackState = playbackState,
            isProcessingNotes = processingSessionId == openSession.id,
            processingStage = processingStage,
            isExporting = isExporting,
            isEditingAudio = isEditingAudio,
            onLoadSession = { s -> viewModel.loadSessionForPlayback(s, autoPlay = false) },
            onTogglePlayPause = { viewModel.togglePlaybackForSession(openSession) },
            onSeekGlobal = { ms -> viewModel.seekPlaybackGlobal(ms) },
            onSeekToChunk = { idx -> viewModel.seekPlaybackToChunk(idx, autoPlay = true) },
            onSkipBy = { delta -> viewModel.skipPlaybackBy(delta) },
            onSelectSpeed = { speed -> viewModel.setPlaybackSpeed(speed) },
            onSelectBoostDb = { db -> viewModel.setPlaybackBoostDb(db) },
            onBack = { activeSessionId = null },
            onToggleFavorite = { viewModel.toggleFavorite(openSession.id) },
            onChangeCategory = { cat -> viewModel.updateCategory(openSession.id, cat) },
            onRename = { newTitle -> viewModel.renameSession(openSession.id, newTitle) },
            onMoveToTrash = {
                viewModel.moveToTrash(openSession.id)
                activeSessionId = null
            },
            onShare = { viewModel.exportAndShareSession(openSession) },
            onSaveToDownloads = {
                viewModel.saveSessionToPublicStorage(
                    openSession,
                    com.example.model.PublicExportDestination.DOWNLOADS
                )
            },
            onSaveToMusicFolder = { viewModel.saveSessionToMusicFolder(openSession) },
            onExportNotesTxt = { viewModel.exportAndShareNotes(openSession) },
            onAddBookmarkHere = { posMs -> viewModel.addBookmarkAtPlaybackPosition(openSession.id, posMs) },
            onToggleAbLoop = { viewModel.togglePlaybackAbLoop() },
            onClearAbLoop = { viewModel.clearPlaybackAbLoop() },
            onUpdateBookmarkLabel = { bmId, label ->
                viewModel.updateBookmarkLabel(openSession.id, bmId, label)
            },
            onDeleteBookmark = { bmId ->
                viewModel.deleteBookmark(openSession.id, bmId)
            },
            onApplyTrim = { startMs, endMs, saveAsNewCopy, onDone ->
                viewModel.trimSession(openSession, startMs, endMs, saveAsNewCopy) { created ->
                    onDone()
                    if (created != null && saveAsNewCopy) {
                        activeSessionId = created.id
                    }
                }
            },
            onApplySplit = { splitMs, keepOriginal, onDone ->
                viewModel.splitSession(openSession, splitMs, keepOriginal) { ok ->
                    onDone()
                    if (ok && !keepOriginal) {
                        activeSessionId = null
                    }
                }
            },
            onApplyRemoveSilence = { config, saveAsNewCopy, onDone ->
                viewModel.removeSilenceFromSession(
                    session = openSession,
                    config = config,
                    saveAsNewCopy = saveAsNewCopy
                ) { created ->
                    onDone()
                    if (created != null && saveAsNewCopy) {
                        activeSessionId = created.id
                    }
                }
            },
            onTranscribeAndSummarize = { viewModel.transcribeAndSummarize(openSession) },
            onSaveEditedTranscript = { text ->
                viewModel.saveEditedTranscriptAndSummarize(openSession, text)
            },
            onNotify = { msg -> viewModel.notifyMessage(msg) }
        )
        return
    }

    val nextSchedule = remember(schedules) {
        val now = System.currentTimeMillis()
        schedules.filter { it.isEnabled && it.endTimeMillis > now }.minByOrNull { it.startTimeMillis }
    }

    val isBatchSelecting = selectedTab == 1 && selectedSessionIds.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (isBatchSelecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { viewModel.clearSessionSelection() },
                            modifier = Modifier.testTag("batch_cancel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_exit_selection)
                            )
                        }
                    },
                    title = {
                        Text(
                            text = stringResource(R.string.batch_selected_count, selectedSessionIds.size),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.selectAllVisibleSessions() },
                            modifier = Modifier.testTag("batch_select_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SelectAll,
                                contentDescription = stringResource(R.string.cd_select_all)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.batchFavoriteSelected() },
                            modifier = Modifier.testTag("batch_star_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = stringResource(R.string.cd_toggle_favorite)
                            )
                        }
                        IconButton(
                            onClick = { showBatchCategoryDialog = true },
                            modifier = Modifier.testTag("batch_category_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Label,
                                contentDescription = stringResource(R.string.action_category)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.batchMoveSelectedToTrash() },
                            modifier = Modifier.testTag("batch_trash_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.action_move_to_trash),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = if (selectedTab == 0) {
                                stringResource(R.string.app_name)
                            } else {
                                stringResource(R.string.recordings_title)
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        if (selectedTab == 1) {
                            IconButton(
                                onClick = { showTrashSheet = true },
                                modifier = Modifier.testTag("open_trash_button")
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (trashedSessions.isNotEmpty()) {
                                            Badge { Text("${trashedSessions.size}") }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.cd_open_trash)
                                    )
                                }
                            }
                        }
                        val themeIcon = when (settings.themeMode) {
                            AppThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                            AppThemeMode.LIGHT -> Icons.Rounded.LightMode
                            AppThemeMode.DARK -> Icons.Rounded.DarkMode
                        }
                        IconButton(
                            onClick = { viewModel.cycleThemeMode() },
                            modifier = Modifier.testTag("theme_cycle_button")
                        ) {
                            Icon(
                                imageVector = themeIcon,
                                contentDescription = stringResource(R.string.cd_cycle_theme)
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.refreshStorageSummary()
                                showSettingsSheet = true
                            },
                            modifier = Modifier.testTag("settings_gear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.cd_open_settings)
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            Column {
                MiniPlayerBar(
                    playbackState = playbackState,
                    onOpenPlayer = {
                        playbackState.sessionId?.let { id ->
                            activeSessionId = id
                        }
                    },
                    onTogglePlayPause = { viewModel.togglePlayback() },
                    onClose = { viewModel.dismissMiniPlayer() }
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == 0) Icons.Rounded.Mic else Icons.Outlined.Mic,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.tab_record)) },
                        modifier = Modifier.testTag("nav_tab_record")
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == 1) Icons.Rounded.Folder else Icons.Outlined.Folder,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.tab_recordings)) },
                        modifier = Modifier.testTag("nav_tab_recordings")
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> RecordScreen(
                recorderState = recorderState,
                recordingMode = settings.recordingMode,
                audioFormat = settings.audioFormat,
                batteryTelemetry = batteryTelemetry,
                batteryProfile = settings.batteryProfile,
                lowBatteryStopPercent = settings.lowBatteryStopPercent,
                availableStorageBytes = storageSummary.availableDeviceBytes,
                autoStopMinutes = settings.autoStopMinutes,
                silenceAutoStopMinutes = settings.silenceAutoStopMinutes,
                nextSchedule = nextSchedule,
                hasMicPermission = hasMicPermission,
                onToggleVoiceActivated = { enabled ->
                    viewModel.updateRecordingMode(
                        if (enabled) RecordingMode.VOICE_ACTIVATED else RecordingMode.CONTINUOUS
                    )
                },
                onSelectFormat = { viewModel.updateAudioFormat(it) },
                onOpenSettings = {
                    viewModel.refreshStorageSummary()
                    showSettingsSheet = true
                },
                onSelectAutoStopMinutes = { viewModel.updateAutoStopMinutes(it) },
                onSelectSilenceAutoStopMinutes = { viewModel.updateSilenceAutoStopMinutes(it) },
                onOpenSchedules = { showSchedulesSheet = true },
                onDisarmStandby = { viewModel.disarmStandby() },
                onToggleRecord = {
                    if (recorderState.isSessionActive) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                },
                onPauseResume = {
                    if (recorderState.isPausedManual) {
                        viewModel.resumeRecording()
                    } else {
                        viewModel.pauseRecording()
                    }
                },
                onMarkMoment = { viewModel.markMoment() },
                onRequestMicPermission = { requestRequiredPermissions() },
                modifier = Modifier.padding(innerPadding)
            )

            else -> RecordingsScreen(
                sessions = filteredSessions,
                totalActiveCount = activeSessions.size,
                searchQuery = searchQuery,
                selectedFilter = selectedFilter,
                sortOrder = sortOrder,
                selectedSessionIds = selectedSessionIds,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onSelectFilter = { viewModel.updateFilter(it) },
                onSelectSortOrder = { viewModel.updateSortOrder(it) },
                onToggleSelection = { id -> viewModel.toggleSessionSelection(id) },
                onClearSelection = { viewModel.clearSessionSelection() },
                onOpenSession = { session -> activeSessionId = session.id },
                onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                onChangeCategory = { id, cat -> viewModel.updateCategory(id, cat) },
                onRenameSession = { id, newTitle -> viewModel.renameSession(id, newTitle) },
                onMoveToTrash = { id -> viewModel.moveToTrash(id) },
                onShareSession = { session -> viewModel.exportAndShareSession(session) },
                onSaveToPublicStorage = { session, dest ->
                    viewModel.saveSessionToPublicStorage(session, dest)
                },
                isExporting = isExporting,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    if (showBatchCategoryDialog) {
        CategoryPickerDialog(
            currentCategory = RecordingCategory.GENERAL,
            onDismiss = { showBatchCategoryDialog = false },
            onSelect = { cat ->
                viewModel.batchCategorizeSelected(cat)
                showBatchCategoryDialog = false
            }
        )
    }

    if (showSchedulesSheet) {
        SchedulesSheet(
            schedules = schedules,
            isArmedStandby = recorderState.status == RecorderStatus.ARMED_STANDBY,
            isRecordingActive = recorderState.isSessionActive,
            canScheduleExactAlarms = canExactAlarms,
            onRequestExactAlarms = { viewModel.openExactAlarmSettings() },
            onSaveSchedule = { existingId, label, startMs, endMs, repeat, mode ->
                viewModel.saveSchedule(existingId, label, startMs, endMs, repeat, mode)
            },
            onToggleSchedule = { id, enabled -> viewModel.toggleSchedule(id, enabled) },
            onDeleteSchedule = { id -> viewModel.deleteSchedule(id) },
            onArmStandby = { viewModel.armStandbyForNextSchedule() },
            onDisarmStandby = { viewModel.disarmStandby() },
            onDismiss = { showSchedulesSheet = false }
        )
    }

    if (showTrashSheet) {
        TrashBinSheet(
            trashedSessions = trashedSessions,
            onRestore = { id -> viewModel.restoreFromTrash(id) },
            onDeleteForever = { id -> viewModel.deleteSessionPermanently(id) },
            onEmptyTrash = { viewModel.emptyTrash() },
            onDismiss = { showTrashSheet = false }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = settings,
            storageSummary = storageSummary,
            onSelectThemeMode = { viewModel.updateThemeMode(it) },
            onSelectRecordingMode = { viewModel.updateRecordingMode(it) },
            onSelectFormat = { viewModel.updateAudioFormat(it) },
            onSelectSensitivity = { viewModel.updateVoiceSensitivity(it) },
            onSelectSilenceDelay = { viewModel.updateSilenceDelay(it) },
            onSelectSilenceAutoStop = { viewModel.updateSilenceAutoStopMinutes(it) },
            onSelectBatteryProfile = { viewModel.updateBatteryProfile(it) },
            onSelectLowBatteryStop = { viewModel.updateLowBatteryStopPercent(it) },
            onToggleKeepScreenOn = { viewModel.updateKeepScreenOn(it) },
            onEmptyTrash = { viewModel.emptyTrash() },
            onClearExportCache = { viewModel.clearExportCache() },
            onOpenBatteryCheck = {
                showSettingsSheet = false
                showBatterySheet = true
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showBatterySheet) {
        BatteryCheckSheet(
            isBatteryUnrestricted = isBatteryUnrestricted,
            onRequestBatteryUnrestricted = {
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
            },
            onDismiss = { showBatterySheet = false }
        )
    }
}
