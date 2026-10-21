package com.example.ocrmedicationapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.edit

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medName = intent.getStringExtra("med_name") ?: "Medication"
        val medId = intent.getStringExtra("med_id")
        val medTime = intent.getStringExtra("med_time")
        val ringtoneUri = intent.getStringExtra("ringtone_uri")
        val isTest = intent.getBooleanExtra("is_test", false)
        
        // Temporary WakeLock to ensure service starts
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MedicationApp:ReceiverWakeLock")
        wakeLock.acquire(10000) // 10 seconds should be plenty
        
        if (isTest) {
            Toast.makeText(context, "Test Alarm Received! Starting service...", Toast.LENGTH_SHORT).show()
        }

        // Reset taken status for this dose (Skip for tests)
        if (!isTest) {
            val statusPrefs = context.getSharedPreferences("med_status", Context.MODE_PRIVATE)
            statusPrefs.edit { putBoolean("taken_$medName", false) }
        }

        // Schedule Escalation check in 1 hour (Skip for tests)
        if (!isTest) {
            val escalationIntent = Intent(context, EscalationReceiver::class.java).apply {
                putExtra("med_name", medName)
                putExtra("med_id", medId)
                putExtra("med_time", medTime)
            }
            val escalationPI = PendingIntent.getBroadcast(
                context,
                medName.hashCode() + 1000,
                escalationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 3600000,
                escalationPI
            )
        }

        // Start Foreground Service for reliable alert
        val serviceIntent = Intent(context, MedicationAlarmService::class.java).apply {
            putExtra("med_name", medName)
            putExtra("med_id", medId)
            putExtra("med_time", medTime)
            putExtra("ringtone_uri", ringtoneUri)
            putExtra("is_test", isTest)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
