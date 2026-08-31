package com.example.ocrmedicationapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class EscalationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medName = intent.getStringExtra("med_name") ?: "Medication"
        
        // Check if med was taken (This is a simplified check, ideally uses ViewModel/Repo)
        val prefs = context.getSharedPreferences("med_status", Context.MODE_PRIVATE)
        val isTaken = prefs.getBoolean("taken_$medName", false)
        
        if (!isTaken) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "escalation_alerts"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Escalation Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Urgent alerts for missed doses"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("URGENT: Missed Dose")
                .setContentText("You haven't confirmed your dose of $medName in over an hour!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)

            notificationManager.notify(medName.hashCode() + 2000, builder.build())
        }
    }
}
