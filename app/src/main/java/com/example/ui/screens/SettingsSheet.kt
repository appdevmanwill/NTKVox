package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AppThemeMode
import com.example.model.AudioFormatOption
import com.example.model.BatteryOptimizationProfile
import com.example.model.RecordingMode
import com.example.model.StorageSummary
import com.example.model.UserSettings
import com.example.model.VoiceSensitivity
import com.example.ui.theme.LocalSignalColors
import com.example.util.BatteryOptimizationHelper
import com.example.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: UserSettings,
    storageSummary: StorageSummary,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    onSelectRecordingMode: (RecordingMode) -> Unit,
    onSelectFormat: (AudioFormatOption) -> Unit,
    onSelectSensitivity: (VoiceSensitivity) -> Unit,
    onSelectSilenceDelay: (Int) -> Unit,
    onSelectSilenceAutoStop: (Int) -> Unit,
    onSelectBatteryProfile: (BatteryOptimizationProfile) -> Unit,
    onSelectLowBatteryStop: (Int) -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onEmptyTrash: () -> Unit,
    onClearExportCache: () -> Unit,
    onOpenBatteryCheck: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val signalColors = LocalSignalColors.current
    val delayPresets = listOf(5, 10, 15, 30)
    val silenceAutoStopPresets = listOf(0, 1, 2, 3, 5, 10)
    val lowBatteryPresets = listOf(0, 5, 10, 15, 20)
    val isCustomLowBattery = settings.lowBatteryStopPercent > 0 &&
        settings.lowBatteryStopPercent !in lowBatteryPresets
    var showCustomBatteryDialog by remember { mutableStateOf(false) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }

    val isVoxEnabled = settings.recordingMode == RecordingMode.VOICE_ACTIVATED

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
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 0a. Appearance (System / Light / Dark Theme)
            Text(
                text = stringResource(R.string.settings_appearance_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = settings.themeMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = AppThemeMode.entries
                modes.forEachIndexed { idx, mode ->
                    val icon = when (mode) {
                        AppThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                        AppThemeMode.LIGHT -> Icons.Rounded.LightMode
                        AppThemeMode.DARK -> Icons.Rounded.DarkMode
                    }
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { onSelectThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size),
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("theme_mode_${mode.id}")
                    ) {
                        Text(mode.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 0b. Recording Mode Switch (Voice-Activated VOX vs Continuous Normal)
            Text(
                text = stringResource(R.string.settings_mode_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isVoxEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        onSelectRecordingMode(
                            if (isVoxEnabled) RecordingMode.CONTINUOUS else RecordingMode.VOICE_ACTIVATED
                        )
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.recording_mode_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (isVoxEnabled) {
                                stringResource(R.string.recording_mode_vox_desc)
                            } else {
                                stringResource(R.string.recording_mode_continuous_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isVoxEnabled,
                        onCheckedChange = { checked ->
                            onSelectRecordingMode(
                                if (checked) RecordingMode.VOICE_ACTIVATED else RecordingMode.CONTINUOUS
                            )
                        },
                        modifier = Modifier.testTag("settings_vox_mode_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 1. Audio Format Selector (5 Formats including Pure-Java MP3 & M4A Remux)
            Text(
                text = stringResource(R.string.settings_format_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_format_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AudioFormatOption.entries.forEach { option ->
                    val selected = settings.audioFormat == option
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("format_option_${option.id}")
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectFormat(option) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (option == AudioFormatOption.OGG_OPUS) {
                                            signalColors.readyContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    ) {
                                        Text(
                                            text = option.recommendationBadge,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (option == AudioFormatOption.OGG_OPUS) {
                                                signalColors.onReadyContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (option == AudioFormatOption.MP3) {
                                            signalColors.warningContainer
                                        } else {
                                            signalColors.listeningContainer
                                        }
                                    ) {
                                        Text(
                                            text = option.powerBadge,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = if (option == AudioFormatOption.MP3) {
                                                signalColors.onWarningContainer
                                            } else {
                                                signalColors.onListeningContainer
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 2. Voice Sensitivity & Background Noise Guard (3 Tiers)
            Text(
                text = stringResource(R.string.settings_sensitivity_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!isVoxEnabled) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.settings_vox_only_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SensitivityChoiceCard(
                    title = stringResource(R.string.sensitivity_quiet_title),
                    badge = stringResource(R.string.sensitivity_quiet_badge),
                    description = stringResource(R.string.sensitivity_quiet_desc),
                    selected = settings.voiceSensitivity == VoiceSensitivity.QUIET_VOICES,
                    onClick = { onSelectSensitivity(VoiceSensitivity.QUIET_VOICES) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sensitivity_quiet_card")
                )
                SensitivityChoiceCard(
                    title = stringResource(R.string.sensitivity_normal_title),
                    badge = null,
                    description = stringResource(R.string.sensitivity_normal_desc),
                    selected = settings.voiceSensitivity == VoiceSensitivity.NORMAL_ROOM,
                    onClick = { onSelectSensitivity(VoiceSensitivity.NORMAL_ROOM) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sensitivity_normal_card")
                )
                SensitivityChoiceCard(
                    title = stringResource(R.string.sensitivity_prominent_title),
                    badge = stringResource(R.string.sensitivity_prominent_badge),
                    description = stringResource(R.string.sensitivity_prominent_desc),
                    selected = settings.voiceSensitivity == VoiceSensitivity.PROMINENT_ONLY,
                    onClick = { onSelectSensitivity(VoiceSensitivity.PROMINENT_ONLY) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sensitivity_prominent_card")
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 3. VOX Clip Silence Stop Delay (5s - 30s)
            Text(
                text = stringResource(R.string.settings_delay_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_delay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                delayPresets.forEach { seconds ->
                    val isSelected = settings.silenceDelaySeconds == seconds
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectSilenceDelay(seconds) },
                        label = {
                            Text(
                                text = "${seconds}s",
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("silence_delay_${seconds}s")
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. Session Silence Auto-Stop (Off, 1m, 2m, 3m, 5m, 10m — works in BOTH VOX & Normal modes)
            Text(
                text = stringResource(R.string.silence_autostop_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.silence_autostop_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                silenceAutoStopPresets.forEach { mins ->
                    FilterChip(
                        selected = settings.silenceAutoStopMinutes == mins,
                        onClick = { onSelectSilenceAutoStop(mins) },
                        label = { Text(if (mins == 0) "Off" else "$mins min") },
                        modifier = Modifier.testTag("settings_silence_autostop_$mins")
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4b. Battery & Efficiency Profile + Low-Battery Auto-Save (Presets + Custom %) + Keep Screen On
            Text(
                text = stringResource(R.string.settings_battery_profile_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_battery_profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BatteryOptimizationProfile.entries.forEach { profile ->
                    val selected = settings.batteryProfile == profile
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("battery_profile_${profile.id}")
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectBatteryProfile(profile) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = profile.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (profile == BatteryOptimizationProfile.ADAPTIVE_ECO) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = signalColors.readyContainer
                                        ) {
                                            Text(
                                                text = "Default",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = signalColors.onReadyContainer,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.settings_low_battery_stop_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.settings_low_battery_stop_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                lowBatteryPresets.forEach { pct ->
                    FilterChip(
                        selected = settings.lowBatteryStopPercent == pct,
                        onClick = { onSelectLowBatteryStop(pct) },
                        label = { Text(if (pct == 0) "Off" else "$pct%") },
                        modifier = Modifier.testTag("low_battery_stop_$pct")
                    )
                }
                FilterChip(
                    selected = isCustomLowBattery,
                    onClick = { showCustomBatteryDialog = true },
                    label = {
                        Text(
                            text = if (isCustomLowBattery) {
                                "${stringResource(R.string.low_battery_custom_chip)} (${settings.lowBatteryStopPercent}%)"
                            } else {
                                stringResource(R.string.low_battery_custom_chip)
                            }
                        )
                    },
                    modifier = Modifier.testTag("low_battery_stop_custom")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Keep Screen On While Recording Switch
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onToggleKeepScreenOn(!settings.keepScreenOn) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_keep_screen_on_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_keep_screen_on_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = settings.keepScreenOn,
                        onCheckedChange = onToggleKeepScreenOn,
                        modifier = Modifier.testTag("settings_keep_screen_on_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 5. Disk Usage Dashboard
            Text(
                text = stringResource(R.string.settings_disk_usage_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("disk_usage_dashboard_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Hero Total Consumed Readout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_disk_total_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = FormatUtils.formatFileSize(storageSummary.totalAppBytes),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("disk_usage_total_value")
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Storage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${storageSummary.sessionCount} active • ${storageSummary.trashedCount} trash",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Visual Multi-Segment Breakdown Bar (Active Recordings | Trash Bin | Cache)
                    val totalAppBytes = storageSummary.totalAppBytes
                    val activeColor = MaterialTheme.colorScheme.primary
                    val trashColor = MaterialTheme.colorScheme.error
                    val cacheColor = signalColors.warning

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (totalAppBytes > 0L) {
                            val activeWeight = (storageSummary.recordingsBytes.toFloat() / totalAppBytes.toFloat())
                                .coerceIn(0f, 1f)
                            val trashWeight = (storageSummary.trashedBytes.toFloat() / totalAppBytes.toFloat())
                                .coerceIn(0f, 1f)
                            val cacheWeight = (storageSummary.cacheBytes.toFloat() / totalAppBytes.toFloat())
                                .coerceIn(0f, 1f)

                            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                                if (activeWeight > 0.001f) {
                                    Box(
                                        modifier = Modifier
                                            .weight(activeWeight)
                                            .fillMaxHeight()
                                            .background(activeColor)
                                    )
                                }
                                if (trashWeight > 0.001f) {
                                    Box(
                                        modifier = Modifier
                                            .weight(trashWeight)
                                            .fillMaxHeight()
                                            .background(trashColor)
                                    )
                                }
                                if (cacheWeight > 0.001f) {
                                    Box(
                                        modifier = Modifier
                                            .weight(cacheWeight)
                                            .fillMaxHeight()
                                            .background(cacheColor)
                                    )
                                }
                            }
                        }
                    }

                    // Breakdown Legend Rows
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StorageMetricRow(
                            label = "${stringResource(R.string.settings_disk_recordings_label)} (${storageSummary.sessionCount})",
                            value = FormatUtils.formatFileSize(storageSummary.recordingsBytes),
                            dotColor = activeColor
                        )
                        StorageMetricRow(
                            label = "${stringResource(R.string.settings_disk_trash_label)} (${storageSummary.trashedCount})",
                            value = FormatUtils.formatFileSize(storageSummary.trashedBytes),
                            dotColor = trashColor
                        )
                        StorageMetricRow(
                            label = stringResource(R.string.settings_disk_cache_label),
                            value = FormatUtils.formatFileSize(storageSummary.cacheBytes),
                            dotColor = cacheColor
                        )
                    }

                    // Device Free Space Meter
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StorageMetricRow(
                            label = stringResource(R.string.settings_storage_free),
                            value = "${FormatUtils.formatFileSize(storageSummary.availableDeviceBytes)} free"
                        )
                        val usedFraction = if (storageSummary.totalDeviceBytes > 0L) {
                            1f - (storageSummary.availableDeviceBytes.toFloat() / storageSummary.totalDeviceBytes.toFloat())
                        } else {
                            0f
                        }.coerceIn(0f, 1f)

                        LinearProgressIndicator(
                            progress = { usedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }

                    // Clear Trash Bin Action Button (Prominent space-recovery CTA)
                    Button(
                        onClick = { showEmptyTrashConfirm = true },
                        enabled = storageSummary.hasTrashedItems,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_empty_trash_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (storageSummary.hasTrashedItems) {
                                stringResource(
                                    R.string.settings_disk_empty_trash_size,
                                    FormatUtils.formatFileSize(storageSummary.trashedBytes)
                                )
                            } else {
                                stringResource(R.string.settings_disk_trash_empty_label)
                            }
                        )
                    }

                    // Clear Temporary Export Cache Action
                    OutlinedButton(
                        onClick = onClearExportCache,
                        enabled = storageSummary.cacheBytes > 0L,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("clear_export_cache_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_clear_cache))
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 6. Background Recording Check
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("battery_check_button")
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenBatteryCheck)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_battery_button),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_battery_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showCustomBatteryDialog) {
        var inputText by remember(settings.lowBatteryStopPercent) {
            mutableStateOf(
                if (settings.lowBatteryStopPercent in 1..50) {
                    settings.lowBatteryStopPercent.toString()
                } else {
                    "15"
                }
            )
        }
        val parsed = inputText.trim().toIntOrNull()
        val isValid = parsed != null && parsed in 1..50

        AlertDialog(
            onDismissRequest = { showCustomBatteryDialog = false },
            title = { Text(stringResource(R.string.low_battery_custom_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.low_battery_custom_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(2)
                            inputText = digits
                        },
                        label = { Text(stringResource(R.string.low_battery_custom_hint)) },
                        suffix = { Text("%") },
                        singleLine = true,
                        isError = inputText.isNotEmpty() && !isValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_low_battery_input")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = isValid,
                    onClick = {
                        if (parsed != null && parsed in 1..50) {
                            onSelectLowBatteryStop(parsed)
                            showCustomBatteryDialog = false
                        }
                    },
                    modifier = Modifier.testTag("custom_low_battery_confirm")
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomBatteryDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showEmptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            title = { Text(stringResource(R.string.dialog_empty_trash_title)) },
            text = {
                Text(
                    "${stringResource(R.string.dialog_empty_trash_body)}\n\n" +
                        "Space to reclaim: ${FormatUtils.formatFileSize(storageSummary.trashedBytes)}"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyTrashConfirm = false
                        onEmptyTrash()
                    },
                    modifier = Modifier.testTag("settings_empty_trash_confirm")
                ) {
                    Text(
                        text = stringResource(R.string.action_empty_trash),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun StorageMetricRow(
    label: String,
    value: String,
    dotColor: Color? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SensitivityChoiceCard(
    title: String,
    badge: String?,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val signalColors = LocalSignalColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (badge != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = signalColors.readyContainer
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = signalColors.onReadyContainer,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryCheckSheet(
    isBatteryUnrestricted: Boolean,
    onRequestBatteryUnrestricted: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val signalColors = LocalSignalColors.current
    val oemProfile = BatteryOptimizationHelper.detectOemProfile()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.battery_guide_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.onboarding_subtitle_2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isBatteryUnrestricted) signalColors.readyContainer else signalColors.warningContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isBatteryUnrestricted) Icons.Rounded.CheckCircle else Icons.Rounded.TipsAndUpdates,
                        contentDescription = null,
                        tint = if (isBatteryUnrestricted) signalColors.ready else signalColors.onWarningContainer,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isBatteryUnrestricted) {
                                stringResource(R.string.onboarding_battery_allowed)
                            } else {
                                stringResource(R.string.battery_oem_tip_title, oemProfile.brandDisplayName)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isBatteryUnrestricted) signalColors.onReadyContainer else signalColors.onWarningContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = oemProfile.stepByStepTip,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isBatteryUnrestricted) signalColors.onReadyContainer else signalColors.onWarningContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (!isBatteryUnrestricted) {
                Button(
                    onClick = onRequestBatteryUnrestricted,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("battery_sheet_allow_button")
                ) {
                    Text(stringResource(R.string.onboarding_battery_needed))
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedButton(
                onClick = { BatteryOptimizationHelper.openOemBatterySettings(context) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("battery_sheet_oem_button")
            ) {
                Text(stringResource(R.string.battery_open_oem_settings))
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("battery_sheet_done_button")
            ) {
                Text(stringResource(R.string.battery_done))
            }
        }
    }
}
