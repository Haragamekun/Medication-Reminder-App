package com.example.ocrmedicationapp

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.ocrmedicationapp.databinding.ActivityAlarmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class AlarmActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure activity shows on lock screen and turns screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableHighRefreshRate()

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val medName = intent.getStringExtra("med_name") ?: "Medication"
        val medId = intent.getStringExtra("med_id")
        val medTime = intent.getStringExtra("med_time")
        binding.tvMedicationName.text = medName

        binding.btnDismiss.setOnClickListener {
            stopAlarmService()
            ReliabilityUtils.cancelPendingAlarms(this, medName, medId)
            lifecycleScope.launch(Dispatchers.IO) {
                val repository = MedicationRepository(applicationContext)
                val medications = repository.getMedications()
                val targetMed = medications.find { (medId != null && it.id == medId) || it.name.equals(medName, ignoreCase = true) }
                if (targetMed != null) {
                    val now = System.currentTimeMillis()
                    repository.recordMissed(targetMed, now, medTime)
                }
            }
            finish()
        }

        binding.btnTaken.setOnClickListener {
            stopAlarmService()
            ReliabilityUtils.cancelPendingAlarms(this, medName, medId)
            val prefs = getSharedPreferences("med_status", MODE_PRIVATE)
            prefs.edit { putBoolean("taken_$medName", true) }

            // Update medication status in database to immediately update app dashboard and history
            lifecycleScope.launch(Dispatchers.IO) {
                val repository = MedicationRepository(applicationContext)
                val medications = repository.getMedications()
                val targetMed = medications.find { (medId != null && it.id == medId) || it.name.equals(medName, ignoreCase = true) }
                if (targetMed != null) {
                    val now = System.currentTimeMillis()
                    val updated = targetMed.copy(isTaken = true, takenTimestamp = now)
                    repository.updateMedication(updated)
                    repository.recordIntake(updated, now, medTime)
                }
            }

            Toast.makeText(this, getString(R.string.msg_confirmed_taken), Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnSnooze.setOnClickListener {
            stopAlarmService()
            snoozeAlarm(medName, medId, medTime)
            finish()
        }
    }

    private fun stopAlarmService() {
        val serviceIntent = Intent(this, MedicationAlarmService::class.java)
        stopService(serviceIntent)
    }

    private fun snoozeAlarm(medName: String, medId: String?, medTime: String?) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("med_name", medName)
            putExtra("med_id", medId)
            putExtra("med_time", medTime)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            medName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            add(Calendar.MINUTE, 15)
        }

        val showIntent = Intent(this, MainActivity::class.java)
        val showOperation = PendingIntent.getActivity(
            this,
            medName.hashCode() + 2000,
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
        
        Toast.makeText(this, getString(R.string.msg_snoozed), Toast.LENGTH_SHORT).show()
    }

    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentDisplay = this.display
            if (currentDisplay != null) {
                val modes = currentDisplay.supportedModes
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.attributes = window.attributes.apply {
                preferredRefreshRate = 120f
            }
        }
    }
}
