package com.example.ocrmedicationapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.ocrmedicationapp.databinding.ActivityReliabilityGuideBinding

import androidx.core.content.edit

class ReliabilityGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReliabilityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReliabilityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupListeners() {
        binding.btnOptimize.setOnClickListener {
            try {
                startActivity(ReliabilityUtils.getBatteryOptimizationIntent(this))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        binding.btnAutoStart.setOnClickListener {
            val intents = ReliabilityUtils.getAutoStartIntents(this)
            var success = false
            for (intent in intents) {
                try {
                    startActivity(intent)
                    success = true
                    break
                } catch (e: Exception) {
                    continue
                }
            }
            
            getSharedPreferences("reliability_prefs", MODE_PRIVATE).edit {
                putBoolean("auto_start_attempted", true)
            }
            updateStatus()

            if (!success) {
                Toast.makeText(this, "Please look for 'Auto-start' or 'Background' settings manually.", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnExactAlarm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }

        binding.btnDone.setOnClickListener {
            finish()
        }
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("reliability_prefs", MODE_PRIVATE)
        val isBatteryOptimized = !ReliabilityUtils.isBatteryOptimized(this)
        val isAutoStartDone = prefs.getBoolean("auto_start_attempted", false)
        val isExactAlarmGranted = ReliabilityUtils.canScheduleExactAlarms(this)

        // Step 1: Battery
        if (isBatteryOptimized) {
            binding.tvBatteryStatus.text = getString(R.string.status_optimized)
            binding.tvBatteryStatus.setTextColor(getColor(R.color.accent_teal))
            binding.btnOptimize.isEnabled = false
            binding.btnOptimize.alpha = 0.5f
        } else {
            binding.tvBatteryStatus.text = getString(R.string.status_restricted)
            binding.tvBatteryStatus.setTextColor(getColor(R.color.snooze_orange))
            binding.btnOptimize.isEnabled = true
            binding.btnOptimize.alpha = 1.0f
        }

        // Step 2: Auto-Start (Locked until Step 1 is done)
        if (!isBatteryOptimized) {
            binding.cardAutoStart.alpha = 0.5f
            binding.btnAutoStart.isEnabled = false
            binding.tvAutoStartStatus.text = getString(R.string.status_waiting_step_1)
            binding.tvAutoStartStatus.setTextColor(getColor(R.color.text_secondary))
        } else {
            binding.cardAutoStart.alpha = 1.0f
            binding.btnAutoStart.isEnabled = !isAutoStartDone
            if (isAutoStartDone) {
                binding.tvAutoStartStatus.text = getString(R.string.status_done)
                binding.tvAutoStartStatus.setTextColor(getColor(R.color.accent_teal))
                binding.btnAutoStart.alpha = 0.5f
            } else {
                binding.tvAutoStartStatus.text = getString(R.string.status_pending)
                binding.tvAutoStartStatus.setTextColor(getColor(R.color.snooze_orange))
                binding.btnAutoStart.alpha = 1.0f
            }
        }

        // Step 3: Exact Alarms (Locked until Step 1 and 2 are done)
        if (!isBatteryOptimized || !isAutoStartDone) {
            binding.cardExactAlarm.alpha = 0.5f
            binding.btnExactAlarm.isEnabled = false
            binding.tvExactStatus.text = getString(R.string.status_waiting_steps_1_2)
            binding.tvExactStatus.setTextColor(getColor(R.color.text_secondary))
        } else {
            binding.cardExactAlarm.alpha = 1.0f
            if (isExactAlarmGranted) {
                binding.tvExactStatus.text = getString(R.string.status_granted)
                binding.tvExactStatus.setTextColor(getColor(R.color.accent_teal))
                binding.btnExactAlarm.isEnabled = false
                binding.btnExactAlarm.alpha = 0.5f
            } else {
                binding.tvExactStatus.text = getString(R.string.status_required)
                binding.tvExactStatus.setTextColor(getColor(R.color.snooze_orange))
                binding.btnExactAlarm.isEnabled = true
                binding.btnExactAlarm.alpha = 1.0f
            }
        }
        
        // Hide Step 3 if not relevant (Pre-Android 12)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            binding.cardExactAlarm.visibility = View.GONE
        }
    }
}
