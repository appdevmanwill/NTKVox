package com.example.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatHms(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatCompactDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun formatFileSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatDateLabel(millis: Long): String {
        val fmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    fun formatTimeRange(startMillis: Long, endMillis: Long): String {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val start = fmt.format(Date(startMillis))
        val end = fmt.format(Date(maxOf(startMillis, endMillis)))
        return if (start == end) start else "$start – $end"
    }

    fun formatWallClockTime(millis: Long): String {
        val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    fun formatDateTimeShort(millis: Long): String {
        val fmt = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    fun formatShortDate(millis: Long): String {
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    fun formatEstimatedHours(availableBytes: Long, bytesPerHour: Long): String {
        val reserveBytes = 100L * 1024L * 1024L
        val usableBytes = (availableBytes - reserveBytes).coerceAtLeast(0L)
        if (usableBytes == 0L || bytesPerHour <= 0L) return "< 1m"
        val totalMinutes = ((usableBytes.toDouble() / bytesPerHour.toDouble()) * 60.0).toLong()
        return when {
            totalMinutes >= 60L * 100L -> "~${totalMinutes / 60L}h"
            totalMinutes >= 60L -> {
                val hrs = totalMinutes / 60L
                val mins = totalMinutes % 60L
                if (mins >= 10L) "~${hrs}h ${mins}m" else "~${hrs}h"
            }
            totalMinutes >= 1L -> "~${totalMinutes}m"
            else -> "< 1m"
        }
    }
}

data class OemBatteryProfile(
    val brandDisplayName: String,
    val isAggressiveOem: Boolean,
    val stepByStepTip: String
)

object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun detectOemProfile(): OemBatteryProfile {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.US)
        val brand = Build.BRAND.orEmpty().lowercase(Locale.US)
        val combined = "$manufacturer $brand"

        return when {
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") ->
                OemBatteryProfile(
                    brandDisplayName = "Xiaomi / Redmi",
                    isAggressiveOem = true,
                    stepByStepTip = "Open Battery Saver for NTK VOX and choose \"No restrictions\", and enable \"Autostart\" if shown."
                )

            combined.contains("samsung") ->
                OemBatteryProfile(
                    brandDisplayName = "Samsung",
                    isAggressiveOem = true,
                    stepByStepTip = "In App Info → Battery, choose \"Unrestricted\" so Samsung Sleeping Apps never pauses NTK VOX."
                )

            combined.contains("huawei") || combined.contains("honor") ->
                OemBatteryProfile(
                    brandDisplayName = "Huawei / Honor",
                    isAggressiveOem = true,
                    stepByStepTip = "In Battery → App launch, turn off \"Manage automatically\" for NTK VOX and keep \"Run in background\" enabled."
                )

            combined.contains("oppo") || combined.contains("realme") || combined.contains("oneplus") ->
                OemBatteryProfile(
                    brandDisplayName = "Oppo / OnePlus / Realme",
                    isAggressiveOem = true,
                    stepByStepTip = "In Battery usage, allow \"Background activity\" and \"Foreground activity\" for NTK VOX."
                )

            combined.contains("vivo") || combined.contains("iqoo") ->
                OemBatteryProfile(
                    brandDisplayName = "Vivo",
                    isAggressiveOem = true,
                    stepByStepTip = "In Battery → Background power consumption, set NTK VOX to \"High background power usage\"."
                )

            combined.contains("tecno") || combined.contains("infinix") || combined.contains("itel") ->
                OemBatteryProfile(
                    brandDisplayName = "Tecno / Infinix",
                    isAggressiveOem = true,
                    stepByStepTip = "In Phone Master → Auto-start / Power Marathon, exempt NTK VOX from background cleanup."
                )

            else ->
                OemBatteryProfile(
                    brandDisplayName = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
                        .ifBlank { "Android" },
                    isAggressiveOem = false,
                    stepByStepTip = "Set NTK VOX battery usage to \"Unrestricted\" so long overnight sessions run smoothly with the screen off."
                )
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(directIntent)
            true
        }.getOrDefault(false)

        if (!launched) {
            openAppBatterySettingsFallback(context)
        }
    }

    fun openOemBatterySettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.US)
        val candidates = buildList {
            when {
                manufacturer.contains("xiaomi") -> {
                    add(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    add(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                    add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                }
                manufacturer.contains("vivo") -> {
                    add(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"))
                }
            }
        }

        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                putExtra("package_name", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return
        }
        openAppBatterySettingsFallback(context)
    }

    private fun openAppBatterySettingsFallback(context: Context) {
        val ignoreListIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedList = runCatching {
            context.startActivity(ignoreListIntent)
            true
        }.getOrDefault(false)

        if (!launchedList) {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(appDetails) }
        }
    }
}
