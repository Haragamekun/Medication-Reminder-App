package com.example.ocrmedicationapp

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import com.example.ocrmedicationapp.databinding.DialogWheelTimePickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.Locale

class TimeSelectionSheet(
    private val initialTimeLabel: String = "08:00 AM",
    private val onTimeSelected: (formattedTime: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogWheelTimePickerBinding? = null
    private val binding get() = _binding!!

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
        _binding = DialogWheelTimePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        styleNumberPicker(binding.npHour)
        styleNumberPicker(binding.npMinute)
        styleNumberPicker(binding.npAmpm)

        // Hour Wheel (1..12)
        binding.npHour.minValue = 1
        binding.npHour.maxValue = 12
        binding.npHour.setFormatter { String.format(Locale.US, "%02d", it) }

        // Minute Wheel (0..59)
        binding.npMinute.minValue = 0
        binding.npMinute.maxValue = 59
        binding.npMinute.setFormatter { String.format(Locale.US, "%02d", it) }

        // AM/PM Wheel
        binding.npAmpm.minValue = 0
        binding.npAmpm.maxValue = 1
        binding.npAmpm.displayedValues = arrayOf("AM", "PM")

        // Parse initial time label (e.g. "08:00 AM")
        var initHour = 8
        var initMinute = 0
        var initAmpm = 0 // 0 = AM, 1 = PM

        try {
            val parts = initialTimeLabel.split(" ")
            if (parts.size >= 2) {
                val hms = parts[0].split(":")
                if (hms.size >= 2) {
                    initHour = hms[0].toInt()
                    initMinute = hms[1].toInt()
                }
                if (parts[1].equals("PM", ignoreCase = true)) {
                    initAmpm = 1
                }
            }
        } catch (_: Exception) {}

        binding.npHour.value = if (initHour in 1..12) initHour else 8
        binding.npMinute.value = if (initMinute in 0..59) initMinute else 0
        binding.npAmpm.value = initAmpm

        binding.btnSaveTime.setOnClickListener {
            val h = binding.npHour.value
            val m = binding.npMinute.value
            val ampmStr = if (binding.npAmpm.value == 1) "PM" else "AM"
            val displayHour = String.format(Locale.US, "%02d", h)
            val displayMinute = String.format(Locale.US, "%02d", m)
            val formattedTime = "$displayHour:$displayMinute $ampmStr"
            onTimeSelected(formattedTime)
            dismiss()
        }
    }

    private fun styleNumberPicker(picker: NumberPicker) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.textColor = Color.WHITE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
