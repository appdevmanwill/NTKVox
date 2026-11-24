package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.RecordingSession
import com.example.model.VoiceCleanConfig
import com.example.model.VoiceSensitivity
import com.example.util.FormatUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrimSplitSheet(
    session: RecordingSession,
    isProcessing: Boolean,
    onApplyTrim: (startMs: Long, endMs: Long, saveAsNewCopy: Boolean) -> Unit,
    onApplySplit: (splitMs: Long, keepOriginal: Boolean) -> Unit,
    onApplyRemoveSilence: (config: VoiceCleanConfig, saveAsNewCopy: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val totalMs = session.totalSpeechMillis.coerceAtLeast(1_000L)
    val totalFloat = totalMs.toFloat()

    var selectedMode by remember { mutableIntStateOf(0) } // 0 = Trim, 1 = Split, 2 = Clean Voice & Noise
    var trimRange by remember(session.id) { mutableStateOf(0f..totalFloat) }
    var splitPoint by remember(session.id) { mutableFloatStateOf(totalFloat / 2f) }
    var keepPauseMillis by remember { mutableLongStateOf(1_000L) }
    var cleanPreset by remember { mutableStateOf(VoiceSensitivity.NORMAL_ROOM) }
    var prominenceOffsetDb by remember { mutableFloatStateOf(VoiceSensitivity.NORMAL_ROOM.defaultProminenceOffsetDb) }
    var reduceSteadyNoise by remember { mutableStateOf(true) }
    var saveAsNewCopy by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = { if (!isProcessing) onDismiss() },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.edit_audio_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            PrimaryTabRow(
                selectedTabIndex = selectedMode,
                modifier = Modifier.fillMaxWidth()
            ) {
                val tabs = listOf(
                    Triple(0, Icons.Rounded.ContentCut, stringResource(R.string.tab_trim)) to "trim_tab_button",
                    Triple(1, Icons.Rounded.CallSplit, stringResource(R.string.tab_split)) to "split_tab_button",
                    Triple(2, Icons.Rounded.AutoFixHigh, stringResource(R.string.tab_clean_voice)) to "clean_silence_tab_button"
                )
                tabs.forEach { (item, tag) ->
                    val (idx, icon, label) = item
                    Tab(
                        selected = selectedMode == idx,
                        onClick = { selectedMode = idx },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        modifier = Modifier.testTag(tag)
                    )
                }
            }

            when (selectedMode) {
                0 -> {
                    Text(
                        text = stringResource(R.string.trim_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val startMs = trimRange.start.toLong().coerceIn(0L, totalMs)
                    val endMs = trimRange.endInclusive.toLong().coerceIn(startMs, totalMs)
                    val keptMs = (endMs - startMs).coerceAtLeast(0L)

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimeStatColumn("Start", FormatUtils.formatHms(startMs), modifier = Modifier.weight(1f))
                            TimeStatColumn("Kept", FormatUtils.formatHms(keptMs), highlight = true, modifier = Modifier.weight(1f))
                            TimeStatColumn("End", FormatUtils.formatHms(endMs), modifier = Modifier.weight(1f))
                        }
                    }

                    RangeSlider(
                        value = trimRange,
                        onValueChange = { range ->
                            if (range.endInclusive - range.start >= 500f) {
                                trimRange = range
                            }
                        },
                        valueRange = 0f..totalFloat,
                        enabled = !isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("trim_range_slider")
                    )
                }

                1 -> {
                    Text(
                        text = stringResource(R.string.split_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val splitMs = splitPoint.toLong().coerceIn(500L, (totalMs - 500L).coerceAtLeast(500L))
                    val part2Ms = (totalMs - splitMs).coerceAtLeast(0L)

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimeStatColumn("Part 1", FormatUtils.formatHms(splitMs), highlight = true, modifier = Modifier.weight(1f))
                            TimeStatColumn("Split At", FormatUtils.formatHms(splitMs), modifier = Modifier.weight(1f))
                            TimeStatColumn("Part 2", FormatUtils.formatHms(part2Ms), highlight = true, modifier = Modifier.weight(1f))
                        }
                    }

                    Slider(
                        value = splitPoint,
                        onValueChange = { splitPoint = it },
                        valueRange = 500f..(totalFloat - 500f).coerceAtLeast(500f),
                        enabled = !isProcessing && totalMs > 1_000L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("split_position_slider")
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.clean_voice_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 1. Voice Focus Presets (Quiet Voices, Normal Room, Prominent Only)
                            Text(
                                text = stringResource(R.string.clean_focus_preset_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                VoiceSensitivity.entries.forEach { preset ->
                                    FilterChip(
                                        selected = cleanPreset == preset,
                                        onClick = {
                                            cleanPreset = preset
                                            prominenceOffsetDb = preset.defaultProminenceOffsetDb
                                        },
                                        enabled = !isProcessing,
                                        label = { Text(preset.displayName) },
                                        modifier = Modifier.testTag("clean_sensitivity_${preset.id}")
                                    )
                                }
                            }

                            // 2. Manual Prominence Cutoff Slider (-24 dB .. -4 dB below main voice)
                            val offsetInt = prominenceOffsetDb.roundToInt().coerceIn(-24, -4)
                            val strictnessHint = when {
                                offsetInt <= -18 -> "Gentle — keeps softer and farther voices too"
                                offsetInt <= -12 -> "Balanced — cuts steady room noise & quiet distant chatter"
                                else -> "Strict — keeps prominent foreground speaker only"
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(R.string.clean_prominence_slider_label, offsetInt),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = strictnessHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Slider(
                                    value = prominenceOffsetDb,
                                    onValueChange = { prominenceOffsetDb = it.roundToInt().toFloat() },
                                    valueRange = -24f..-4f,
                                    steps = 19,
                                    enabled = !isProcessing,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("clean_prominence_slider")
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Gentle (-24 dB)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Strict (-4 dB)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // 3. Steady Background Noise Reduction Switch (ON by default)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.clean_reduce_steady_noise_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.clean_reduce_steady_noise_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Switch(
                                    checked = reduceSteadyNoise,
                                    onCheckedChange = { reduceSteadyNoise = it },
                                    enabled = !isProcessing,
                                    modifier = Modifier.testTag("clean_reduce_steady_noise_switch")
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // 4. Natural Pause Retention
                            Text(
                                text = stringResource(R.string.clean_silence_pause_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val pausePresets = listOf(500L to "0.5s", 1_000L to "1.0s", 2_000L to "2.0s")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                pausePresets.forEach { (ms, label) ->
                                    FilterChip(
                                        selected = keepPauseMillis == ms,
                                        onClick = { keepPauseMillis = ms },
                                        enabled = !isProcessing,
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("clean_pause_${ms}ms")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = saveAsNewCopy,
                    onCheckedChange = { saveAsNewCopy = it },
                    enabled = !isProcessing,
                    modifier = Modifier.testTag("trim_keep_original_checkbox")
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.save_as_new_copy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Button(
                onClick = {
                    when (selectedMode) {
                        0 -> onApplyTrim(trimRange.start.toLong(), trimRange.endInclusive.toLong(), saveAsNewCopy)
                        1 -> onApplySplit(splitPoint.toLong(), saveAsNewCopy)
                        else -> onApplyRemoveSilence(
                            VoiceCleanConfig(
                                focusPreset = cleanPreset,
                                prominenceOffsetDb = prominenceOffsetDb,
                                reduceSteadyNoise = reduceSteadyNoise,
                                keepPauseMillis = keepPauseMillis
                            ),
                            saveAsNewCopy
                        )
                    }
                },
                enabled = !isProcessing && (selectedMode == 2 || totalMs > 1_000L),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("apply_trim_split_button")
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.processing_audio_edit))
                } else {
                    Text(
                        text = when (selectedMode) {
                            0 -> stringResource(R.string.action_apply_trim)
                            1 -> stringResource(R.string.action_apply_split)
                            else -> stringResource(R.string.action_apply_clean_voice)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeStatColumn(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}
