package com.example.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ClearVoxGraph
import com.example.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles exact scheduled start/stop alarms and `BOOT_COMPLETED` reboot recovery.
 *
 * Respects Android 14–16 privacy rules: attempts to trigger an already-armed or permitted
 * foreground service cleanly, and posts an urgent 1-tap "Scheduled Recording Ready" notification
 * if the OS blocks silent background microphone launch while the app is closed.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ClearVoxGraph.scheduleRepository(context).loadAndRescheduleAll()
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_SCHEDULE_START -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                val label = intent.getStringExtra(EXTRA_SCHEDULE_LABEL) ?: "Scheduled Recording"
                val endTimeMillis = intent.getLongExtra(EXTRA_END_TIME_MILLIS, 0L)
                val modeId = intent.getStringExtra(EXTRA_MODE_ID)

                // Attempt to hand off directly to VoxRecordingService (succeeds automatically if
                // Armed Standby is running or app is foregrounded).
                val serviceIntent = Intent(context, VoxRecordingService::class.java).apply {
                    this.action = VoxRecordingService.ACTION_SCHEDULE_TRIGGER
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                    putExtra(EXTRA_SCHEDULE_LABEL, label)
                    putExtra(EXTRA_END_TIME_MILLIS, endTimeMillis)
                    putExtra(EXTRA_MODE_ID, modeId)
                }

                val startedDirectly = runCatching {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    true
                }.onFailure {
                    Log.i(TAG, "Background mic start restricted by OS; showing urgent start alert", it)
                }.getOrDefault(false)

                if (!startedDirectly) {
                    postUrgentStartNotification(context, scheduleId, label, endTimeMillis, modeId)
                }
            }

            ACTION_SCHEDULE_STOP -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID)
                val stopIntent = Intent(context, VoxRecordingService::class.java).apply {
                    this.action = VoxRecordingService.ACTION_STOP
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                }
                runCatching { context.startService(stopIntent) }
                if (scheduleId != null) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ClearVoxGraph.scheduleRepository(context).markScheduleCompleted(scheduleId)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
        const val ALERTS_CHANNEL_ID = "ntkvox_alerts_channel"
        private const val SCHEDULE_ALERT_NOTIFICATION_ID = 1010

        const val ACTION_SCHEDULE_START = "com.example.ntkvox.ACTION_SCHEDULE_START"
        const val ACTION_SCHEDULE_STOP = "com.example.ntkvox.ACTION_SCHEDULE_STOP"

        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        const val EXTRA_SCHEDULE_LABEL = "extra_schedule_label"
        const val EXTRA_END_TIME_MILLIS = "extra_end_time_millis"
        const val EXTRA_MODE_ID = "extra_mode_id"
        const val EXTRA_AUTO_START_FROM_SCHEDULE = "extra_auto_start_from_schedule"

        fun postUrgentStartNotification(
            context: Context,
            scheduleId: String,
            label: String,
            endTimeMillis: Long,
            modeId: String?
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                context.getString(R.string.notification_alerts_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_alerts_desc)
            }
            nm.createNotificationChannel(channel)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_AUTO_START_FROM_SCHEDULE, true)
                putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(EXTRA_SCHEDULE_LABEL, label)
                putExtra(EXTRA_END_TIME_MILLIS, endTimeMillis)
                putExtra(EXTRA_MODE_ID, modeId)
            }
            val pi = PendingIntent.getActivity(
                context,
                301,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val endStr = if (endTimeMillis > System.currentTimeMillis()) {
                " • Auto-stops at ${FormatUtils.formatWallClockTime(endTimeMillis)}"
            } else {
                ""
            }

            val notif = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Scheduled: $label")
                .setContentText("Tap to start recording now$endStr")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            nm.notify(SCHEDULE_ALERT_NOTIFICATION_ID, notif)
        }
    }
}
