package com.example.model

enum class AudioFormatOption(
    val id: String,
    val displayName: String,
    val shortBadge: String,
    val extension: String,
    val mimeType: String,
    val recommendationBadge: String,
    val powerBadge: String,
    val estimatedBytesPerHour: Long,
    val description: String
) {
    OGG_OPUS(
        id = "ogg_opus",
        displayName = "Ogg Opus (.ogg)",
        shortBadge = "OGG",
        extension = "ogg",
        mimeType = "audio/ogg",
        recommendationBadge = "Recommended • Smallest & Crash-Safe",
        powerBadge = "Ultra-Low Battery",
        estimatedBytesPerHour = 15L * 1024L * 1024L,
        description = "Tiny files (~15 MB/hr) tuned for clear speech and long sessions."
    ),
    M4A_AAC(
        id = "m4a_aac",
        displayName = "M4A / AAC (.m4a)",
        shortBadge = "M4A",
        extension = "m4a",
        mimeType = "audio/mp4",
        recommendationBadge = "Best for Sharing Everywhere",
        powerBadge = "Ultra-Low Battery",
        estimatedBytesPerHour = 28L * 1024L * 1024L,
        description = "Plays cleanly on iPhone, Mac, Windows, and Android."
    ),
    MP3(
        id = "mp3",
        displayName = "MP3 (.mp3)",
        shortBadge = "MP3",
        extension = "mp3",
        mimeType = "audio/mpeg",
        recommendationBadge = "Classic Universal Compatibility",
        powerBadge = "Medium Battery",
        estimatedBytesPerHour = 28L * 1024L * 1024L,
        description = "Works on older players, car stereos, and simple archiving."
    ),
    FLAC(
        id = "flac",
        displayName = "FLAC (.flac)",
        shortBadge = "FLAC",
        extension = "flac",
        mimeType = "audio/flac",
        recommendationBadge = "Best Lossless Quality",
        powerBadge = "Low Battery",
        estimatedBytesPerHour = 65L * 1024L * 1024L,
        description = "Full original sound quality in smaller files than WAV."
    ),
    WAV(
        id = "wav",
        displayName = "WAV (.wav)",
        shortBadge = "WAV",
        extension = "wav",
        mimeType = "audio/wav",
        recommendationBadge = "Uncompressed • Audio Editing",
        powerBadge = "Ultra-Low Battery",
        estimatedBytesPerHour = 115L * 1024L * 1024L,
        description = "Uncompressed audio for editing apps, safely split for long sessions."
    );

    companion object {
        fun fromId(id: String?): AudioFormatOption =
            entries.firstOrNull { it.id == id } ?: OGG_OPUS
    }
}

enum class BatteryOptimizationProfile(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val badge: String?,
    val description: String,
    val syncIntervalSeconds: Int,
    val screenOffNotifIntervalSeconds: Int
) {
    ADAPTIVE_ECO(
        id = "adaptive_eco",
        displayName = "Adaptive Eco",
        shortLabel = "Eco",
        badge = "Recommended",
        description = "Smooth live meters while screen is on; automatically saves battery when screen is off.",
        syncIntervalSeconds = 30,
        screenOffNotifIntervalSeconds = 30
    ),
    ULTRA_SAVER(
        id = "ultra_saver",
        displayName = "Ultra Battery Saver",
        shortLabel = "Ultra",
        badge = "Max Endurance",
        description = "Extra-efficient background mode built for multi-day battery life.",
        syncIntervalSeconds = 30,
        screenOffNotifIntervalSeconds = 45
    ),
    BALANCED(
        id = "balanced",
        displayName = "Balanced",
        shortLabel = "Balanced",
        badge = null,
        description = "Faster live meters and frequent saves while keeping standby battery-friendly.",
        syncIntervalSeconds = 10,
        screenOffNotifIntervalSeconds = 10
    );

    companion object {
        fun fromId(id: String?): BatteryOptimizationProfile =
            entries.firstOrNull { it.id == id } ?: ADAPTIVE_ECO
    }
}

