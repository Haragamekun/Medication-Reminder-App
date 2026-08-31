package com.example.ocrmedicationapp

import android.content.Intent
import android.content.res.ColorStateList
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.ocrmedicationapp.databinding.FragmentEditMedicationBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

class EditMedicationSheet(
    private val initialName: String = "",
    private val initialDosage: String = "",
    private val initialQty: String = "",
    private val initialFreq: String = "",
    private val initialTime: String = "08:00 AM",
    private val medication: Medication? = null,
    private val onSave: (name: String, dosage: String, qty: String, freq: String, time: String, ringtoneUri: String?) -> Unit,
    private val onRescan: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: FragmentEditMedicationBinding? = null
    private val binding get() = _binding!!
    private var selectedTimeLabel = "08:00 AM"
    private var selectedRingtoneUri: Uri? = null

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            selectedRingtoneUri = uri
            binding.btnSelectRingtone.text = if (uri != null) {
                RingtoneManager.getRingtone(requireContext(), uri).getTitle(requireContext())
            } else {
                "Default Ringtone"
            }
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
        binding.etQuantity.setText(initialQty)
        binding.etFrequency.setText(initialFreq)
        
        selectedTimeLabel = initialTime
        binding.btnSelectTime.text = selectedTimeLabel

        setupVerificationUI()

        binding.btnSelectTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText("Select Reminder Time")
                .build()

            picker.addOnPositiveButtonClickListener {
                val selHour = picker.hour
                val selMinute = picker.minute
                val formatSuffix = if (selHour >= 12) "PM" else "AM"
                val displayHour = when {
                    selHour == 0 -> 12
                    selHour > 12 -> selHour - 12
                    else -> selHour
                }
                val displayMinute = String.format("%02d", selMinute)
                selectedTimeLabel = "$displayHour:$displayMinute $formatSuffix"
                binding.btnSelectTime.text = selectedTimeLabel
            }
            picker.show(childFragmentManager, "time_picker")
        }

        binding.btnSelectRingtone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Ringtone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedRingtoneUri)
            }
            ringtonePickerLauncher.launch(intent)
        }

        binding.btnRescan.setOnClickListener {
            onRescan()
            dismiss()
        }

        binding.btnSave.setOnClickListener {
            val finalName = binding.etMedicineName.text.toString().trim()
            val dosage = binding.etDosage.text.toString().trim()
            val qty = binding.etQuantity.text.toString().trim()
            val freq = binding.etFrequency.text.toString().trim()

            if (finalName.isEmpty()) {
                binding.etMedicineName.error = "Name is required"
                return@setOnClickListener
            }

            onSave(finalName, dosage, qty, freq, selectedTimeLabel, selectedRingtoneUri?.toString())
            dismiss()
        }
    }

    private fun setupVerificationUI() {
        val med = medication ?: return
        
        binding.verificationBanner.visibility = View.VISIBLE
        if (med.isVerified) {
            binding.tvVerificationStatus.text = "Verified"
            binding.ivVerificationStatus.setImageResource(R.drawable.baseline_check_24)
            binding.ivVerificationStatus.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.accent_teal))
            binding.tvVerificationStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_teal))
        } else {
            binding.tvVerificationStatus.text = "Review Required"
            binding.ivVerificationStatus.setImageResource(android.R.drawable.ic_dialog_alert)
            binding.ivVerificationStatus.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.snooze_orange))
            binding.tvVerificationStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.snooze_orange))
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
                "frequency" -> binding.etFrequency.backgroundTintList = amber
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
