package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalSignalColors
import com.example.util.BatteryOptimizationHelper

@Composable
fun OnboardingScreen(
    hasMicPermission: Boolean,
    hasNotificationPermission: Boolean,
    isBatteryUnrestricted: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val signalColors = LocalSignalColors.current
    val oemProfile = BatteryOptimizationHelper.detectOemProfile()
    var step by rememberSaveable { mutableIntStateOf(1) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_step_badge, step),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Image(
                        painter = painterResource(id = R.drawable.img_ntkvox_hero),
                        contentDescription = stringResource(R.string.onboarding_title_1),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(24.dp))
                    )

                    Spacer(modifier = Modifier.height(22.dp))

                    if (step == 1) {
                        Text(
                            text = stringResource(R.string.onboarding_title_1),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_subtitle_1),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(22.dp))

                        PermissionInfoRow(
                            icon = Icons.Rounded.Mic,
                            title = stringResource(R.string.onboarding_mic_title),
                            description = stringResource(R.string.onboarding_mic_desc),
                            granted = hasMicPermission
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PermissionInfoRow(
                            icon = Icons.Rounded.NotificationsActive,
                            title = stringResource(R.string.onboarding_notif_title),
                            description = stringResource(R.string.onboarding_notif_desc),
                            granted = hasNotificationPermission
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.onboarding_title_2),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_subtitle_2),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(22.dp))

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (isBatteryUnrestricted) signalColors.readyContainer else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isBatteryUnrestricted) Icons.Rounded.CheckCircle else Icons.Rounded.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = if (isBatteryUnrestricted) signalColors.ready else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isBatteryUnrestricted) {
                                        stringResource(R.string.onboarding_battery_allowed)
                                    } else {
                                        oemProfile.stepByStepTip
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isBatteryUnrestricted) signalColors.onReadyContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (oemProfile.isAggressiveOem) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = signalColors.warningContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.TipsAndUpdates,
                                            contentDescription = null,
                                            tint = signalColors.onWarningContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.battery_oem_tip_title, oemProfile.brandDisplayName),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = signalColors.onWarningContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = oemProfile.stepByStepTip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = signalColors.onWarningContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = { BatteryOptimizationHelper.openOemBatterySettings(context) },
                                        modifier = Modifier.testTag("onboarding_oem_battery_button")
                                    ) {
                                        Text(stringResource(R.string.battery_open_oem_settings))
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (step == 1) {
                        Button(
                            onClick = {
                                if (!hasMicPermission || !hasNotificationPermission) {
                                    onRequestPermissions()
                                }
                                step = 2
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("onboarding_step1_button")
                        ) {
                            Text(
                                text = if (hasMicPermission && hasNotificationPermission) {
                                    stringResource(R.string.onboarding_step1_next)
                                } else {
                                    stringResource(R.string.onboarding_step1_cta)
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    } else {
                        if (!isBatteryUnrestricted) {
                            FilledTonalButton(
                                onClick = onRequestBatteryUnrestricted,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("onboarding_battery_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_battery_needed),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        Button(
                            onClick = onComplete,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("onboarding_finish_button")
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_finish),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        if (!isBatteryUnrestricted) {
                            TextButton(
                                onClick = onComplete,
                                modifier = Modifier.testTag("onboarding_skip_button")
                            ) {
                                Text(stringResource(R.string.onboarding_skip))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionInfoRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean
) {
    val signalColors = LocalSignalColors.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (granted) signalColors.readyContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (granted) Icons.Rounded.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (granted) signalColors.ready else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
