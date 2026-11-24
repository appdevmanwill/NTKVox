package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.model.AppThemeMode

@Immutable
data class ClearVoxSignalColors(
    val listening: Color,
    val listeningContainer: Color,
    val onListeningContainer: Color,
    val recording: Color,
    val recordingContainer: Color,
    val onRecordingContainer: Color,
    val ready: Color,
    val readyContainer: Color,
    val onReadyContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

private val LightSignalColors = ClearVoxSignalColors(
    listening = ListeningBlue,
    listeningContainer = ListeningBlueContainer,
    onListeningContainer = OnListeningBlueContainer,
    recording = RecordingCoral,
    recordingContainer = RecordingCoralContainer,
    onRecordingContainer = OnRecordingCoralContainer,
    ready = ReadyTeal,
    readyContainer = ReadyTealContainer,
    onReadyContainer = OnReadyTealContainer,
    warning = WarmAmber,
    warningContainer = WarmAmberContainer,
    onWarningContainer = OnWarmAmberContainer
)

private val DarkSignalColors = ClearVoxSignalColors(
    listening = ListeningBlueDark,
    listeningContainer = ListeningBlueContainerDark,
    onListeningContainer = OnListeningBlueContainerDark,
    recording = RecordingCoralDark,
    recordingContainer = RecordingCoralContainerDark,
    onRecordingContainer = OnRecordingCoralContainerDark,
    ready = ReadyTealDark,
    readyContainer = ReadyTealContainerDark,
    onReadyContainer = OnReadyTealContainerDark,
    warning = Color(0xFFF5B856),
    warningContainer = Color(0xFF473108),
    onWarningContainer = Color(0xFFFFE4B5)
)

val LocalSignalColors = staticCompositionLocalOf { LightSignalColors }

private val DarkColorScheme = darkColorScheme(
    primary = ReadyTealDark,
    onPrimary = Color(0xFF002922),
    primaryContainer = ReadyTealContainerDark,
    onPrimaryContainer = OnReadyTealContainerDark,
    secondary = ListeningBlueDark,
    onSecondary = Color(0xFF002652),
    secondaryContainer = ListeningBlueContainerDark,
    onSecondaryContainer = OnListeningBlueContainerDark,
    tertiary = RecordingCoralDark,
    onTertiary = Color(0xFF4D0B05),
    tertiaryContainer = RecordingCoralContainerDark,
    onTertiaryContainer = OnRecordingCoralContainerDark,
    background = ObsidianBg,
    onBackground = ObsidianInk,
    surface = ObsidianSurface,
    onSurface = ObsidianInk,
    surfaceVariant = ObsidianSurfaceContainer,
    onSurfaceVariant = ObsidianInkMuted,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF283040),
    error = RecordingCoralDark,
    onError = Color(0xFF4D0B05),
    errorContainer = RecordingCoralContainerDark,
    onErrorContainer = OnRecordingCoralContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = ReadyTeal,
    onPrimary = Color.White,
    primaryContainer = ReadyTealContainer,
    onPrimaryContainer = OnReadyTealContainer,
    secondary = ListeningBlue,
    onSecondary = Color.White,
    secondaryContainer = ListeningBlueContainer,
    onSecondaryContainer = OnListeningBlueContainer,
    tertiary = RecordingCoral,
    onTertiary = Color.White,
    tertiaryContainer = RecordingCoralContainer,
    onTertiaryContainer = OnRecordingCoralContainer,
    background = StudioAlabaster,
    onBackground = StudioInk,
    surface = StudioSurface,
    onSurface = StudioInk,
    surfaceVariant = StudioSurfaceContainer,
    onSurfaceVariant = StudioInkMuted,
    surfaceContainerLowest = StudioSurface,
    surfaceContainerLow = StudioAlabaster,
    surfaceContainer = StudioSurfaceContainer,
    surfaceContainerHigh = StudioSurfaceContainerHigh,
    outline = StudioOutline,
    outlineVariant = Color(0xFFE6E3DB),
    error = RecordingCoral,
    onError = Color.White,
    errorContainer = RecordingCoralContainer,
    onErrorContainer = OnRecordingCoralContainer
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun ClearVoxTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val signalColors = if (darkTheme) DarkSignalColors else LightSignalColors

    CompositionLocalProvider(LocalSignalColors provides signalColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ClearVoxTypography,
            content = content
        )
    }
}
