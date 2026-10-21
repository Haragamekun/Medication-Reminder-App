package com.example.ocrmedicationapp

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isMusicAudioPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun getOverlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun isUnusedAppRestrictionDisabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.isAutoRevokeWhitelisted
        } else {
            true
        }
    }

    fun getManageUnusedAppRestrictionsIntent(context: Context): Intent {
        return IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
    }

    fun parseDaysOfWeek(frequencyStr: String): Set<Int> {
        val lower = frequencyStr.lowercase()
        if (!lower.contains("week") && !lower.contains("sun") && !lower.contains("mon") &&
            !lower.contains("tue") && !lower.contains("wed") && !lower.contains("thu") &&
            !lower.contains("fri") && !lower.contains("sat")) {
            return emptySet()
        }

        val days = mutableSetOf<Int>()
        if (lower.contains("sun")) days.add(Calendar.SUNDAY)
        if (lower.contains("mon")) days.add(Calendar.MONDAY)
        if (lower.contains("tue")) days.add(Calendar.TUESDAY)
        if (lower.contains("wed")) days.add(Calendar.WEDNESDAY)
        if (lower.contains("thu")) days.add(Calendar.THURSDAY)
        if (lower.contains("fri")) days.add(Calendar.FRIDAY)
        if (lower.contains("sat")) days.add(Calendar.SATURDAY)

        return days
    }

    fun scheduleAlarm(context: Context, medication: Medication) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val times = medication.time.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (times.isEmpty()) return

        // Cancel existing scheduled alarms for this medication before rescheduling
        cancelAlarm(context, medication)

        times.forEachIndexed { index, timeStr ->
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("med_name", medication.name)
                putExtra("med_id", medication.id)
                putExtra("med_time", timeStr)
                putExtra("ringtone_uri", medication.ringtoneUri)
            }
            
            val requestCode = medication.id.hashCode() + index * 100
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                val timeParts = timeStr.split(" ")
                if (timeParts.size >= 2) {
                    val hms = timeParts[0].split(":")
                    if (hms.size >= 2) {
                        var hour = hms[0].toInt()
                        val minute = hms[1].toInt()
                        val ampm = timeParts[1]

                        if (ampm.equals("PM", ignoreCase = true) && hour < 12) hour += 12
                        if (ampm.equals("AM", ignoreCase = true) && hour == 12) hour = 0

                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)

                        var specificDate: Calendar? = null
                        if (medication.frequency.contains("only once", ignoreCase = true) && medication.frequency.contains("(")) {
                            val datePart = medication.frequency.substringAfter("(").substringBefore(")").trim()
                            val dfs = listOf(
                                SimpleDateFormat("MMM dd, yyyy", Locale.US),
                                SimpleDateFormat("MMMM dd, yyyy", Locale.US),
                                SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            )
                            for (df in dfs) {
                                try {
                                    val d = df.parse(datePart)
                                    if (d != null) {
                                        specificDate = Calendar.getInstance().apply { time = d }
                                        break
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                        if (specificDate != null) {
                            set(Calendar.YEAR, specificDate.get(Calendar.YEAR))
                            set(Calendar.MONTH, specificDate.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, specificDate.get(Calendar.DAY_OF_MONTH))
                        } else {
                            val allowedDays = parseDaysOfWeek(medication.frequency)
                            val now = Calendar.getInstance()

                            if (allowedDays.isNotEmpty()) {
                                while (before(now) || !allowedDays.contains(get(Calendar.DAY_OF_WEEK))) {
                                    add(Calendar.DAY_OF_YEAR, 1)
                                }
                            } else {
                                if (before(now)) {
                                    add(Calendar.DAY_OF_YEAR, 1)
                                }
                            }
                        }
                    }
                }
            }

            val showIntent = Intent(context, MainActivity::class.java)
            val showOperation = PendingIntent.getActivity(
                context,
                requestCode + 2000,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmClockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, showOperation)

            try {
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        }
    }

    fun cancelAlarm(context: Context, medication: Medication) {
        cancelPendingAlarms(context, medication.name, medication.id)
    }

    fun cancelPendingAlarms(context: Context, medName: String, medId: String?) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val mainIntent = Intent(context, AlarmReceiver::class.java)

        val idCodes = listOfNotNull(medId?.hashCode(), medName.hashCode()).distinct()
        for (code in idCodes) {
            for (i in 0..9) {
                val reqCode = code + i * 100
                val piMain = PendingIntent.getBroadcast(
                    context,
                    reqCode,
                    mainIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (piMain != null) {
                    alarmManager.cancel(piMain)
                    piMain.cancel()
                }

                val piBackup = PendingIntent.getBroadcast(
                    context,
                    reqCode + 500,
                    mainIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (piBackup != null) {
                    alarmManager.cancel(piBackup)
                    piBackup.cancel()
                }
            }
        }

        // Cancel escalation alarm
        val nameCode = medName.hashCode()
        val escalationIntent = Intent(context, EscalationReceiver::class.java)
        val piEscalation = PendingIntent.getBroadcast(
            context,
            nameCode + 1000,
            escalationIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (piEscalation != null) {
            alarmManager.cancel(piEscalation)
            piEscalation.cancel()
        }
    }

    fun scheduleTestAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("med_name", "Test Medication")
            putExtra("is_test", true)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999, // Test ID
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + 5000 // 5 seconds from now

        val showIntent = Intent(context, MainActivity::class.java)
        val showOperation = PendingIntent.getActivity(
            context,
            9998,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showOperation)

        try {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    suspend fun performMidnightResetIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("medication_reset_prefs", Context.MODE_PRIVATE)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastResetDate = prefs.getString("last_reset_date", "") ?: ""

        if (todayStr != lastResetDate) {
            val database = AppDatabase.getDatabase(context)
            val medications = database.medicationDao().getAllMedicationsSync()
            val statusPrefs = context.getSharedPreferences("med_status", Context.MODE_PRIVATE)

            var resetCount = 0
            medications.forEach { medication ->
                val isRepeating = !medication.frequency.contains("only once", ignoreCase = true)
                if (isRepeating) {
                    if (medication.isTaken || medication.takenTimestamp != 0L) {
                        val updated = medication.copy(
                            isTaken = false,
                            takenTimestamp = 0L
                        )
                        database.medicationDao().updateMedication(updated)
                        resetCount++
                    }
                    statusPrefs.edit().remove("taken_${medication.name}").apply()
                }
            }

            prefs.edit().putString("last_reset_date", todayStr).apply()
            Log.d("MidnightReset", "Daily 12:00 AM midnight reset performed for $resetCount repeating medications on $todayStr")
        }

        // Re-schedule exact alarm for next midnight
        scheduleMidnightResetAlarm(context)
    }

    fun scheduleMidnightResetAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, MidnightResetReceiver::class.java)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            8888, // Unique ID for Midnight Reset Alarm
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val midnightCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1) // 12:00 AM next day
        }

        val showIntent = Intent(context, MainActivity::class.java)
        val showOperation = PendingIntent.getActivity(
            context,
            8889,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmClockInfo = AlarmManager.AlarmClockInfo(midnightCalendar.timeInMillis, showOperation)

        try {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                midnightCalendar.timeInMillis,
                pendingIntent
            )
        }
    }
}
