package com.example.ocrmedicationapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MedicationAlarmService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val medName = intent?.getStringExtra("med_name") ?: "Medication"
        val medId = intent?.getStringExtra("med_id")
        val medTime = intent?.getStringExtra("med_time")
        val ringtoneUriStr = intent?.getStringExtra("ringtone_uri")
        val isTest = intent?.getBooleanExtra("is_test", false) ?: false

        if (intent?.action == "STOP_ALARM") {
            val repository = MedicationRepository(applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                val medications = repository.getMedications()
                val targetMed = medications.find { (medId != null && it.id == medId) || it.name.equals(medName, ignoreCase = true) }
                repository.markAllMissedForTimeSlot(targetMed, medTime)

                val sameTimeMeds = if (!medTime.isNullOrBlank()) {
                    medications.filter { med ->
                        med.time.split(",").map { it.trim() }.any { it.equals(medTime.trim(), ignoreCase = true) }
                    }
                } else listOfNotNull(targetMed)

                sameTimeMeds.forEach { m ->
                    ReliabilityUtils.cancelPendingAlarms(applicationContext, m.name, m.id)
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "ACTION_TAKEN") {
            val repository = MedicationRepository(applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                val medications = repository.getMedications()
                val targetMed = medications.find { (medId != null && it.id == medId) || it.name.equals(medName, ignoreCase = true) }
                repository.markAllTakenForTimeSlot(targetMed, medTime)

                val sameTimeMeds = if (!medTime.isNullOrBlank()) {
                    medications.filter { med ->
                        med.time.split(",").map { it.trim() }.any { it.equals(medTime.trim(), ignoreCase = true) }
                    }
                } else listOfNotNull(targetMed)

                val prefs = getSharedPreferences("med_status", MODE_PRIVATE)
                sameTimeMeds.forEach { m ->
                    prefs.edit { putBoolean("taken_${m.name}", true) }
                    ReliabilityUtils.cancelPendingAlarms(applicationContext, m.name, m.id)
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        requestAudioFocus()
        startRinging(ringtoneUriStr)
        showNotification(medName, medId, medTime, isTest)

        return START_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MedicationApp:AlarmWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
    }

    private fun requestAudioFocus() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = audioManager?.requestAudioFocus(
            this,
            AudioManager.STREAM_ALARM,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Success
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // We lost focus, but for an alarm we should usually keep ringing or duck
        }
    }

    private fun startRinging(ringtoneUriStr: String?) {
        try {
            val ringtoneUri = if (!ringtoneUriStr.isNullOrEmpty()) {
                Uri.parse(ringtoneUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            // Set volume to a reasonable level if it's too low
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
            audioManager?.setStreamVolume(
                AudioManager.STREAM_ALARM,
                (maxVol * 0.9).toInt(), // 90% volume
                0
            )

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MedicationAlarmService, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Error playing ringtone", e)
        }
    }

    private fun showNotification(medName: String, medId: String?, medTime: String?, isTest: Boolean) {
        val channelId = "medication_reminders_high"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val repository = MedicationRepository(applicationContext)
        var sameTimeMeds: List<Medication> = emptyList()
        runCatching {
            val all = kotlinx.coroutines.runBlocking(Dispatchers.IO) { repository.getMedications() }
            val targetMed = all.find { (medId != null && it.id == medId) || it.name.equals(medName, ignoreCase = true) }
            sameTimeMeds = if (!medTime.isNullOrBlank()) {
                all.filter { med ->
                    med.time.split(",").map { it.trim() }.any { it.equals(medTime.trim(), ignoreCase = true) }
                }
            } else listOfNotNull(targetMed)
        }

        val title = if (isTest) "Medication App: TEST ALARM"
                    else if (sameTimeMeds.size > 1) "Time for your Medications!"
                    else "Time for your Medication!"

        val bodyText = if (isTest) {
            "This is a test to verify your reminders are working."
        } else if (sameTimeMeds.size > 1) {
            sameTimeMeds.joinToString("\n") { "• ${it.name} (${it.details})" }
        } else {
            "Please take your $medName"
        }

        val summaryText = if (sameTimeMeds.size > 1) {
            sameTimeMeds.joinToString(", ") { it.name }
        } else {
            medName
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Priority Medication Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority reminders for your medication"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                // Sound is handled by MediaPlayer for more reliability
                setSound(null, null) 
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notifyPrefs = getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        val notificationStyle = notifyPrefs.getString("notification_style", "banner") ?: "banner"
        val isFullScreen = notificationStyle == "fullscreen"

        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra("med_name", medName)
            putExtra("med_id", medId)
            putExtra("med_time", medTime)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = if (isFullScreen) {
            PendingIntent.getActivity(
                this,
                medName.hashCode(),
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        if (isFullScreen) {
            try {
                startActivity(fullScreenIntent)
            } catch (e: Exception) {
                Log.e("AlarmService", "Failed to start AlarmActivity directly: ${e.message}", e)
            }
        }

        val stopIntent = Intent(this, MedicationAlarmService::class.java).apply {
            action = "STOP_ALARM"
            putExtra("med_name", medName)
            putExtra("med_id", medId)
            putExtra("med_time", medTime)
        }
        val stopPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                medName.hashCode(),
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                medName.hashCode(),
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val takenIntent = Intent(this, MedicationAlarmService::class.java).apply {
            action = "ACTION_TAKEN"
            putExtra("med_name", medName)
            putExtra("med_id", medId)
            putExtra("med_time", medTime)
        }
        val takenPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                medName.hashCode() + 500,
                takenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                medName.hashCode() + 500,
                takenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.checkbox_on_background, "TAKEN", takenPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "SNOOZE", stopPendingIntent)

        if (fullScreenPendingIntent != null) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        val notification = builder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to startForeground: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
        audioManager?.abandonAudioFocus(this)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
