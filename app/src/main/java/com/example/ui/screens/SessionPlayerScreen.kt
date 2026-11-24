package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AudioChunk
import com.example.model.RecordingCategory
import com.example.model.RecordingSession
import com.example.model.SessionBookmark
import com.example.model.VoiceCleanConfig
import com.example.service.PlaybackServiceState
import com.example.storage.SessionRepository
import com.example.ui.theme.LocalSignalColors
import com.example.ui.theme.TabularTimestampStyle
import com.example.util.FormatUtils

private enum class PlayerSection {
    MOMENTS,
    SUMMARY,
    TRANSCRIPT
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionPlayerScreen(
    session: RecordingSession,
    sessionRepository: SessionRepository,
    playbackState: PlaybackServiceState,
    isProcessingNotes: Boolean,
    processingStage: String?,
    isExporting: Boolean,
    isEditingAudio: Boolean,
    onLoadSession: (RecordingSession) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekGlobal: (Long) -> Unit,
    onSeekToChunk: (Int) -> Unit,
    onSkipBy: (Long) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectBoostDb: (Int) -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onChangeCategory: (RecordingCategory) -> Unit,
    onRename: (String) -> Unit,
    onMoveToTrash: () -> Unit,
    onShare: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onSaveToMusicFolder: () -> Unit,
    onExportNotesTxt: () -> Unit,
    onAddBookmarkHere: (Long) -> Unit,
    onToggleAbLoop: () -> Unit,
    onClearAbLoop: () -> Unit,
    onUpdateBookmarkLabel: (String, String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onApplyTrim: (startMs: Long, endMs: Long, saveAsNewCopy: Boolean, onDone: () -> Unit) -> Unit,
    onApplySplit: (splitMs: Long, keepOriginal: Boolean, onDone: () -> Unit) -> Unit,
    onApplyRemoveSilence: (config: VoiceCleanConfig, saveAsNewCopy: Boolean, onDone: () -> Unit) -> Unit,
    onTranscribeAndSummarize: () -> Unit,
    onSaveEditedTranscript: (String) -> Unit,
    onNotify: (String) -> Unit
) {
    BackHandler(onBack = onBack)

    val clipboard = LocalClipboardManager.current
    val signalColors = LocalSignalColors.current

    var selectedSection by rememberSaveable { mutableStateOf(PlayerSection.MOMENTS) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showTrimSplitSheet by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<SessionBookmark?>(null) }
    var isEditingTranscript by remember { mutableStateOf(false) }
    var editableTranscriptText by remember(session.notes?.transcript) {
        mutableStateOf(session.notes?.transcript.orEmpty())
    }

    val playableChunks = remember(session.chunks) {
        session.chunks.filter { sessionRepository.getChunkFile(session, it).exists() }
    }

    // Load this session into the background VoxPlaybackService seamlessly
    LaunchedEffect(session.id, session.chunks.size) {
        onLoadSession(session)
    }

    val isThisSessionLoaded = playbackState.sessionId == session.id
    val isPlaying = isThisSessionLoaded && playbackState.isPlaying
    val currentChunkIndex = if (isThisSessionLoaded) playbackState.currentChunkIndex else 0
    val totalDurationMs = remember(playableChunks, isThisSessionLoaded, playbackState.totalDurationMs) {
        if (isThisSessionLoaded && playbackState.totalDurationMs > 1L) {
            playbackState.totalDurationMs
        } else {
            playableChunks.sumOf { it.durationMillis.coerceAtLeast(0L) }.coerceAtLeast(1L)
        }
    }
    val globalPositionMs = if (isThisSessionLoaded) {
        playbackState.globalPositionMs.coerceIn(0L, totalDurationMs)
    } else {
        0L
    }

    val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val boostOptions = listOf(0, 6, 12, 18)

    val offlineDictationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                val updated = if (editableTranscriptText.isBlank()) {
                    spoken
                } else {
                    "$editableTranscriptText\n\n$spoken"
                }
                editableTranscriptText = updated
                isEditingTranscript = true
                selectedSection = PlayerSection.TRANSCRIPT
            }
        }
    }

    fun launchOfflineDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to append text to this transcript")
        }
        runCatching {
            offlineDictationLauncher.launch(intent)
        }.onFailure {
            onNotify("On-device speech dictation is not installed on this device. You can type directly in Edit Transcript.")
        }
    }

    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${session.format.shortBadge} • ${session.category.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("player_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("player_favorite_button")
                    ) {
                        Icon(
                            imageVector = if (session.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            contentDescription = stringResource(R.string.cd_toggle_favorite),
                            tint = if (session.isFavorite) signalColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showExportDialog = true },
                        enabled = !isExporting && playableChunks.isNotEmpty(),
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("player_share_button")
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.FileUpload,
                                contentDescription = stringResource(R.string.cd_export_recording)
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("player_more_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.cd_more_options)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_save_downloads)) },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                enabled = !isExporting && playableChunks.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    onSaveToDownloads()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_save_to_music)) },
                                leadingIcon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                                enabled = !isExporting && playableChunks.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    onSaveToMusicFolder()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_share_intent)) },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                enabled = !isExporting && playableChunks.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    onShare()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_export_notes)) },
                                leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onExportNotesTxt()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_category)) },
                                leadingIcon = { Icon(Icons.Rounded.Label, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showCategoryDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showRenameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.action_move_to_trash),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showTrashDialog = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
            ) {
                // Seamless Background-Powered Continuous Timeline Player Card
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val activeChunk = playableChunks.getOrNull(currentChunkIndex)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeChunk?.let {
                                    "Clip ${currentChunkIndex + 1} of ${playableChunks.size} • ${
                                        FormatUtils.formatWallClockTime(it.startTimeMillis)
                                    }"
                                } ?: stringResource(R.string.player_no_clips),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = FormatUtils.formatFileSize(session.totalSizeBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Slider(
                            value = globalPositionMs.toFloat(),
                            onValueChange = { onSeekGlobal(it.toLong()) },
                            valueRange = 0f..totalDurationMs.toFloat(),
                            enabled = playableChunks.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("player_timeline_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = FormatUtils.formatHms(globalPositionMs),
                                style = TabularTimestampStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = FormatUtils.formatHms(totalDurationMs),
                                style = TabularTimestampStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Active A-B Loop Status Pill
                        val loopA = if (isThisSessionLoaded) playbackState.loopStartMs else null
                        val loopB = if (isThisSessionLoaded) playbackState.loopEndMs else null
                        if (loopA != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = signalColors.listeningContainer,
                                modifier = Modifier.testTag("player_ab_loop_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Repeat,
                                        contentDescription = null,
                                        tint = signalColors.onListeningContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (loopB != null) {
                                            "Loop ${FormatUtils.formatHms(loopA)} – ${FormatUtils.formatHms(loopB)}"
                                        } else {
                                            "Point A: ${FormatUtils.formatHms(loopA)} • Tap Set B"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = signalColors.onListeningContainer
                                    )
                                    IconButton(
                                        onClick = onClearAbLoop,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("player_clear_loop_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.cd_clear_loop),
                                            tint = signalColors.onListeningContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onSkipBy(-10_000L) },
                                enabled = playableChunks.isNotEmpty(),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("player_rewind_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay10,
                                    contentDescription = stringResource(R.string.cd_rewind_10),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .size(60.dp)
                                    .testTag("player_play_pause_button")
                                    .clip(CircleShape)
                                    .clickable(enabled = playableChunks.isNotEmpty()) {
                                        onTogglePlayPause()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) {
                                            stringResource(R.string.cd_pause)
                                        } else {
                                            stringResource(R.string.cd_play)
                                        },
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onSkipBy(10_000L) },
                                enabled = playableChunks.isNotEmpty(),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("player_forward_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Forward10,
                                    contentDescription = stringResource(R.string.cd_forward_10),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Studio Control Strip: single crisp scrollable row (Speed | Boost | Edit | Bookmark | A-B)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val currentSpeed = playbackState.playbackSpeed
                            FilterChip(
                                selected = currentSpeed != 1.0f,
                                onClick = {
                                    val idx = speedOptions.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.05f }
                                    val nextSpeed = speedOptions[(idx + 1) % speedOptions.size]
                                    onSelectSpeed(nextSpeed)
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    val txt = if (currentSpeed % 1f == 0f) {
                                        "${currentSpeed.toInt()}.0×"
                                    } else {
                                        "${currentSpeed}×"
                                    }
                                    Text(txt, maxLines = 1, softWrap = false)
                                },
                                modifier = Modifier.testTag("player_speed_chip")
                            )

                            val currentBoost = playbackState.boostDb
                            FilterChip(
                                selected = currentBoost > 0,
                                onClick = {
                                    val idx = boostOptions.indexOf(currentBoost)
                                    val nextBoost = boostOptions[(idx + 1) % boostOptions.size]
                                    onSelectBoostDb(nextBoost)
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(
                                        text = if (currentBoost == 0) {
                                            stringResource(R.string.voice_boost_off)
                                        } else {
                                            "+${currentBoost} dB"
                                        },
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                modifier = Modifier.testTag("player_boost_chip")
                            )

                            FilterChip(
                                selected = false,
                                onClick = { showTrimSplitSheet = true },
                                enabled = playableChunks.isNotEmpty() && !isEditingAudio,
                                leadingIcon = {
                                    Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.action_edit_short),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                modifier = Modifier.testTag("player_trim_split_chip")
                            )

                            FilterChip(
                                selected = false,
                                onClick = { onAddBookmarkHere(globalPositionMs) },
                                enabled = playableChunks.isNotEmpty(),
                                leadingIcon = {
                                    Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(
                                        text = stringResource(R.string.action_add_bookmark_here),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                modifier = Modifier.testTag("player_add_bookmark_chip")
                            )

                            val abLoopA = if (isThisSessionLoaded) playbackState.loopStartMs else null
                            val abLoopB = if (isThisSessionLoaded) playbackState.loopEndMs else null
                            FilterChip(
                                selected = abLoopA != null,
                                onClick = onToggleAbLoop,
                                enabled = playableChunks.isNotEmpty(),
                                leadingIcon = {
                                    Icon(Icons.Rounded.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(
                                        text = when {
                                            abLoopA == null -> stringResource(R.string.action_ab_loop)
                                            abLoopB == null -> stringResource(R.string.action_set_loop_b)
                                            else -> "A–B On"
                                        },
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                modifier = Modifier.testTag("player_ab_loop_chip")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3-Way Tab Switcher: Moments | Summary | Transcript (never truncates on 351dp @ 1.3x)
                val sections = listOf(
                    PlayerSection.MOMENTS to stringResource(R.string.tab_moments),
                    PlayerSection.SUMMARY to stringResource(R.string.tab_summary),
                    PlayerSection.TRANSCRIPT to stringResource(R.string.tab_transcript)
                )
                PrimaryTabRow(
                    selectedTabIndex = sections.indexOfFirst { it.first == selectedSection }.coerceAtLeast(0),
                    containerColor = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    sections.forEach { (section, label) ->
                        Tab(
                            selected = selectedSection == section,
                            onClick = { selectedSection = section },
                            text = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selectedSection == section) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag("player_tab_${section.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (selectedSection) {
                    PlayerSection.MOMENTS -> {
                        if (playableChunks.isEmpty() && session.bookmarks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.player_no_clips),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .testTag("spoken_moments_list"),
                                contentPadding = PaddingValues(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (session.bookmarks.isNotEmpty()) {
                                    item(key = "bookmarks_section_header") {
                                        Text(
                                            text = stringResource(R.string.bookmarks_header),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                                        )
                                    }
                                    items(session.bookmarks, key = { "bm_${it.id}" }) { bookmark ->
                                        BookmarkMomentRow(
                                            bookmark = bookmark,
                                            onSeek = { onSeekGlobal(bookmark.offsetFromSessionStartMillis) },
                                            onEdit = { editingBookmark = bookmark },
                                            onDelete = { onDeleteBookmark(bookmark.id) }
                                        )
                                    }
                                    item(key = "clips_section_header") {
                                        Text(
                                            text = stringResource(R.string.player_spoken_moments),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                        )
                                    }
                                }

                                itemsIndexed(playableChunks, key = { _, c -> "chunk_${c.index}" }) { idx, chunk ->
                                    SpokenMomentRow(
                                        index = idx + 1,
                                        chunk = chunk,
                                        isCurrent = idx == currentChunkIndex,
                                        isPlaying = isPlaying && idx == currentChunkIndex,
                                        onClick = { onSeekToChunk(idx) }
                                    )
                                }
                            }
                        }
                    }

                    PlayerSection.SUMMARY -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val notes = session.notes
                            if (isProcessingNotes) {
                                ProcessingNotesCard(processingStage)
                            } else if (notes == null) {
                                EmptyNotesCard(
                                    enabled = playableChunks.isNotEmpty(),
                                    onTranscribe = onTranscribeAndSummarize,
                                    onManualNotes = {
                                        editableTranscriptText = ""
                                        isEditingTranscript = true
                                        selectedSection = PlayerSection.TRANSCRIPT
                                    }
                                )
                            } else {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = signalColors.readyContainer
                                    ) {
                                        Text(
                                            text = stringResource(R.string.rule_based_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = signalColors.onReadyContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = onExportNotesTxt,
                                        enabled = !isExporting,
                                        modifier = Modifier.testTag("export_notes_summary_button")
                                    ) {
                                        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.action_export_notes))
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(notes.summary))
                                            onNotify(copiedMessage)
                                        },
                                        modifier = Modifier.testTag("copy_summary_button")
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.action_copy_text))
                                    }

                                    FilledTonalButton(
                                        onClick = onTranscribeAndSummarize,
                                        modifier = Modifier.testTag("refresh_summary_button")
                                    ) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.action_regenerate_summary))
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = stringResource(R.string.summary_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = notes.summary,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                if (notes.highlights.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(
                                                text = stringResource(R.string.highlights_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            notes.highlights.forEach { item ->
                                                Row(
                                                    modifier = Modifier.padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .padding(top = 7.dp)
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.primary)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Text(
                                                        text = item,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (notes.keywords.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(
                                                text = stringResource(R.string.keywords_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                notes.keywords.forEach { keyword ->
                                                    Surface(
                                                        shape = RoundedCornerShape(10.dp),
                                                        color = signalColors.listeningContainer
                                                    ) {
                                                        Text(
                                                            text = keyword,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = signalColors.onListeningContainer,
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    PlayerSection.TRANSCRIPT -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val notes = session.notes
                            if (isProcessingNotes) {
                                ProcessingNotesCard(processingStage)
                            } else if (notes == null && !isEditingTranscript) {
                                EmptyNotesCard(
                                    enabled = playableChunks.isNotEmpty(),
                                    onTranscribe = onTranscribeAndSummarize,
                                    onManualNotes = {
                                        editableTranscriptText = ""
                                        isEditingTranscript = true
                                    }
                                )
                            } else {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (isEditingTranscript) {
                                        Button(
                                            onClick = {
                                                onSaveEditedTranscript(editableTranscriptText)
                                                isEditingTranscript = false
                                            },
                                            modifier = Modifier.testTag("save_transcript_button")
                                        ) {
                                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.action_save_transcript))
                                        }
                                        OutlinedButton(
                                            onClick = { launchOfflineDictation() },
                                            modifier = Modifier.testTag("dictate_transcript_button")
                                        ) {
                                            Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.action_dictate_note))
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = {
                                                editableTranscriptText = notes?.transcript.orEmpty()
                                                isEditingTranscript = true
                                            },
                                            modifier = Modifier.testTag("edit_transcript_button")
                                        ) {
                                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.action_edit_transcript))
                                        }
                                        OutlinedButton(
                                            onClick = { launchOfflineDictation() },
                                            modifier = Modifier.testTag("dictate_transcript_button")
                                        ) {
                                            Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.action_dictate_note))
                                        }
                                        OutlinedButton(
                                            onClick = onExportNotesTxt,
                                            enabled = !isExporting,
                                            modifier = Modifier.testTag("export_notes_transcript_button")
                                        ) {
                                            Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.action_export_notes))
                                        }
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(notes?.transcript.orEmpty()))
                                                onNotify(copiedMessage)
                                            },
                                            modifier = Modifier.testTag("copy_transcript_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ContentCopy,
                                                contentDescription = stringResource(R.string.action_copy_text)
                                            )
                                        }
                                    }
                                }

                                if (isEditingTranscript) {
                                    Text(
                                        text = stringResource(R.string.transcript_edit_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = editableTranscriptText,
                                        onValueChange = { editableTranscriptText = it },
                                        minLines = 8,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("transcript_editor_field")
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(
                                                text = stringResource(R.string.transcript_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                text = notes?.transcript.orEmpty(),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameSessionDialog(
            initialTitle = session.title,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle ->
                onRename(newTitle)
                showRenameDialog = false
            }
        )
    }

    if (showTrashDialog) {
        MoveToTrashDialog(
            sessionTitle = session.title,
            onDismiss = { showTrashDialog = false },
            onConfirm = {
                showTrashDialog = false
                onMoveToTrash()
            }
        )
    }

    if (showCategoryDialog) {
        CategoryPickerDialog(
            currentCategory = session.category,
            onDismiss = { showCategoryDialog = false },
            onSelect = { cat ->
                onChangeCategory(cat)
                showCategoryDialog = false
            }
        )
    }

    if (showExportDialog) {
        ExportRecordingDialog(
            sessionTitle = session.title,
            formatBadge = session.format.shortBadge,
            sizeLabel = FormatUtils.formatFileSize(session.totalSizeBytes),
            isExporting = isExporting,
            onSaveToDownloads = {
                showExportDialog = false
                onSaveToDownloads()
            },
            onSaveToMusic = {
                showExportDialog = false
                onSaveToMusicFolder()
            },
            onShareIntent = {
                showExportDialog = false
                onShare()
            },
            onDismiss = { showExportDialog = false }
        )
    }

    editingBookmark?.let { target ->
        EditBookmarkLabelDialog(
            initialLabel = target.label,
            onDismiss = { editingBookmark = null },
            onSave = { newLabel ->
                onUpdateBookmarkLabel(target.id, newLabel)
                editingBookmark = null
            }
        )
    }

    if (showTrimSplitSheet) {
        TrimSplitSheet(
            session = session,
            isProcessing = isEditingAudio,
            onApplyTrim = { startMs, endMs, saveAsNewCopy ->
                onApplyTrim(startMs, endMs, saveAsNewCopy) {
                    showTrimSplitSheet = false
                }
            },
            onApplySplit = { splitMs, keepOriginal ->
                onApplySplit(splitMs, keepOriginal) {
                    showTrimSplitSheet = false
                }
            },
            onApplyRemoveSilence = { config, saveAsNewCopy ->
                onApplyRemoveSilence(config, saveAsNewCopy) {
                    showTrimSplitSheet = false
                }
            },
            onDismiss = { showTrimSplitSheet = false }
        )
    }
}

@Composable
private fun BookmarkMomentRow(
    bookmark: SessionBookmark,
    onSeek: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val signalColors = LocalSignalColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = signalColors.warningContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, signalColors.warning.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bookmark_row_${bookmark.id}")
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSeek)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Bookmark,
                contentDescription = null,
                tint = signalColors.warning,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${FormatUtils.formatHms(bookmark.offsetFromSessionStartMillis)} • ${
                        FormatUtils.formatWallClockTime(bookmark.timestampMillis)
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("bookmark_edit_${bookmark.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.cd_edit_bookmark),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("bookmark_delete_${bookmark.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.cd_delete_bookmark),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EditBookmarkLabelDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(initialLabel) { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.bookmark_edit_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bookmark_label_input")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSave(text.trim()) },
                modifier = Modifier.testTag("bookmark_save_button")
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SpokenMomentRow(
    index: Int,
    chunk: AudioChunk,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val signalColors = LocalSignalColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isCurrent) 1.5.dp else 1.dp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("spoken_moment_row_$index")
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPlaying) signalColors.recordingContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (isPlaying) signalColors.recording else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${FormatUtils.formatWallClockTime(chunk.startTimeMillis)} • ${
                        FormatUtils.formatCompactDuration(chunk.durationMillis)
                    }",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Spoken moment #$index",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = FormatUtils.formatFileSize(chunk.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyNotesCard(
    enabled: Boolean,
    onTranscribe: () -> Unit,
    onManualNotes: () -> Unit
) {
    val signalColors = LocalSignalColors.current
    val isLegacyAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = signalColors.readyContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Summarize,
                        contentDescription = null,
                        tint = signalColors.onReadyContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.notes_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isLegacyAndroid) {
                    stringResource(R.string.notes_empty_body_legacy)
                } else {
                    stringResource(R.string.notes_empty_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = signalColors.readyContainer
            ) {
                Text(
                    text = stringResource(R.string.rule_based_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = signalColors.onReadyContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onTranscribe,
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("transcribe_summarize_button")
            ) {
                Icon(Icons.Rounded.AutoAwesomeMotion, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isLegacyAndroid) {
                        stringResource(R.string.action_generate_acoustic_log)
                    } else {
                        stringResource(R.string.action_transcribe_summarize)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onManualNotes,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("manual_notes_button")
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_add_notes_manual),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProcessingNotesCard(stage: String?) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (stage == "summarizing") {
                    stringResource(R.string.processing_summarizing)
                } else {
                    stringResource(R.string.processing_transcribing)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
