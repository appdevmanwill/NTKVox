package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.PublicExportDestination
import com.example.model.RecordingCategory
import com.example.model.RecordingMode
import com.example.model.RecordingSession
import com.example.model.RecordingSortOrder
import com.example.model.SessionStatus
import com.example.service.PlaybackServiceState
import com.example.ui.theme.LocalSignalColors
import com.example.util.FormatUtils
import com.example.viewmodel.RecordingFilter

@Composable
fun RecordingsScreen(
    sessions: List<RecordingSession>,
    totalActiveCount: Int,
    searchQuery: String,
    selectedFilter: RecordingFilter,
    sortOrder: RecordingSortOrder,
    selectedSessionIds: Set<String>,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (RecordingFilter) -> Unit,
    onSelectSortOrder: (RecordingSortOrder) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onOpenSession: (RecordingSession) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onChangeCategory: (String, RecordingCategory) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onMoveToTrash: (String) -> Unit,
    onShareSession: (RecordingSession) -> Unit,
    onSaveToPublicStorage: (RecordingSession, PublicExportDestination) -> Unit,
    isExporting: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = selectedSessionIds.isNotEmpty()) {
        onClearSelection()
    }

    var sessionToRename by remember { mutableStateOf<RecordingSession?>(null) }
    var sessionToTrash by remember { mutableStateOf<RecordingSession?>(null) }
    var sessionForCategory by remember { mutableStateOf<RecordingSession?>(null) }
    var sessionToExport by remember { mutableStateOf<RecordingSession?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val selectionMode = selectedSessionIds.isNotEmpty()
    val visibleSessions = remember(sessions) {
        sessions.filter { it.status == SessionStatus.READY || it.chunks.isNotEmpty() }
    }

    if (totalActiveCount == 0) {
        EmptyRecordingsState(modifier = modifier)
    } else {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Search Bar + Sort Dropdown Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_recordings_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { onSearchQueryChange("") },
                                modifier = Modifier.testTag("clear_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.cd_clear_search)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("recordings_search_bar")
                )

                Box {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (sortOrder != RecordingSortOrder.NEWEST) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        IconButton(
                            onClick = { sortMenuExpanded = true },
                            modifier = Modifier
                                .size(52.dp)
                                .testTag("recordings_sort_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = stringResource(R.string.action_sort),
                                tint = if (sortOrder != RecordingSortOrder.NEWEST) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        RecordingSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.displayName) },
                                trailingIcon = if (order == sortOrder) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectSortOrder(order)
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Filter Chips: All, Starred, Categories
            LazyRow(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter is RecordingFilter.All,
                        onClick = { onSelectFilter(RecordingFilter.All) },
                        label = { Text(stringResource(R.string.filter_all)) },
                        modifier = Modifier.testTag("filter_chip_all")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter is RecordingFilter.Starred,
                        onClick = { onSelectFilter(RecordingFilter.Starred) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(stringResource(R.string.filter_starred)) },
                        modifier = Modifier.testTag("filter_chip_starred")
                    )
                }
                items(RecordingCategory.entries, key = { it.id }) { cat ->
                    FilterChip(
                        selected = selectedFilter is RecordingFilter.Category && selectedFilter.category == cat,
                        onClick = { onSelectFilter(RecordingFilter.Category(cat)) },
                        label = { Text(cat.displayName) },
                        modifier = Modifier.testTag("filter_chip_${cat.id}")
                    )
                }
            }

            if (visibleSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.search_no_results_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("recordings_list"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visibleSessions, key = { it.id }) { session ->
                        val isSelected = session.id in selectedSessionIds
                        SessionCard(
                            session = session,
                            isSelected = isSelected,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    onToggleSelection(session.id)
                                } else {
                                    onOpenSession(session)
                                }
                            },
                            onLongClick = { onToggleSelection(session.id) },
                            onToggleFavorite = { onToggleFavorite(session.id) },
                            onChangeCategory = { sessionForCategory = session },
                            onRename = { sessionToRename = session },
                            onMoveToTrash = { sessionToTrash = session },
                            onExport = { sessionToExport = session },
                            onShare = { onShareSession(session) }
                        )
                    }
                }
            }
        }
    }

    sessionToRename?.let { target ->
        RenameSessionDialog(
            initialTitle = target.title,
            onDismiss = { sessionToRename = null },
            onConfirm = { newTitle ->
                onRenameSession(target.id, newTitle)
                sessionToRename = null
            }
        )
    }

    sessionToTrash?.let { target ->
        MoveToTrashDialog(
            sessionTitle = target.title,
            onDismiss = { sessionToTrash = null },
            onConfirm = {
                onMoveToTrash(target.id)
                sessionToTrash = null
            }
        )
    }

    sessionForCategory?.let { target ->
        CategoryPickerDialog(
            currentCategory = target.category,
            onDismiss = { sessionForCategory = null },
            onSelect = { cat ->
                onChangeCategory(target.id, cat)
                sessionForCategory = null
            }
        )
    }

    sessionToExport?.let { target ->
        ExportRecordingDialog(
            sessionTitle = target.title,
            formatBadge = target.format.shortBadge,
            sizeLabel = FormatUtils.formatFileSize(target.totalSizeBytes),
            isExporting = isExporting,
            onSaveToDownloads = {
                sessionToExport = null
                onSaveToPublicStorage(target, PublicExportDestination.DOWNLOADS)
            },
            onSaveToMusic = {
                sessionToExport = null
                onSaveToPublicStorage(target, PublicExportDestination.MUSIC)
            },
            onShareIntent = {
                sessionToExport = null
                onShareSession(target)
            },
            onDismiss = { sessionToExport = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: RecordingSession,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onChangeCategory: () -> Unit,
    onRename: () -> Unit,
    onMoveToTrash: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    val signalColors = LocalSignalColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isSelected) 3.dp else 1.dp,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("session_card_${session.id}")
            .clip(RoundedCornerShape(22.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("session_star_${session.id}")
                ) {
                    Icon(
                        imageVector = if (session.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        contentDescription = stringResource(R.string.cd_toggle_favorite),
                        tint = if (session.isFavorite) signalColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("session_menu_${session.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.cd_more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_export_recording)) },
                                leadingIcon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onExport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onShare()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_category)) },
                                leadingIcon = { Icon(Icons.Rounded.Label, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onChangeCategory()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRename()
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
                                    onMoveToTrash()
                                }
                            )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${FormatUtils.formatTimeRange(session.startTimeMillis, session.endTimeMillis)} • ${
                    FormatUtils.formatDateLabel(session.startTimeMillis)
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isContinuous = session.recordingMode == RecordingMode.CONTINUOUS
                val clipLabel = if (isContinuous) {
                    if (session.clipCount == 1) "1 part" else "${session.clipCount} parts"
                } else {
                    if (session.clipCount == 1) "1 clip" else "${session.clipCount} clips"
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "${FormatUtils.formatCompactDuration(session.totalSpeechMillis)} • $clipLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isContinuous) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        signalColors.listeningContainer
                    }
                ) {
                    Text(
                        text = session.recordingMode.shortBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isContinuous) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            signalColors.onListeningContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = signalColors.listeningContainer
                ) {
                    Text(
                        text = session.format.shortBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = signalColors.onListeningContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }

                if (session.category != RecordingCategory.GENERAL) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = session.category.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }

                if (session.bookmarks.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = signalColors.warningContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bookmark,
                                contentDescription = null,
                                tint = signalColors.onWarningContainer,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${session.bookmarks.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = signalColors.onWarningContainer
                            )
                        }
                    }
                }

                if (session.notes != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = signalColors.readyContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Description,
                                contentDescription = null,
                                tint = signalColors.onReadyContainer,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.badge_transcribed),
                                style = MaterialTheme.typography.labelSmall,
                                color = signalColors.onReadyContainer
                            )
                        }
                    }
                }

                Text(
                    text = FormatUtils.formatFileSize(session.totalSizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    playbackState: PlaybackServiceState,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!playbackState.hasLoadedSession) return
    val progress = (playbackState.globalPositionMs.toFloat() / playbackState.totalDurationMs.coerceAtLeast(1L).toFloat())
        .coerceIn(0f, 1f)

    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlayer)
            .testTag("mini_player_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playbackState.sessionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${FormatUtils.formatHms(playbackState.globalPositionMs)} / ${
                            FormatUtils.formatHms(playbackState.totalDurationMs)
                        }" + if (playbackState.playbackSpeed != 1.0f) " • ${playbackState.playbackSpeed}×" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.testTag("mini_player_play_pause")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) {
                            stringResource(R.string.cd_pause)
                        } else {
                            stringResource(R.string.cd_play)
                        }
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("mini_player_close")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close_mini_player)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRecordingsState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_recordings_empty_1795374051171),
                contentDescription = stringResource(R.string.recordings_empty_title),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(24.dp))
            )
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.recordings_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.recordings_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerDialog(
    currentCategory: RecordingCategory,
    onDismiss: () -> Unit,
    onSelect: (RecordingCategory) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_category_title)) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecordingCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = currentCategory == cat,
                        onClick = { onSelect(cat) },
                        label = { Text(cat.displayName) },
                        modifier = Modifier.testTag("category_option_${cat.id}")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun RenameSessionDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initialTitle) { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.dialog_rename_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_session_input")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                modifier = Modifier.testTag("rename_confirm_button")
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
fun MoveToTrashDialog(
    sessionTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_move_to_trash) + "?") },
        text = {
            Text("\"$sessionTitle\"\n\n${stringResource(R.string.dialog_trash_body)}")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("trash_confirm_button")
            ) {
                Text(
                    text = stringResource(R.string.action_move_to_trash),
                    color = MaterialTheme.colorScheme.error
                )
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
fun ExportRecordingDialog(
    sessionTitle: String,
    formatBadge: String,
    sizeLabel: String,
    isExporting: Boolean,
    onSaveToDownloads: () -> Unit,
    onSaveToMusic: () -> Unit,
    onShareIntent: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.export_dialog_title))
                Text(
                    text = "$sessionTitle • $formatBadge • $sizeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExportDestinationOptionCard(
                    icon = Icons.Rounded.Download,
                    title = stringResource(R.string.export_save_downloads),
                    subtitle = stringResource(R.string.export_save_downloads_desc),
                    enabled = !isExporting,
                    onClick = onSaveToDownloads,
                    modifier = Modifier.testTag("export_option_downloads")
                )
                ExportDestinationOptionCard(
                    icon = Icons.Rounded.LibraryMusic,
                    title = stringResource(R.string.export_save_music),
                    subtitle = stringResource(R.string.export_save_music_desc),
                    enabled = !isExporting,
                    onClick = onSaveToMusic,
                    modifier = Modifier.testTag("export_option_music")
                )
                ExportDestinationOptionCard(
                    icon = Icons.Rounded.Share,
                    title = stringResource(R.string.export_share_intent),
                    subtitle = stringResource(R.string.export_share_intent_desc),
                    enabled = !isExporting,
                    onClick = onShareIntent,
                    modifier = Modifier.testTag("export_option_share")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ExportDestinationOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
