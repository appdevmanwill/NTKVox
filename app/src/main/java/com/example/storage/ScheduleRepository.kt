package com.example.storage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.model.RecordingMode
import com.example.model.RecordingSchedule
import com.example.model.ScheduleRepeat
import com.example.service.ScheduleAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID

/**
 * Offline, atomic JSON persistence + [AlarmManager] scheduler for scheduled Date & Time recordings
 * (Once, Daily, Weekly) that survive app termination and device reboots.
 */
class ScheduleRepository(private val context: Context) {

    private val scheduleFile = File(context.filesDir, "vox_schedules.json")
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val lock = Any()

    private val _schedules = MutableStateFlow<List<RecordingSchedule>>(emptyList())
    val schedules: StateFlow<List<RecordingSchedule>> = _schedules.asStateFlow()

    init {
        loadFromDiskLocked()
    }

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    suspend fun loadAndRescheduleAll() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            loadFromDiskLocked()
            val now = System.currentTimeMillis()
            val updated = _schedules.value.map { schedule ->
                normalizeScheduleForCurrentTime(schedule, now)
            }
            saveAndPublishLocked(updated)
            updated.forEach { schedule ->
                if (schedule.isEnabled && schedule.startTimeMillis > now) {
                    registerAlarms(schedule)
                } else {
                    cancelAlarms(schedule.id)
                }
            }
        }
    }

    suspend fun upsertSchedule(
        existingId: String? = null,
        label: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        repeat: ScheduleRepeat,
        recordingMode: RecordingMode,
        isEnabled: Boolean = true
    ): RecordingSchedule = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val safeEnd = maxOf(endTimeMillis, startTimeMillis + 60_000L)
            val schedule = RecordingSchedule(
                id = existingId ?: UUID.randomUUID().toString().take(8),
                label = label.trim().ifBlank { "Scheduled Recording" },
                startTimeMillis = startTimeMillis,
                endTimeMillis = safeEnd,
                repeat = repeat,
                recordingMode = recordingMode,
                isEnabled = isEnabled
            )
            val list = (_schedules.value.filterNot { it.id == schedule.id } + schedule)
                .sortedBy { it.startTimeMillis }
            saveAndPublishLocked(list)
            if (schedule.isEnabled) {
                registerAlarms(schedule)
            } else {
                cancelAlarms(schedule.id)
            }
            schedule
        }
    }

    suspend fun toggleSchedule(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val updated = _schedules.value.map { item ->
                if (item.id != id) {
                    item
                } else {
                    val toggled = item.copy(isEnabled = enabled)
                    if (enabled) normalizeScheduleForCurrentTime(toggled, now) else toggled
                }
            }
            saveAndPublishLocked(updated)
            val target = updated.firstOrNull { it.id == id }
            if (target != null && target.isEnabled && target.startTimeMillis > now) {
                registerAlarms(target)
            } else {
                cancelAlarms(id)
            }
        }
    }

    suspend fun deleteSchedule(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            cancelAlarms(id)
            val list = _schedules.value.filterNot { it.id == id }
            saveAndPublishLocked(list)
        }
    }

    fun getScheduleById(id: String): RecordingSchedule? =
        synchronized(lock) { _schedules.value.firstOrNull { it.id == id } }

    fun getNextUpcomingSchedule(nowMillis: Long = System.currentTimeMillis()): RecordingSchedule? =
        synchronized(lock) {
            _schedules.value
                .filter { it.isEnabled && it.endTimeMillis > nowMillis }
                .minByOrNull { it.startTimeMillis }
        }

    /**
     * Called after a scheduled recording completes: disables ONCE schedules or advances DAILY/WEEKLY
     * schedules to their next occurrence and registers the next AlarmManager trigger.
     */
    fun markScheduleCompleted(id: String) {
        synchronized(lock) {
            val target = _schedules.value.firstOrNull { it.id == id } ?: return
            cancelAlarms(id)
            val next = when (target.repeat) {
                ScheduleRepeat.ONCE -> target.copy(isEnabled = false)
                ScheduleRepeat.DAILY -> advanceByDays(target, 1)
                ScheduleRepeat.WEEKLY -> advanceByDays(target, 7)
            }
            val updated = (_schedules.value.filterNot { it.id == id } + next)
                .sortedBy { it.startTimeMillis }
            saveAndPublishLocked(updated)
            if (next.isEnabled) {
                registerAlarms(next)
            }
        }
    }

    private fun normalizeScheduleForCurrentTime(
        schedule: RecordingSchedule,
        now: Long
    ): RecordingSchedule {
        if (!schedule.isEnabled) return schedule
        if (schedule.endTimeMillis > now) return schedule
        return when (schedule.repeat) {
            ScheduleRepeat.ONCE -> schedule.copy(isEnabled = false)
            ScheduleRepeat.DAILY -> {
                var s = schedule
                while (s.endTimeMillis <= now) {
                    s = advanceByDays(s, 1)
                }
                s
            }
            ScheduleRepeat.WEEKLY -> {
                var s = schedule
                while (s.endTimeMillis <= now) {
                    s = advanceByDays(s, 7)
                }
                s
            }
        }
    }

    private fun advanceByDays(schedule: RecordingSchedule, days: Int): RecordingSchedule {
        val duration = (schedule.endTimeMillis - schedule.startTimeMillis).coerceAtLeast(60_000L)
        val cal = Calendar.getInstance().apply {
            timeInMillis = schedule.startTimeMillis
            add(Calendar.DAY_OF_YEAR, days)
        }
        val nextStart = cal.timeInMillis
        return schedule.copy(
            startTimeMillis = nextStart,
            endTimeMillis = nextStart + duration
        )
    }

    private fun registerAlarms(schedule: RecordingSchedule) {
        val now = System.currentTimeMillis()
        if (schedule.startTimeMillis > now) {
            val startPi = buildAlarmPendingIntent(schedule, isStart = true)
            scheduleExactOrFallback(schedule.startTimeMillis, startPi)
        }
        if (schedule.endTimeMillis > now) {
            val endPi = buildAlarmPendingIntent(schedule, isStart = false)
            scheduleExactOrFallback(schedule.endTimeMillis, endPi)
        }
    }

    private fun cancelAlarms(scheduleId: String) {
        val startReq = (scheduleId.hashCode() and 0x0FFFFFFF) * 2
        val endReq = startReq + 1
        val startIntent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_SCHEDULE_START
        }
        val endIntent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_SCHEDULE_STOP
        }
        PendingIntent.getBroadcast(
            context,
            startReq,
            startIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { alarmManager.cancel(it) }
        PendingIntent.getBroadcast(
            context,
            endReq,
            endIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { alarmManager.cancel(it) }
    }

    private fun buildAlarmPendingIntent(schedule: RecordingSchedule, isStart: Boolean): PendingIntent {
        val reqCode = (schedule.id.hashCode() and 0x0FFFFFFF) * 2 + if (isStart) 0 else 1
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = if (isStart) {
                ScheduleAlarmReceiver.ACTION_SCHEDULE_START
            } else {
                ScheduleAlarmReceiver.ACTION_SCHEDULE_STOP
            }
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_LABEL, schedule.label)
            putExtra(ScheduleAlarmReceiver.EXTRA_END_TIME_MILLIS, schedule.endTimeMillis)
            putExtra(ScheduleAlarmReceiver.EXTRA_MODE_ID, schedule.recordingMode.id)
        }
        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleExactOrFallback(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to setAndAllowWhileIdle", e)
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        }
    }

    private fun loadFromDiskLocked() {
        if (!scheduleFile.exists()) {
            _schedules.value = emptyList()
            return
        }
        try {
            val arr = JSONArray(scheduleFile.readText(Charsets.UTF_8))
            val items = mutableListOf<RecordingSchedule>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(
                    RecordingSchedule(
                        id = obj.getString("id"),
                        label = obj.optString("label", "Scheduled Recording"),
                        startTimeMillis = obj.getLong("startTimeMillis"),
                        endTimeMillis = obj.getLong("endTimeMillis"),
                        repeat = ScheduleRepeat.fromId(obj.optString("repeat")),
                        recordingMode = RecordingMode.fromId(obj.optString("recordingModeId")),
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
            _schedules.value = items.sortedBy { it.startTimeMillis }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read schedules.json", e)
            _schedules.value = emptyList()
        }
    }

    private fun saveAndPublishLocked(list: List<RecordingSchedule>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("startTimeMillis", s.startTimeMillis)
                    put("endTimeMillis", s.endTimeMillis)
                    put("repeat", s.repeat.id)
                    put("recordingModeId", s.recordingMode.id)
                    put("isEnabled", s.isEnabled)
                }
            )
        }
        val tmp = File(context.filesDir, "vox_schedules.json.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(arr.toString(2).toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(scheduleFile)) {
                tmp.copyTo(scheduleFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing schedules.json", e)
        }
        _schedules.value = list
    }

    companion object {
        private const val TAG = "ScheduleRepository"
    }
}
