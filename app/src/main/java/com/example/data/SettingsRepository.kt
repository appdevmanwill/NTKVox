package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppThemeMode
import com.example.model.AudioFormatOption
import com.example.model.BatteryOptimizationProfile
import com.example.model.RecordingMode
import com.example.model.UserSettings
import com.example.model.VoiceSensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.clearVoxDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clearvox_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val AUDIO_FORMAT = stringPreferencesKey("audio_format")
        val VOICE_SENSITIVITY = stringPreferencesKey("voice_sensitivity")
        val SILENCE_DELAY_SECONDS = intPreferencesKey("silence_delay_seconds")
        val AUTO_STOP_MINUTES = intPreferencesKey("auto_stop_minutes")
        val SILENCE_AUTO_STOP_MINUTES = intPreferencesKey("silence_auto_stop_minutes")
        val DEFAULT_PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
        val BATTERY_PROFILE = stringPreferencesKey("battery_profile")
        val LOW_BATTERY_STOP_PERCENT = intPreferencesKey("low_battery_stop_percent")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val settingsFlow: Flow<UserSettings> = context.clearVoxDataStore.data.map { prefs ->
        UserSettings(
            recordingMode = RecordingMode.fromId(prefs[Keys.RECORDING_MODE]),
            audioFormat = AudioFormatOption.fromId(prefs[Keys.AUDIO_FORMAT]),
            voiceSensitivity = VoiceSensitivity.fromId(prefs[Keys.VOICE_SENSITIVITY]),
            silenceDelaySeconds = prefs[Keys.SILENCE_DELAY_SECONDS] ?: 10,
            autoStopMinutes = prefs[Keys.AUTO_STOP_MINUTES] ?: 0,
            silenceAutoStopMinutes = prefs[Keys.SILENCE_AUTO_STOP_MINUTES] ?: 0,
            defaultPlaybackSpeed = (prefs[Keys.DEFAULT_PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.75f, 2.0f),
            batteryProfile = BatteryOptimizationProfile.fromId(prefs[Keys.BATTERY_PROFILE]),
            lowBatteryStopPercent = (prefs[Keys.LOW_BATTERY_STOP_PERCENT] ?: 5).coerceIn(0, 50),
            themeMode = AppThemeMode.fromId(prefs[Keys.THEME_MODE]),
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
        )
    }

    suspend fun setRecordingMode(mode: RecordingMode) {
        context.clearVoxDataStore.edit { it[Keys.RECORDING_MODE] = mode.id }
    }

    suspend fun setAudioFormat(format: AudioFormatOption) {
        context.clearVoxDataStore.edit { it[Keys.AUDIO_FORMAT] = format.id }
    }

    suspend fun setVoiceSensitivity(sensitivity: VoiceSensitivity) {
        context.clearVoxDataStore.edit { it[Keys.VOICE_SENSITIVITY] = sensitivity.id }
    }

    suspend fun setSilenceDelaySeconds(seconds: Int) {
        context.clearVoxDataStore.edit { it[Keys.SILENCE_DELAY_SECONDS] = seconds.coerceIn(5, 60) }
    }

    suspend fun setAutoStopMinutes(minutes: Int) {
        context.clearVoxDataStore.edit { it[Keys.AUTO_STOP_MINUTES] = minutes.coerceIn(0, 24 * 60) }
    }

    suspend fun setSilenceAutoStopMinutes(minutes: Int) {
        context.clearVoxDataStore.edit { it[Keys.SILENCE_AUTO_STOP_MINUTES] = minutes.coerceIn(0, 60) }
    }

    suspend fun setDefaultPlaybackSpeed(speed: Float) {
        context.clearVoxDataStore.edit { it[Keys.DEFAULT_PLAYBACK_SPEED] = speed.coerceIn(0.75f, 2.0f) }
    }

    suspend fun setBatteryProfile(profile: BatteryOptimizationProfile) {
        context.clearVoxDataStore.edit { it[Keys.BATTERY_PROFILE] = profile.id }
    }

    suspend fun setLowBatteryStopPercent(percent: Int) {
        context.clearVoxDataStore.edit { it[Keys.LOW_BATTERY_STOP_PERCENT] = percent.coerceIn(0, 50) }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.clearVoxDataStore.edit { it[Keys.THEME_MODE] = mode.id }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.clearVoxDataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.clearVoxDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }
}
