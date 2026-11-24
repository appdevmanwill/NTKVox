package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HearingDisabled
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NoiseAware
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.example.R
import com.example.model.AudioFormatOption
import com.example.model.BatteryOptimizationProfile
import com.example.model.BatteryTelemetry
import com.example.model.RecordingMode
import com.example.model.RecordingSchedule
import com.example.service.RecorderServiceState
import com.example.service.RecorderStatus
import com.example.ui.theme.LocalSignalColors
import com.example.ui.theme.TabularTimerStyle
import com.example.util.FormatUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(
    recorderState: RecorderServiceState,
    recordingMode: RecordingMode,
    audioFormat: AudioFormatOption,
    batteryTelemetry: BatteryTelemetry,
    batteryProfile: BatteryOptimizationProfile,
    lowBatteryStopPercent: Int,
    availableStorageBytes: Long,
    autoStopMinutes: Int,
    silenceAutoStopMinutes: Int,
    nextSchedule: RecordingSchedule?,
    hasMicPermission: Boolean,
    onToggleVoiceActivated: (Boolean) -> Unit,
    onSelectFormat: (AudioFormatOption) -> Unit,
    onOpenSettings: () -> Unit,
    onSelectAutoStopMinutes: (Int) -> Unit,
    onSelectSilenceAutoStopMinutes: (Int) -> Unit,
    onOpenSchedules: () -> Unit,
    onDisarmStandby: () -> Unit,
    onToggleRecord: () -> Unit,
    onPauseResume: () -> Unit,
    onMarkMoment: () -> Unit,
    onRequestMicPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val signalColors = LocalSignalColors.current
    val isActive = recorderState.isSessionActive
    val isArmedStandby = recorderState.status == RecorderStatus.ARMED_STANDBY
    val effectiveMode = if (isActive) recorderState.activeRecordingMode else recordingMode
    val effectiveFormat = if (isActive) recorderState.activeFormat else audioFormat
    val isVoxMode = effectiveMode == RecordingMode.VOICE_ACTIVATED
    val isRecordingSpeech = recorderState.status == RecorderStatus.RECORDING
    val isListening = recorderState.status == RecorderStatus.LISTENING
    val isPausedManual = recorderState.status == RecorderStatus.PAUSED_MANUAL

    var showAutoStopDialog by remember { mutableStateOf(false) }
    var showSilenceStopDialog by remember { mutableStateOf(false) }
    var showQuickFormatDialog by remember { mutableStateOf(false) }

    val statusLabel = when (recorderState.status) {
        RecorderStatus.READY -> stringResource(R.string.status_ready)
        RecorderStatus.ARMED_STANDBY -> stringResource(R.string.status_armed_standby)
        RecorderStatus.LISTENING -> stringResource(R.string.status_listening)
        RecorderStatus.RECORDING -> if (isVoxMode) {
            stringResource(R.string.status_recording)
        } else {
            stringResource(R.string.status_recording_continuous)
        }
        RecorderStatus.PAUSED_MANUAL -> stringResource(R.string.status_paused_manual)
        RecorderStatus.PAUSED_CALL -> stringResource(R.string.status_paused_call)
        RecorderStatus.LOW_STORAGE_STOPPED -> stringResource(R.string.status_low_storage)
        RecorderStatus.LOW_BATTERY_STOPPED -> stringResource(R.string.status_low_battery)
    }

    val pillBg by animateColorAsState(
        targetValue = when (recorderState.status) {
            RecorderStatus.LISTENING, RecorderStatus.ARMED_STANDBY -> signalColors.listeningContainer
            RecorderStatus.RECORDING -> signalColors.recordingContainer
            RecorderStatus.PAUSED_MANUAL,
            RecorderStatus.PAUSED_CALL,
            RecorderStatus.LOW_STORAGE_STOPPED,
            RecorderStatus.LOW_BATTERY_STOPPED -> signalColors.warningContainer
            RecorderStatus.READY -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(260),
        label = "status_pill_bg"
    )

    val pillContent by animateColorAsState(
        targetValue = when (recorderState.status) {
            RecorderStatus.LISTENING, RecorderStatus.ARMED_STANDBY -> signalColors.onListeningContainer
            RecorderStatus.RECORDING -> signalColors.onRecordingContainer
            RecorderStatus.PAUSED_MANUAL,
            RecorderStatus.PAUSED_CALL,
            RecorderStatus.LOW_STORAGE_STOPPED,
            RecorderStatus.LOW_BATTERY_STOPPED -> signalColors.onWarningContainer
            RecorderStatus.READY -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(260),
        label = "status_pill_fg"
    )

    val ringAccent by animateColorAsState(
        targetValue = when (recorderState.status) {
            RecorderStatus.LISTENING, RecorderStatus.ARMED_STANDBY -> signalColors.listening
            RecorderStatus.RECORDING -> signalColors.recording
            RecorderStatus.PAUSED_MANUAL,
            RecorderStatus.PAUSED_CALL,
            RecorderStatus.LOW_STORAGE_STOPPED,
            RecorderStatus.LOW_BATTERY_STOPPED -> signalColors.warning
            RecorderStatus.READY -> signalColors.ready
        },
        animationSpec = tween(260),
        label = "ring_accent"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecordingSpeech -> signalColors.recording
            isListening -> signalColors.listening
            isActive -> signalColors.warning
            else -> signalColors.recording
        },
        animationSpec = tween(260),
        label = "record_btn_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breath"
    )

    val smoothAudioLevel by animateFloatAsState(
        targetValue = if (isActive && !isPausedManual) recorderState.audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f),
        label = "smooth_level"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Telemetry Strip + Status Pill + Mode Switch Card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Live Studio Telemetry Bar: Battery + Storage/Hours + Quick Format Pill
                StudioTelemetryRow(
                    batteryTelemetry = batteryTelemetry,
                    batteryProfile = batteryProfile,
                    lowBatteryStopPercent = lowBatteryStopPercent,
                    availableStorageBytes = availableStorageBytes,
                    audioFormat = effectiveFormat,
                    canChangeFormat = !isActive && !isArmedStandby,
                    onClickBattery = onOpenSettings,
                    onClickFormat = { showQuickFormatDialog = true }
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = CircleShape,
                    color = pillBg,
                    tonalElevation = 1.dp,
                    modifier = Modifier.testTag("record_status_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val dotScale = if (isActive || isArmedStandby) 0.85f + (idleBreath * 0.3f) else 1f
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .scale(dotScale)
                                .clip(CircleShape)
                                .background(ringAccent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = pillContent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Live Non-AI Acoustic Classifier Feedback Pill (Noise Filtered / Prominent Voice)
                val showNoiseFiltered = isActive && isVoxMode && isListening && recorderState.isNoiseRejected
                val showProminentVoice = isActive && isVoxMode && isRecordingSpeech
                AnimatedVisibility(
                    visible = showNoiseFiltered || showProminentVoice,
                    enter = fadeIn(tween(180)) + expandVertically(),
                    exit = fadeOut(tween(180)) + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (showNoiseFiltered) signalColors.warningContainer else signalColors.readyContainer,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("record_acoustic_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (showNoiseFiltered) Icons.Rounded.NoiseAware else Icons.Rounded.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (showNoiseFiltered) signalColors.onWarningContainer else signalColors.onReadyContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (showNoiseFiltered) {
                                    stringResource(R.string.acoustic_noise_filtered)
                                } else {
                                    stringResource(R.string.acoustic_voice_detected)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (showNoiseFiltered) signalColors.onWarningContainer else signalColors.onReadyContainer
                            )
                        }
                    }
                }

                // Live countdown chips for Auto-Stop or Silence Auto-Stop while recording
                val autoStopRem = recorderState.autoStopRemainingMillis
                val silenceRem = recorderState.silenceCountdownMillis
                if (isActive && (autoStopRem != null || silenceRem != null)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (autoStopRem != null) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.auto_stop_remaining,
                                        FormatUtils.formatHms(autoStopRem)
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (silenceRem != null) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = signalColors.warningContainer
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.silence_autostop_remaining,
                                        FormatUtils.formatCompactDuration(silenceRem)
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = signalColors.onWarningContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                VoiceActivatedModeCard(
                    isVoxEnabled = isVoxMode,
                    enabled = !isActive && !isArmedStandby,
                    onToggle = onToggleVoiceActivated
                )

                // Armed Standby Banner
                AnimatedVisibility(
                    visible = isArmedStandby,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = signalColors.listeningContainer,
                        border = BorderStroke(1.dp, signalColors.listening.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = signalColors.listening
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = recorderState.armedScheduleLabel ?: stringResource(R.string.status_armed_standby),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = signalColors.onListeningContainer
                                    )
                                    val startStr = recorderState.armedStartTimeMillis?.let {
                                        FormatUtils.formatDateTimeShort(it)
                                    } ?: ""
                                    val stopStr = recorderState.scheduledStopTimeMillis?.let {
                                        FormatUtils.formatDateTimeShort(it)
                                    } ?: ""
                                    Text(
                                        text = "Starts $startStr\nStops  $stopStr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = signalColors.onListeningContainer
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onDisarmStandby,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("record_disarm_standby_button")
                            ) {
                                Text(stringResource(R.string.action_disarm_standby))
                            }
                        }
                    }
                }

                if (!hasMicPermission) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = signalColors.warningContainer,
                        border = BorderStroke(1.dp, signalColors.warning.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = signalColors.onWarningContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.hint_permission_needed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = signalColors.onWarningContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onRequestMicPermission,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = signalColors.warning,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.testTag("grant_mic_button")
                            ) {
                                Text(text = stringResource(R.string.action_grant_mic))
                            }
                        }
                    }
                }
            }

            // Centerpiece: Live Scrolling Waveform + Animated Audio Level Ring + Giant Start / Stop Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LiveWaveformStrip(
                    audioLevel = recorderState.audioLevel,
                    isActive = isActive && !isPausedManual,
                    barColor = ringAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(22.dp)
                        .testTag("live_waveform_strip")
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(154.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = this.center
                        val baseRadius = size.minDimension * 0.35f
                        val dynamicBoost = if (isActive && !isPausedManual) {
                            (smoothAudioLevel * size.minDimension * 0.14f) + (idleBreath * 6.dp.toPx())
                        } else {
                            idleBreath * 4.dp.toPx()
                        }

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ringAccent.copy(alpha = if (isActive) 0.24f else 0.10f),
                                    ringAccent.copy(alpha = 0.0f)
                                ),
                                center = center,
                                radius = (baseRadius + dynamicBoost + 22.dp.toPx()).coerceAtMost(size.minDimension / 2f)
                            ),
                            radius = (baseRadius + dynamicBoost + 22.dp.toPx()).coerceAtMost(size.minDimension / 2f),
                            center = center
                        )

                        drawCircle(
                            color = ringAccent.copy(alpha = if (isActive) 0.22f + (smoothAudioLevel * 0.2f) else 0.08f),
                            radius = (baseRadius + dynamicBoost + 10.dp.toPx()).coerceAtMost(size.minDimension * 0.48f),
                            center = center
                        )

                        drawCircle(
                            color = ringAccent.copy(alpha = if (isActive) 0.34f else 0.14f),
                            radius = (baseRadius + (dynamicBoost * 0.45f) + 4.dp.toPx()).coerceAtMost(size.minDimension * 0.46f),
                            center = center
                        )
                    }

                    val buttonDescription = when {
                        isActive -> stringResource(R.string.cd_stop_recording)
                        isVoxMode -> stringResource(R.string.cd_start_recording)
                        else -> stringResource(R.string.cd_start_continuous)
                    }

                    Surface(
                        shape = CircleShape,
                        color = buttonColor,
                        shadowElevation = if (isActive) 10.dp else 6.dp,
                        modifier = Modifier
                            .size(108.dp)
                            .testTag("record_toggle_button")
                            .semantics { contentDescription = buttonDescription }
                            .clip(CircleShape)
                            .clickable(role = Role.Button) {
                                if (hasMicPermission) onToggleRecord() else onRequestMicPermission()
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = if (isActive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isActive) stringResource(R.string.action_stop) else stringResource(R.string.tab_record),
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Active Session Secondary Controls: Pause/Resume & One-Tap Mark Moment
                AnimatedVisibility(
                    visible = isActive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onPauseResume,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("record_pause_resume_button")
                        ) {
                            Icon(
                                imageVector = if (isPausedManual) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isPausedManual) {
                                    stringResource(R.string.action_resume)
                                } else {
                                    stringResource(R.string.action_pause)
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val fullMarkDesc = stringResource(R.string.action_mark_moment)
                        FilledTonalButton(
                            onClick = onMarkMoment,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("record_mark_moment_button")
                                .semantics { contentDescription = fullMarkDesc }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BookmarkAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val bmLabel = if (recorderState.bookmarkCount > 0) {
                                "${stringResource(R.string.action_mark_short)} (${recorderState.bookmarkCount})"
                            } else {
                                stringResource(R.string.action_mark_short)
                            }
                            Text(text = bmLabel, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Bottom Clean Counters, Quick Schedule/Timers Strip & Reassurance
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isVoxMode) {
                        MetricCounterCard(
                            label = stringResource(R.string.label_session_time),
                            value = FormatUtils.formatHms(recorderState.sessionElapsedMillis),
                            subtext = if (isActive) "Active monitor" else "Waiting to start",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("session_time_counter")
                        )

                        val clipWord = if (recorderState.clipCount == 1) "clip" else "clips"
                        MetricCounterCard(
                            label = stringResource(R.string.label_speech_captured),
                            value = FormatUtils.formatHms(recorderState.speechCapturedMillis),
                            subtext = "${recorderState.clipCount} $clipWord",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("speech_captured_counter")
                        )
                    } else {
                        MetricCounterCard(
                            label = stringResource(R.string.label_recorded_time),
                            value = FormatUtils.formatHms(recorderState.speechCapturedMillis),
                            subtext = if (isActive) "Recording nonstop" else "Normal recorder",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("speech_captured_counter")
                        )

                        val segWord = if (recorderState.clipCount == 1) "segment" else "segments"
                        MetricCounterCard(
                            label = stringResource(R.string.label_session_time),
                            value = FormatUtils.formatHms(recorderState.sessionElapsedMillis),
                            subtext = "${recorderState.clipCount} $segWord",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("session_time_counter")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Compact Single-Row Scrollable Schedules & Timers Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    val schedulePillText = nextSchedule?.let {
                        "${it.label.take(12)} • ${FormatUtils.formatWallClockTime(it.startTimeMillis)}"
                    } ?: stringResource(R.string.action_schedules)

                    FilterChip(
                        selected = nextSchedule != null || isArmedStandby,
                        onClick = onOpenSchedules,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = schedulePillText,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.testTag("record_schedules_chip")
                    )

                    FilterChip(
                        selected = autoStopMinutes > 0,
                        onClick = { showAutoStopDialog = true },
                        enabled = !isActive,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (autoStopMinutes > 0) {
                                    "Timer: ${formatTimerPreset(autoStopMinutes)}"
                                } else {
                                    "Timer: Off"
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        modifier = Modifier.testTag("record_autostop_chip")
                    )

                    FilterChip(
                        selected = silenceAutoStopMinutes > 0,
                        onClick = { showSilenceStopDialog = true },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.HearingDisabled,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (silenceAutoStopMinutes > 0) {
                                    "Silence: ${silenceAutoStopMinutes}m"
                                } else {
                                    "Silence: Off"
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        modifier = Modifier.testTag("record_silence_autostop_chip")
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when {
                        isActive -> stringResource(R.string.hint_background_safe)
                        isVoxMode -> stringResource(R.string.hint_tap_to_start)
                        else -> stringResource(R.string.hint_tap_to_start_continuous)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                recorderState.errorMessage?.let { err ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showQuickFormatDialog) {
        AlertDialog(
            onDismissRequest = { showQuickFormatDialog = false },
            title = { Text(stringResource(R.string.quick_format_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioFormatOption.entries.forEach { option ->
                        val selected = option == audioFormat
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onSelectFormat(option)
                                    showQuickFormatDialog = false
                                }
                                .testTag("quick_format_${option.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${option.powerBadge} • ${option.recommendationBadge}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (selected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuickFormatDialog = false }) {
                    Text(stringResource(R.string.battery_done))
                }
            }
        )
    }

    if (showAutoStopDialog) {
        val presets = listOf(0, 30, 60, 120, 240, 480, 720)
        AlertDialog(
            onDismissRequest = { showAutoStopDialog = false },
            title = { Text(stringResource(R.string.auto_stop_label)) },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { mins ->
                        FilterChip(
                            selected = autoStopMinutes == mins,
                            onClick = {
                                onSelectAutoStopMinutes(mins)
                                showAutoStopDialog = false
                            },
                            label = { Text(formatTimerPreset(mins)) },
                            modifier = Modifier.testTag("autostop_preset_$mins")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoStopDialog = false }) {
                    Text(stringResource(R.string.battery_done))
                }
            }
        )
    }

    if (showSilenceStopDialog) {
        val silencePresets = listOf(0, 1, 2, 3, 5, 10)
        AlertDialog(
            onDismissRequest = { showSilenceStopDialog = false },
            title = { Text(stringResource(R.string.silence_autostop_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.silence_autostop_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        silencePresets.forEach { mins ->
                            FilterChip(
                                selected = silenceAutoStopMinutes == mins,
                                onClick = {
                                    onSelectSilenceAutoStopMinutes(mins)
                                    showSilenceStopDialog = false
                                },
                                label = { Text(if (mins == 0) "Off" else "$mins min") },
                                modifier = Modifier.testTag("silence_autostop_preset_$mins")
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSilenceStopDialog = false }) {
                    Text(stringResource(R.string.battery_done))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioTelemetryRow(
    batteryTelemetry: BatteryTelemetry,
    batteryProfile: BatteryOptimizationProfile,
    lowBatteryStopPercent: Int,
    availableStorageBytes: Long,
    audioFormat: AudioFormatOption,
    canChangeFormat: Boolean,
    onClickBattery: () -> Unit,
    onClickFormat: () -> Unit
) {
    val signalColors = LocalSignalColors.current
    val pct = batteryTelemetry.levelPercent
    val isLowBatteryWarning = batteryTelemetry.isAvailable &&
        !batteryTelemetry.isCharging &&
        lowBatteryStopPercent > 0 &&
        pct <= (lowBatteryStopPercent + 5)

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Live Battery Status Indicator Pill
        val batBg = when {
            isLowBatteryWarning -> signalColors.warningContainer
            batteryTelemetry.isCharging -> signalColors.readyContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        }
        val batFg = when {
            isLowBatteryWarning -> signalColors.onWarningContainer
            batteryTelemetry.isCharging -> signalColors.onReadyContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = batBg,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClickBattery)
                .testTag("telemetry_battery_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (batteryTelemetry.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                    contentDescription = null,
                    tint = batFg,
                    modifier = Modifier.size(14.dp)
                )
                val pctLabel = if (batteryTelemetry.isAvailable) "$pct%" else "—"
                Text(
                    text = "$pctLabel • ${batteryProfile.shortLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = batFg
                )
            }
        }

        // 2. Live Free Storage + Estimated Recording Hours Pill
        if (availableStorageBytes > 0L) {
            val estHours = FormatUtils.formatEstimatedHours(
                availableBytes = availableStorageBytes,
                bytesPerHour = audioFormat.estimatedBytesPerHour
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag("telemetry_storage_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SdStorage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${FormatUtils.formatFileSize(availableStorageBytes)} • $estHours",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. Tappable Quick Format Pill
        Surface(
            shape = RoundedCornerShape(50),
            color = signalColors.listeningContainer,
            border = BorderStroke(1.dp, signalColors.listening.copy(alpha = 0.25f)),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = canChangeFormat, onClick = onClickFormat)
                .testTag("telemetry_format_pill")
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 10.dp,
                    end = if (canChangeFormat) 4.dp else 10.dp,
                    top = 5.dp,
                    bottom = 5.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = audioFormat.shortBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = signalColors.onListeningContainer
                )
                if (canChangeFormat) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = signalColors.onListeningContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveWaveformStrip(
    audioLevel: Float,
    isActive: Boolean,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val barCount = 36
    val latestLevel by rememberUpdatedState(audioLevel)
    val levels = remember {
        mutableStateListOf<Float>().apply { repeat(barCount) { add(0.06f) } }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) {
                val clamped = latestLevel.coerceIn(0.06f, 1f)
                if (levels.size >= barCount) levels.removeAt(0)
                levels.add(clamped)
                delay(75L)
            }
        } else {
            for (i in levels.indices) {
                levels[i] = 0.06f
            }
        }
    }

    Canvas(modifier = modifier) {
        val count = levels.size.coerceAtLeast(1)
        val totalWidth = size.width
        val slotWidth = totalWidth / count
        val barWidth = (slotWidth * 0.56f).coerceIn(2.dp.toPx(), 6.dp.toPx())
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.92f
        val minBarHeight = 4.dp.toPx()

        for (i in 0 until count) {
            val amp = levels[i]
            val h = (minBarHeight + amp * (maxBarHeight - minBarHeight)).coerceAtMost(maxBarHeight)
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            val alpha = if (isActive) 0.35f + (i.toFloat() / count) * 0.65f else 0.22f
            drawRoundRect(
                color = barColor.copy(alpha = alpha),
                topLeft = Offset(x, centerY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = corner
            )
        }
    }
}

private fun formatTimerPreset(minutes: Int): String = when {
    minutes <= 0 -> "Off"
    minutes < 60 -> "${minutes}m"
    else -> "${minutes / 60}h"
}

@Composable
private fun VoiceActivatedModeCard(
    isVoxEnabled: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val signalColors = LocalSignalColors.current
    val badgeBg = if (isVoxEnabled) {
        signalColors.listeningContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val badgeFg = if (isVoxEnabled) {
        signalColors.onListeningContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (isVoxEnabled) {
                signalColors.listening.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.72f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onToggle(!isVoxEnabled) }
            .testTag("vox_mode_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = badgeBg,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isVoxEnabled) Icons.Rounded.GraphicEq else Icons.Rounded.FiberManualRecord,
                        contentDescription = null,
                        tint = if (isVoxEnabled) signalColors.listening else signalColors.recording,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.recording_mode_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeBg
                    ) {
                        Text(
                            text = if (isVoxEnabled) {
                                stringResource(R.string.recording_mode_vox_badge)
                            } else {
                                stringResource(R.string.recording_mode_continuous_badge)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (!enabled) {
                        stringResource(R.string.recording_mode_locked_hint)
                    } else if (isVoxEnabled) {
                        stringResource(R.string.recording_mode_vox_desc)
                    } else {
                        stringResource(R.string.recording_mode_continuous_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = isVoxEnabled,
                onCheckedChange = if (enabled) onToggle else null,
                enabled = enabled,
                modifier = Modifier.testTag("vox_mode_switch")
            )
        }
    }
}

@Composable
private fun MetricCounterCard(
    label: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val approxCardInnerWidthDp = ((config.screenWidthDp.coerceAtMost(560) - 42) / 2f - 20f).coerceAtLeast(96f)
    val timerFontSize = (approxCardInnerWidthDp / (5.6f * fontScale)).coerceIn(13f, 22f).sp

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = TabularTimerStyle.copy(
                    fontSize = timerFontSize,
                    lineHeight = timerFontSize * 1.15f
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtext,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
