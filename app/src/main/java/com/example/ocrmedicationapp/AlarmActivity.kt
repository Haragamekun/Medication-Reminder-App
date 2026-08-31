package com.example.ocrmedicationapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.edit
import androidx.appcompat.app.AppCompatActivity
import com.example.ocrmedicationapp.databinding.ActivityAlarmBinding
import java.util.Calendar

class AlarmActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val medName = intent.getStringExtra("med_name") ?: "Medication"
        binding.tvMedicationName.text = medName

        binding.btnDismiss.setOnClickListener {
            stopAlarmService()
            finish()
        }

        binding.btnTaken.setOnClickListener {
            stopAlarmService()
            val prefs = getSharedPreferences("med_status", Context.MODE_PRIVATE)
            prefs.edit { putBoolean("taken_$medName", true) }
            Toast.makeText(this, getString(R.string.msg_confirmed_taken), Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnSnooze.setOnClickListener {
            stopAlarmService()
            snoozeAlarm(medName)
            finish()
        }
    }

    private fun stopAlarmService() {
        val serviceIntent = Intent(this, MedicationAlarmService::class.java)
        stopService(serviceIntent)
    }

    private fun snoozeAlarm(medName: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("med_name", medName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            medName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 15)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
        
        Toast.makeText(this, getString(R.string.msg_snoozed), Toast.LENGTH_SHORT).show()
    }
}
