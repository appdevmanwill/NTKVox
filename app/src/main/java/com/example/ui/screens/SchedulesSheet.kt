package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.RecordingMode
import com.example.model.RecordingSchedule
import com.example.model.ScheduleRepeat
import com.example.ui.theme.LocalSignalColors
import com.example.util.FormatUtils
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesSheet(
    schedules: List<RecordingSchedule>,
    isArmedStandby: Boolean,
    isRecordingActive: Boolean,
    canScheduleExactAlarms: Boolean,
    onRequestExactAlarms: () -> Unit,
    onSaveSchedule: (
        existingId: String?,
        label: String,
        startMillis: Long,
        endMillis: Long,
        repeat: ScheduleRepeat,
        mode: RecordingMode
    ) -> Unit,
    onToggleSchedule: (String, Boolean) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onArmStandby: () -> Unit,
    onDisarmStandby: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val signalColors = LocalSignalColors.current

    var editingSchedule by remember { mutableStateOf<RecordingSchedule?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }

    val nextUpcoming = remember(schedules) {
        val now = System.currentTimeMillis()
        schedules.filter { it.isEnabled && it.endTimeMillis > now }.minByOrNull { it.startTimeMillis }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.schedules_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                val addScheduleDesc = stringResource(R.string.action_add_schedule)
                Button(
                    onClick = {
                        editingSchedule = null
                        showEditorDialog = true
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .testTag("add_schedule_button")
                        .semantics { contentDescription = addScheduleDesc }
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_add_short),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (!canScheduleExactAlarms) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = signalColors.warningContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            tint = signalColors.onWarningContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.exact_alarm_banner_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = signalColors.onWarningContainer
                            )
                            Text(
                                text = stringResource(R.string.exact_alarm_banner_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = signalColors.onWarningContainer
                            )
                        }
                        TextButton(
                            onClick = onRequestExactAlarms,
                            modifier = Modifier.testTag("allow_exact_alarms_button")
                        ) {
                            Text(
                                text = stringResource(R.string.action_allow_exact_alarms),
                                color = signalColors.onWarningContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (nextUpcoming != null || isArmedStandby) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isArmedStandby) {
                            signalColors.listeningContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = if (isArmedStandby) signalColors.listening else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isArmedStandby) {
                                    stringResource(R.string.status_armed_standby)
                                } else {
                                    "Hands-Free Screen-Off Standby"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isArmedStandby) {
                                    signalColors.onListeningContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Text(
                            text = stringResource(R.string.armed_standby_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isArmedStandby) {
                                signalColors.onListeningContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (isArmedStandby) {
                            OutlinedButton(
                                onClick = onDisarmStandby,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("disarm_standby_sheet_button")
                            ) {
                                Text(stringResource(R.string.action_disarm_standby))
                            }
                        } else if (!isRecordingActive && nextUpcoming != null) {
                            Button(
                                onClick = onArmStandby,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("arm_standby_sheet_button")
                            ) {
                                Icon(Icons.Rounded.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Arm Standby for ${nextUpcoming.label}")
                            }
                        }
                    }
                }
            }

            if (schedules.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarMonth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = stringResource(R.string.schedules_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.schedules_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                schedules.forEach { schedule ->
                    ScheduleRowCard(
                        schedule = schedule,
                        onToggle = { enabled -> onToggleSchedule(schedule.id, enabled) },
                        onEdit = {
                            editingSchedule = schedule
                            showEditorDialog = true
                        },
                        onDelete = { onDeleteSchedule(schedule.id) }
                    )
                }
            }
        }
    }

    if (showEditorDialog) {
        ScheduleEditorDialog(
            existing = editingSchedule,
            onDismiss = { showEditorDialog = false },
            onSave = { label, startMs, endMs, repeat, mode ->
                onSaveSchedule(editingSchedule?.id, label, startMs, endMs, repeat, mode)
                showEditorDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleRowCard(
    schedule: RecordingSchedule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_card_${schedule.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = schedule.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Starts: ${FormatUtils.formatDateTimeShort(schedule.startTimeMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Stops:  ${FormatUtils.formatDateTimeShort(schedule.endTimeMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = schedule.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("schedule_switch_${schedule.id}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = schedule.repeat.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = schedule.recordingMode.shortBadge,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("schedule_edit_${schedule.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit schedule",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("schedule_delete_${schedule.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Delete schedule",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditorDialog(
    existing: RecordingSchedule?,
    onDismiss: () -> Unit,
    onSave: (String, Long, Long, ScheduleRepeat, RecordingMode) -> Unit
) {
    val defaultStart = remember(existing) {
        existing?.startTimeMillis ?: Calendar.getInstance().apply {
            add(Calendar.MINUTE, 10)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val defaultEnd = remember(existing) {
        existing?.endTimeMillis ?: (defaultStart + 60 * 60_000L)
    }

    var label by remember(existing) { mutableStateOf(existing?.label ?: "Scheduled Recording") }
    var startMillis by remember(existing) { mutableLongStateOf(defaultStart) }
    var endMillis by remember(existing) { mutableLongStateOf(defaultEnd) }
    var repeat by remember(existing) { mutableStateOf(existing?.repeat ?: ScheduleRepeat.ONCE) }
    var mode by remember(existing) { mutableStateOf(existing?.recordingMode ?: RecordingMode.VOICE_ACTIVATED) }

    var activePickerTarget by remember { mutableStateOf<String?>(null) } // "start_date", "start_time", "end_date", "end_time"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Recording Schedule" else "Edit Schedule") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Schedule Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("schedule_label_input")
                )

                Text(
                    text = "Start Date & Time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { activePickerTarget = "start_date" },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("schedule_start_date_button")
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(FormatUtils.formatShortDate(startMillis), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { activePickerTarget = "start_time" },
                        modifier = Modifier
                            .weight(0.85f)
                            .testTag("schedule_start_time_button")
                    ) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(FormatUtils.formatWallClockTime(startMillis), maxLines = 1)
                    }
                }

                Text(
                    text = "Stop Date & Time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { activePickerTarget = "end_date" },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("schedule_end_date_button")
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(FormatUtils.formatShortDate(endMillis), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { activePickerTarget = "end_time" },
                        modifier = Modifier
                            .weight(0.85f)
                            .testTag("schedule_end_time_button")
                    ) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(FormatUtils.formatWallClockTime(endMillis), maxLines = 1)
                    }
                }

                Text(
                    text = "Repeat",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleRepeat.entries.forEach { option ->
                        FilterChip(
                            selected = repeat == option,
                            onClick = { repeat = option },
                            label = { Text(option.displayName) },
                            modifier = Modifier.testTag("schedule_repeat_${option.id}")
                        )
                    }
                }

                Text(
                    text = "Recording Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option },
                            label = { Text(option.displayName) },
                            modifier = Modifier.testTag("schedule_mode_${option.id}")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeEnd = if (endMillis <= startMillis) {
                        startMillis + 60 * 60_000L
                    } else {
                        endMillis
                    }
                    onSave(label, startMillis, safeEnd, repeat, mode)
                },
                modifier = Modifier.testTag("schedule_save_button")
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

    // Material 3 DatePickerDialog for Start or End date (UTC-midnight safe!)
    if (activePickerTarget == "start_date" || activePickerTarget == "end_date") {
        val isStart = activePickerTarget == "start_date"
        val currentMillis = if (isStart) startMillis else endMillis
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localMillisToUtcMidnight(currentMillis)
        )
        DatePickerDialog(
            onDismissRequest = { activePickerTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pickedUtc = datePickerState.selectedDateMillis
                        if (pickedUtc != null) {
                            val updated = mergeUtcDateWithLocalTime(pickedUtc, currentMillis)
                            if (isStart) {
                                val delta = (endMillis - startMillis).coerceAtLeast(60 * 60_000L)
                                startMillis = updated
                                if (endMillis <= startMillis) {
                                    endMillis = startMillis + delta
                                }
                            } else {
                                endMillis = updated
                            }
                        }
                        activePickerTarget = null
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { activePickerTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Material 3 TimePicker dialog for Start or End time
    if (activePickerTarget == "start_time" || activePickerTarget == "end_time") {
        val isStart = activePickerTarget == "start_time"
        val currentMillis = if (isStart) startMillis else endMillis
        val cal = remember(currentMillis) {
            Calendar.getInstance().apply { timeInMillis = currentMillis }
        }
        val timePickerState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { activePickerTarget = null },
            title = { Text(if (isStart) "Choose Start Time" else "Choose Stop Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updatedCal = Calendar.getInstance().apply {
                            timeInMillis = currentMillis
                            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            set(Calendar.MINUTE, timePickerState.minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val updated = updatedCal.timeInMillis
                        if (isStart) {
                            startMillis = updated
                            if (endMillis <= startMillis) {
                                endMillis = startMillis + 60 * 60_000L
                            }
                        } else {
                            endMillis = updated
                        }
                        activePickerTarget = null
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { activePickerTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun localMillisToUtcMidnight(localMillis: Long): Long {
    val localCal = Calendar.getInstance().apply { timeInMillis = localMillis }
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            localCal.get(Calendar.YEAR),
            localCal.get(Calendar.MONTH),
            localCal.get(Calendar.DAY_OF_MONTH),
            0,
            0,
            0
        )
    }
    return utcCal.timeInMillis
}

private fun mergeUtcDateWithLocalTime(utcDateMillis: Long, existingLocalMillis: Long): Long {
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcDateMillis
    }
    val localCal = Calendar.getInstance().apply {
        timeInMillis = existingLocalMillis
        set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
        set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return localCal.timeInMillis
}
