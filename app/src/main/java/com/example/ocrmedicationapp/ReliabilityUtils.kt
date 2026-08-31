package com.example.ocrmedicationapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object ReliabilityUtils {

    fun isBatteryOptimized(context: Context): Boolean {
        val pw = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pw.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun getAutoStartIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val manufacturer = Build.MANUFACTURER.lowercase()

        when {
            manufacturer.contains("xiaomi") -> {
                intents.add(Intent().apply { component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") })
            }
            manufacturer.contains("oppo") -> {
                intents.add(Intent().apply { component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity") })
            }
            manufacturer.contains("vivo") -> {
                intents.add(Intent().apply { component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity") })
            }
            manufacturer.contains("huawei") -> {
                intents.add(Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity") })
                intents.add(Intent().apply { component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity") })
            }
            manufacturer.contains("samsung") -> {
                intents.add(Intent().apply { component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") })
            }
        }
        
        // Add generic power usage summary as fallback
        intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        
        return intents
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun scheduleAlarm(context: Context, medication: Medication) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("med_name", medication.name)
            putExtra("ringtone_uri", medication.ringtoneUri)
        }
        
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            medication.id.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = java.util.Calendar.getInstance().apply {
            val timeParts = medication.time.split(" ")
            if (timeParts.size >= 2) {
                val hms = timeParts[0].split(":")
                if (hms.size >= 2) {
                    var hour = hms[0].toInt()
                    val minute = hms[1].toInt()
                    val ampm = timeParts[1]

                    if (ampm.equals("PM", ignoreCase = true) && hour < 12) hour += 12
                    if (ampm.equals("AM", ignoreCase = true) && hour == 12) hour = 0

                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)

                    if (before(java.util.Calendar.getInstance())) {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                }
            }
        }

        if (canScheduleExactAlarms(context)) {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
            
            // Backup alarm 2 minutes later for extreme reliability
            val backupIntent = android.app.PendingIntent.getBroadcast(
                context,
                medication.id.hashCode() + 500,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis + (2 * 60 * 1000),
                backupIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }
    }
}