enum class AppThemeMode(
    val id: String,
    val displayName: String,
    val description: String
) {
    SYSTEM(
        id = "system",
        displayName = "System",
        description = "Follows your phone's light or dark theme automatically."
    ),
    LIGHT(
        id = "light",
        displayName = "Light",
        description = "Bright alabaster studio theme for daytime visibility."
    ),
    DARK(
        id = "dark",
        displayName = "Dark",
        description = "Deep obsidian studio theme for low-light comfort and OLED savings."
    );

    fun next(): AppThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }

    companion object {
        fun fromId(id: String?): AppThemeMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class RecordingSortOrder(
    val id: String,
    val displayName: String
) {
    NEWEST("newest", "Newest first"),
    OLDEST("oldest", "Oldest first"),
    LONGEST("longest", "Longest duration"),
    LARGEST("largest", "Largest size"),
    TITLE_AZ("title_az", "Name A–Z");

    companion object {
        fun fromId(id: String?): RecordingSortOrder =
            entries.firstOrNull { it.id == id } ?: NEWEST
    }
}

data class BatteryTelemetry(
    val levelPercent: Int = -1,
    val isCharging: Boolean = false
) {
    val isAvailable: Boolean
        get() = levelPercent in 0..100
}

data class VoiceCleanConfig(
    val focusPreset: VoiceSensitivity = VoiceSensitivity.NORMAL_ROOM,
    val prominenceOffsetDb: Float = focusPreset.defaultProminenceOffsetDb,
    val reduceSteadyNoise: Boolean = true,
    val keepPauseMillis: Long = 1_000L
)

data class SilenceRemovalResult(
    val session: RecordingSession,
    val originalDurationMillis: Long,
    val cleanedDurationMillis: Long,
    val detectedNoiseFloorDb: Float = -65f,
    val detectedProminentPeakDb: Float = -20f,
    val noiseReductionApplied: Boolean = false
) {
    val removedSilenceMillis: Long
        get() = (originalDurationMillis - cleanedDurationMillis).coerceAtLeast(0L)
}

enum class VoiceSensitivity(
    val id: String,
    val displayName: String,
    val speechStartThreshold: Float,
    val speechStopThreshold: Float,
    val snrMarginDb: Float,
    val minAbsoluteSpeechDb: Float,
    val maxSpectralFlatness: Float,
    val minVoiceBandRatio: Float,
    val defaultProminenceOffsetDb: Float
) {
    QUIET_VOICES(
        id = "quiet_voices",
        displayName = "Quiet Voices",
        speechStartThreshold = 0.35f,
        speechStopThreshold = 0.22f,
        snrMarginDb = 5.5f,
        minAbsoluteSpeechDb = -56f,
        maxSpectralFlatness = 0.72f,
        minVoiceBandRatio = 0.28f,
        defaultProminenceOffsetDb = -22f
    ),
    NORMAL_ROOM(
        id = "normal_room",
        displayName = "Normal Room",
        speechStartThreshold = 0.50f,
        speechStopThreshold = 0.30f,
        snrMarginDb = 9.5f,
        minAbsoluteSpeechDb = -48f,
        maxSpectralFlatness = 0.58f,
        minVoiceBandRatio = 0.38f,
        defaultProminenceOffsetDb = -15f
    ),
    PROMINENT_ONLY(
        id = "prominent_only",
        displayName = "Prominent Only",
        speechStartThreshold = 0.62f,
        speechStopThreshold = 0.38f,
        snrMarginDb = 14.0f,
        minAbsoluteSpeechDb = -40f,
        maxSpectralFlatness = 0.48f,
        minVoiceBandRatio = 0.45f,
        defaultProminenceOffsetDb = -9f
    );

    companion object {
        fun fromId(id: String?): VoiceSensitivity =
            entries.firstOrNull { it.id == id } ?: QUIET_VOICES
    }
}

enum class RecordingMode(
    val id: String,
    val displayName: String,
    val shortBadge: String
) {
    VOICE_ACTIVATED(
        id = "voice_activated",
        displayName = "Voice-Activated (VOX)",
        shortBadge = "VOX"
    ),
    CONTINUOUS(
        id = "continuous",
        displayName = "Continuous (Normal)",
        shortBadge = "Normal"
    );

    companion object {
        fun fromId(id: String?): RecordingMode =
            entries.firstOrNull { it.id == id } ?: VOICE_ACTIVATED
    }
}

enum class RecordingCategory(
    val id: String,
    val displayName: String
) {
    GENERAL("general", "General"),
    MEETINGS("meetings", "Meetings"),
    NOTES("notes", "Notes"),
    LECTURES("lectures", "Lectures"),
    PERSONAL("personal", "Personal"),
    INTERVIEWS("interviews", "Interviews");

    companion object {
        fun fromId(id: String?): RecordingCategory =
            entries.firstOrNull { it.id == id } ?: GENERAL
    }
}

enum class ScheduleRepeat(
    val id: String,
    val displayName: String
) {
    ONCE("once", "Once"),
    DAILY("daily", "Daily"),
    WEEKLY("weekly", "Weekly");

    companion object {
        fun fromId(id: String?): ScheduleRepeat =
            entries.firstOrNull { it.id == id } ?: ONCE
    }
}

data class RecordingSchedule(
    val id: String,
    val label: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val repeat: ScheduleRepeat = ScheduleRepeat.ONCE,
    val recordingMode: RecordingMode = RecordingMode.VOICE_ACTIVATED,
    val isEnabled: Boolean = true
)

data class UserSettings(
    val recordingMode: RecordingMode = RecordingMode.VOICE_ACTIVATED,
    val audioFormat: AudioFormatOption = AudioFormatOption.OGG_OPUS,
    val voiceSensitivity: VoiceSensitivity = VoiceSensitivity.QUIET_VOICES,
    val silenceDelaySeconds: Int = 10,
    val autoStopMinutes: Int = 0,
    val silenceAutoStopMinutes: Int = 0,
    val defaultPlaybackSpeed: Float = 1.0f,
    val batteryProfile: BatteryOptimizationProfile = BatteryOptimizationProfile.ADAPTIVE_ECO,
    val lowBatteryStopPercent: Int = 5,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val keepScreenOn: Boolean = false,
    val onboardingCompleted: Boolean = false
)

enum class SessionStatus {
    ACTIVE,
    READY
}

data class AudioChunk(
    val index: Int,
    val fileName: String,
    val startTimeMillis: Long,
    val durationMillis: Long,
    val sizeBytes: Long,
    val actualExtension: String = "wav"
)

data class SessionBookmark(
    val id: String,
    val timestampMillis: Long,
    val offsetFromSessionStartMillis: Long,
    val label: String
)

enum class PublicExportDestination(
    val id: String,
    val folderLabel: String
) {
    DOWNLOADS("downloads", "Downloads/NTK VOX"),
    MUSIC("music", "Music/NTK VOX");

    companion object {
        fun fromId(id: String?): PublicExportDestination =
            entries.firstOrNull { it.id == id } ?: DOWNLOADS
    }
}

data class StorageSummary(
    val recordingsBytes: Long = 0L,
    val trashedBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val availableDeviceBytes: Long = 0L,
    val totalDeviceBytes: Long = 1L,
    val sessionCount: Int = 0,
    val trashedCount: Int = 0,
    val totalClipCount: Int = 0
) {
    val totalRecordingsBytes: Long
        get() = recordingsBytes + trashedBytes

    val totalAppBytes: Long
        get() = recordingsBytes + trashedBytes + cacheBytes

    val hasTrashedItems: Boolean
        get() = trashedCount > 0 || trashedBytes > 0L
}

data class SessionNotes(
    val transcript: String,
    val summary: String,
    val highlights: List<String>,
    val keywords: List<String> = emptyList(),
    val generatedAtMillis: Long
)

data class RecordingSession(
    val id: String,
    val title: String,
    val folderName: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val format: AudioFormatOption,
    val recordingMode: RecordingMode = RecordingMode.VOICE_ACTIVATED,
    val status: SessionStatus,
    val chunks: List<AudioChunk>,
    val bookmarks: List<SessionBookmark> = emptyList(),
    val isFavorite: Boolean = false,
    val category: RecordingCategory = RecordingCategory.GENERAL,
    val deletedAtMillis: Long? = null,
    val notes: SessionNotes? = null
) {
    val isTrashed: Boolean
        get() = deletedAtMillis != null

    val totalSpeechMillis: Long
        get() = chunks.sumOf { it.durationMillis }

    val totalSizeBytes: Long
        get() = chunks.sumOf { it.sizeBytes }

    val clipCount: Int
        get() = chunks.size
}
