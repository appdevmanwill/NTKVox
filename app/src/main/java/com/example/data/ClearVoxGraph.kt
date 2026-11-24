package com.example.data

import android.content.Context
import com.example.speech.RuleBasedSpeechProcessor
import com.example.storage.ScheduleRepository
import com.example.storage.SessionRepository

/**
 * Lightweight singleton container ensuring services and MainActivity/ViewModel share
 * the exact same repository instances for immediate reactive updates.
 */
object ClearVoxGraph {
    @Volatile
    private var settingsRepository: SettingsRepository? = null

    @Volatile
    private var sessionRepository: SessionRepository? = null

    @Volatile
    private var scheduleRepository: ScheduleRepository? = null

    @Volatile
    private var speechProcessor: RuleBasedSpeechProcessor? = null

    fun settingsRepository(context: Context): SettingsRepository =
        settingsRepository ?: synchronized(this) {
            settingsRepository ?: SettingsRepository(context.applicationContext).also {
                settingsRepository = it
            }
        }

    fun sessionRepository(context: Context): SessionRepository =
        sessionRepository ?: synchronized(this) {
            sessionRepository ?: SessionRepository(context.applicationContext).also {
                sessionRepository = it
            }
        }

    fun scheduleRepository(context: Context): ScheduleRepository =
        scheduleRepository ?: synchronized(this) {
            scheduleRepository ?: ScheduleRepository(context.applicationContext).also {
                scheduleRepository = it
            }
        }

    fun speechProcessor(context: Context): RuleBasedSpeechProcessor =
        speechProcessor ?: synchronized(this) {
            speechProcessor ?: RuleBasedSpeechProcessor(
                context = context.applicationContext,
                sessionRepository = sessionRepository(context)
            ).also {
                speechProcessor = it
            }
        }
}
