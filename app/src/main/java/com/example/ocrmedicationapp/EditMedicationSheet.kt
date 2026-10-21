package com.example.ocrmedicationapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.ocrmedicationapp.databinding.FragmentEditMedicationBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EditMedicationSheet(
    private val initialName: String = "",
    private val initialDosage: String = "",
    private val initialQty: String = "1",
    private val initialFreq: String = "",
    private val initialTime: String = "08:00 AM",
    private val medication: Medication? = null,
    private val onSave: (name: String, dosage: String, qty: String, freq: String, time: String, ringtoneUri: String?) -> Unit,
    private val onRescan: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: FragmentEditMedicationBinding? = null
    private val binding get() = _binding!!

    private var selectedFrequencyCount = 1
    private var selectedFrequencyCountLabel = "1 time a day"
    private var selectedRepeatLabel = "Every day"
    private val selectedTimesList = mutableListOf<String>()
    private var selectedRingtoneUri: Uri? = null

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            selectedRingtoneUri = uri
            binding.btnSelectRingtone.text = if (uri != null) {
                try {
                    RingtoneManager.getRingtone(requireContext(), uri)?.getTitle(requireContext()) ?: "Custom Sound"
                } catch (_: Exception) {
                    "Custom Sound"
                }
            } else {
                "Default Ringtone"
            }
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        launchRingtonePicker()
    }

    private fun launchRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Ringtone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedRingtoneUri)
        }
        ringtonePickerLauncher.launch(intent)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setWindowAnimations(R.style.BottomSheetSlideAnimation)
            applyLiquidGlassModalEffect(dimAmount = 0.60f)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditMedicationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etMedicineName.setText(initialName)
        binding.etDosage.setText(initialDosage)

        parseInitialFrequency()
        parseInitialTime()

        binding.btnSelectFrequencyCount.text = selectedFrequencyCountLabel
        binding.btnSelectRepeat.text = selectedRepeatLabel

        renderReceptionTimesUI()
        setupVerificationUI()

        binding.btnSelectFrequencyCount.setOnClickListener {
            val freqCountSheet = FrequencyCountSelectionSheet(selectedFrequencyCount) { count, formattedLabel ->
                selectedFrequencyCount = count
                selectedFrequencyCountLabel = formattedLabel
                binding.btnSelectFrequencyCount.text = formattedLabel
                renderReceptionTimesUI()
            }
            freqCountSheet.show(childFragmentManager, "FrequencyCountSelectionSheet")
        }

        binding.btnSelectRepeat.setOnClickListener {
            val repeatSheet = FrequencySelectionSheet(selectedRepeatLabel) { formattedRepeat ->
                selectedRepeatLabel = formattedRepeat
                binding.btnSelectRepeat.text = formattedRepeat
            }
            repeatSheet.show(childFragmentManager, "FrequencySelectionSheet")
        }

        binding.btnSelectRingtone.setOnClickListener {
            if (!ReliabilityUtils.isMusicAudioPermissionGranted(requireContext())) {
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                requestAudioPermissionLauncher.launch(perm)
            } else {
                launchRingtonePicker()
            }
        }

        binding.btnRescan.setOnClickListener {
            resetFormToDefaults()
        }

        binding.btnSave.setOnClickListener {
            val finalName = binding.etMedicineName.text?.toString()?.trim() ?: ""
            val dosage = binding.etDosage.text?.toString()?.trim() ?: ""
            val freq = "$selectedFrequencyCountLabel • $selectedRepeatLabel"
            val time = selectedTimesList.joinToString(", ")

            if (finalName.isEmpty()) {
                binding.etMedicineName.error = "Name is required"
                return@setOnClickListener
            }

            onSave(finalName, dosage, "1", freq, time, selectedRingtoneUri?.toString())
            dismiss()
        }
    }

    private fun parseInitialFrequency() {
        val lowerFreq = initialFreq.lowercase()
        selectedFrequencyCount = when {
            lowerFreq.contains("4 times") || lowerFreq.contains("four times") -> 4
            lowerFreq.contains("3 times") || lowerFreq.contains("thrice") -> 3
            lowerFreq.contains("2 times") || lowerFreq.contains("twice") -> 2
            else -> 1
        }
        selectedFrequencyCountLabel = "$selectedFrequencyCount ${if (selectedFrequencyCount == 1) "time" else "times"} a day"

        selectedRepeatLabel = when {
            lowerFreq.contains("only once") -> {
                if (initialFreq.contains("•")) {
                    initialFreq.substringAfter("•").trim()
                } else {
                    initialFreq.trim()
                }
            }
            lowerFreq.contains("week") -> {
                if (initialFreq.contains("•")) {
                    initialFreq.substringAfter("•").trim()
                } else {
                    initialFreq.trim()
                }
            }
            else -> "Every day"
        }
    }

    private fun parseInitialTime() {
        selectedTimesList.clear()
        val parts = initialTime.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            selectedTimesList.addAll(parts)
        } else {
            selectedTimesList.add("08:00 AM")
        }
    }

    private fun renderReceptionTimesUI() {
        binding.containerReceptionTimes.removeAllViews()

        val defaultTimes = listOf("08:00 AM", "01:00 PM", "06:00 PM", "09:00 PM")
        while (selectedTimesList.size < selectedFrequencyCount) {
            val nextIndex = selectedTimesList.size
            selectedTimesList.add(defaultTimes.getOrElse(nextIndex) { "08:00 AM" })
        }
        while (selectedTimesList.size > selectedFrequencyCount) {
            selectedTimesList.removeAt(selectedTimesList.size - 1)
        }

        for (i in 0 until selectedFrequencyCount) {
            val slotLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(12)
                }
            }

            val labelView = TextView(requireContext()).apply {
                text = if (selectedFrequencyCount == 1) "RECEPTION TIME" else "RECEPTION TIME ${i + 1}"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                setPadding(0, 0, 0, dpToPx(6))
            }

            val button = com.google.android.material.button.MaterialButton(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(56)
                )
                text = selectedTimesList[i]
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dpToPx(14), 0, dpToPx(10), 0)
                strokeColor = ContextCompat.getColorStateList(context, R.color.button_stroke)
                strokeWidth = dpToPx(1)
                cornerRadius = dpToPx(16)
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_today)
                iconTint = ContextCompat.getColorStateList(context, R.color.accent_teal)
                isAllCaps = false

                setOnClickListener {
                    val slotIndex = i
                    val timeSheet = TimeSelectionSheet(selectedTimesList[slotIndex]) { formattedTime ->
                        selectedTimesList[slotIndex] = formattedTime
                        text = formattedTime
                    }
                    timeSheet.show(childFragmentManager, "TimeSelectionSheet_$slotIndex")
                }
            }

            slotLayout.addView(labelView)
            slotLayout.addView(button)
            binding.containerReceptionTimes.addView(slotLayout)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun resetFormToDefaults() {
        binding.etMedicineName.setText("")
        binding.etMedicineName.error = null
        binding.etMedicineName.backgroundTintList = null

        binding.etDosage.setText("")
        binding.etDosage.backgroundTintList = null

        selectedFrequencyCount = 1
        selectedFrequencyCountLabel = "1 time a day"
        binding.btnSelectFrequencyCount.text = selectedFrequencyCountLabel

        selectedRepeatLabel = "Every day"
        binding.btnSelectRepeat.text = selectedRepeatLabel

        selectedTimesList.clear()
        selectedTimesList.add("08:00 AM")
        renderReceptionTimesUI()

        selectedRingtoneUri = null
        binding.btnSelectRingtone.text = "Default Ringtone"

        binding.verificationBanner.visibility = View.GONE
        binding.tvVerificationMsg.visibility = View.GONE

        android.widget.Toast.makeText(requireContext(), "Form reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun setupVerificationUI() {
        val med = medication ?: return
        
        binding.verificationBanner.visibility = View.VISIBLE
        if (med.isVerified) {
            binding.tvVerificationStatus.text = "VERIFIED"
            binding.ivVerificationStatus.setImageResource(R.drawable.baseline_check_24)
            val textCol = ContextCompat.getColor(requireContext(), R.color.status_pill_taken_text)
            val bgCol = ContextCompat.getColor(requireContext(), R.color.status_pill_taken)
            binding.ivVerificationStatus.imageTintList = ColorStateList.valueOf(textCol)
            binding.tvVerificationStatus.setTextColor(textCol)
            binding.verificationBanner.backgroundTintList = ColorStateList.valueOf(bgCol)
        } else {
            binding.tvVerificationStatus.text = "REVIEW REQUIRED"
            binding.ivVerificationStatus.setImageResource(android.R.drawable.ic_dialog_alert)
            val textCol = ContextCompat.getColor(requireContext(), R.color.status_pill_pending_text)
            val bgCol = ContextCompat.getColor(requireContext(), R.color.status_pill_pending)
            binding.ivVerificationStatus.imageTintList = ColorStateList.valueOf(textCol)
            binding.tvVerificationStatus.setTextColor(textCol)
            binding.verificationBanner.backgroundTintList = ColorStateList.valueOf(bgCol)
        }

        med.verificationMessage?.let {
            binding.tvVerificationMsg.visibility = View.VISIBLE
            binding.tvVerificationMsg.text = it
        }

        // Highlight flagged fields
        val amber = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.snooze_orange))
        med.flaggedFields.forEach { field ->
            when (field.lowercase()) {
                "name" -> binding.etMedicineName.backgroundTintList = amber
                "dosage" -> binding.etDosage.backgroundTintList = amber
                "frequency" -> binding.btnSelectFrequencyCount.strokeColor = amber
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
