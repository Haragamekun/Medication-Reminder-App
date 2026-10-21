package com.example.ocrmedicationapp

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.ocrmedicationapp.databinding.ActivityReliabilityGuideBinding

class ReliabilityGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReliabilityGuideBinding

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updateStatus()
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission denied. You won't receive alerts.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestMusicAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updateStatus()
        if (isGranted) {
            Toast.makeText(this, "Music & Audio permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission denied. Custom ringtones may not play.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHighRefreshRate()
        binding = ActivityReliabilityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateStatus()
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

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupListeners() {
        binding.btnGuideBack.setOnClickListener {
            finish()
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Permission already granted by system.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantMusicAudio.setOnClickListener {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestMusicAudioPermissionLauncher.launch(perm)
        }

        binding.btnGrantOverlay.setOnClickListener {
            try {
                startActivity(ReliabilityUtils.getOverlaySettingsIntent(this))
            } catch (_: Exception) {
                Toast.makeText(this, "Unable to open overlay settings.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDisableUnusedApp.setOnClickListener {
            try {
                startActivity(ReliabilityUtils.getManageUnusedAppRestrictionsIntent(this))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open settings page.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnOptimize.setOnClickListener {
            try {
                startActivity(ReliabilityUtils.getBatteryOptimizationIntent(this))
            } catch (_: Exception) {
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
                } catch (_: Exception) {
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
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }

        binding.btnTestAlarm.setOnClickListener {
            ReliabilityUtils.scheduleTestAlarm(this)
            Toast.makeText(this, "Test alarm scheduled for 5 seconds...", Toast.LENGTH_SHORT).show()
        }

        binding.btnDone.setOnClickListener {
            getSharedPreferences("reliability_prefs", MODE_PRIVATE).edit {
                putBoolean("reliability_guide_completed", true)
            }
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("NAVIGATE_TO_TAB", 0)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun setStatusGranted(tv: TextView, label: String) {
        tv.text = label
        tv.setTextColor(ContextCompat.getColor(this, R.color.status_pill_taken_text))
        tv.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pill_taken))
    }

    private fun setStatusRequired(tv: TextView, label: String) {
        tv.text = label
        tv.setTextColor(ContextCompat.getColor(this, R.color.status_pill_pending_text))
        tv.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pill_pending))
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("reliability_prefs", MODE_PRIVATE)
        val isNotificationGranted = ReliabilityUtils.isNotificationPermissionGranted(this)
        val isMusicAudioGranted = ReliabilityUtils.isMusicAudioPermissionGranted(this)
        val isOverlayGranted = ReliabilityUtils.canDrawOverlays(this)
        val isUnusedAppDisabled = ReliabilityUtils.isUnusedAppRestrictionDisabled(this)
        val isBatteryOptimized = !ReliabilityUtils.isBatteryOptimized(this)
        val isAutoStartDone = prefs.getBoolean("auto_start_attempted", false)
        val isExactAlarmGranted = ReliabilityUtils.canScheduleExactAlarms(this)

        var totalSteps = 0
        var completedSteps = 0

        // Step 1: Notification (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            totalSteps++
            binding.cardNotificationPerm.visibility = View.VISIBLE
            if (isNotificationGranted) {
                completedSteps++
                setStatusGranted(binding.tvNotificationStatus, getString(R.string.status_granted))
                binding.btnGrantNotification.isEnabled = false
                binding.btnGrantNotification.alpha = 0.5f
            } else {
                setStatusRequired(binding.tvNotificationStatus, getString(R.string.status_required))
                binding.btnGrantNotification.isEnabled = true
                binding.btnGrantNotification.alpha = 1.0f
            }
        } else {
            binding.cardNotificationPerm.visibility = View.GONE
        }

        // Step 2: Music & Audio
        totalSteps++
        if (isMusicAudioGranted) {
            completedSteps++
            setStatusGranted(binding.tvMusicAudioStatus, getString(R.string.status_granted))
            binding.btnGrantMusicAudio.isEnabled = false
            binding.btnGrantMusicAudio.alpha = 0.5f
        } else {
            setStatusRequired(binding.tvMusicAudioStatus, getString(R.string.status_required))
            binding.btnGrantMusicAudio.isEnabled = true
            binding.btnGrantMusicAudio.alpha = 1.0f
        }

        // Step 3: Exact Alarms
        totalSteps++
        if (isExactAlarmGranted) {
            completedSteps++
            setStatusGranted(binding.tvExactStatus, getString(R.string.status_granted))
            binding.btnExactAlarm.isEnabled = false
            binding.btnExactAlarm.alpha = 0.5f
        } else {
            setStatusRequired(binding.tvExactStatus, getString(R.string.status_required))
            binding.btnExactAlarm.isEnabled = true
            binding.btnExactAlarm.alpha = 1.0f
        }

        // Step 4: Display Over Other Apps
        totalSteps++
        if (isOverlayGranted) {
            completedSteps++
            setStatusGranted(binding.tvOverlayStatus, getString(R.string.status_granted))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.alpha = 0.5f
        } else {
            setStatusRequired(binding.tvOverlayStatus, getString(R.string.status_required))
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.alpha = 1.0f
        }

        // Step 5: Unused App Settings (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            totalSteps++
            binding.cardUnusedAppPerm.visibility = View.VISIBLE
            if (isUnusedAppDisabled) {
                completedSteps++
                setStatusGranted(binding.tvUnusedAppStatus, getString(R.string.status_unused_app_disabled))
                binding.btnDisableUnusedApp.isEnabled = false
                binding.btnDisableUnusedApp.alpha = 0.5f
            } else {
                setStatusRequired(binding.tvUnusedAppStatus, getString(R.string.status_unused_app_enabled))
                binding.btnDisableUnusedApp.isEnabled = true
                binding.btnDisableUnusedApp.alpha = 1.0f
            }
        } else {
            binding.cardUnusedAppPerm.visibility = View.GONE
        }

        // Step 6: Battery Optimization
        totalSteps++
        if (isBatteryOptimized) {
            completedSteps++
            setStatusGranted(binding.tvBatteryStatus, getString(R.string.status_optimized))
            binding.btnOptimize.isEnabled = false
            binding.btnOptimize.alpha = 0.5f
        } else {
            setStatusRequired(binding.tvBatteryStatus, getString(R.string.status_restricted))
            binding.btnOptimize.isEnabled = true
            binding.btnOptimize.alpha = 1.0f
        }

        // Step 7: Auto-Start
        totalSteps++
        binding.btnAutoStart.isEnabled = !isAutoStartDone
        if (isAutoStartDone) {
            completedSteps++
            setStatusGranted(binding.tvAutoStartStatus, getString(R.string.status_done))
            binding.btnAutoStart.alpha = 0.5f
        } else {
            setStatusRequired(binding.tvAutoStartStatus, getString(R.string.status_pending))
            binding.btnAutoStart.alpha = 1.0f
        }

        // Update progress badge and progress bar
        binding.tvGuideProgress.text = "$completedSteps/$totalSteps Done"
        val progressPercent = if (totalSteps > 0) (completedSteps * 100) / totalSteps else 0
        binding.pbGuideProgress.progress = progressPercent

        // Show Test Alarm only if basic steps are done
        binding.cardTestAlarm.visibility = if (isNotificationGranted && isExactAlarmGranted) View.VISIBLE else View.GONE
    }
}
