package com.example.ocrmedicationapp

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.ocrmedicationapp.databinding.DialogFrequencySelectionBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class FrequencySelectionSheet(
    private val currentFrequencyStr: String = "Every day",
    private val onFrequencySelected: (formattedFrequency: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogFrequencySelectionBinding? = null
    private val binding get() = _binding!!

    private var selectedType = TYPE_EVERY_DAY
    private var isSpecificDateOn = false
    private val targetCalendar: Calendar = Calendar.getInstance()
    private val selectedDaysSet = mutableSetOf<Int>()

    companion object {
        const val TYPE_ONLY_ONCE = 0
        const val TYPE_EVERY_DAY = 1
        const val TYPE_EVERY_WEEK = 2
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
        _binding = DialogFrequencySelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parseInitialFrequency()
        setupListeners()
        updateUiState()
    }

    private fun parseInitialFrequency() {
        val lower = currentFrequencyStr.lowercase()
        when {
            lower.contains("only once") -> {
                selectedType = TYPE_ONLY_ONCE
                if (lower.contains("specific") || lower.contains("jan") || lower.contains("feb") ||
                    lower.contains("mar") || lower.contains("apr") || lower.contains("may") ||
                    lower.contains("jun") || lower.contains("jul") || lower.contains("aug") ||
                    lower.contains("sep") || lower.contains("oct") || lower.contains("nov") || lower.contains("dec") ||
                    currentFrequencyStr.contains("(")) {
                    isSpecificDateOn = true

                    if (currentFrequencyStr.contains("(")) {
                        val datePart = currentFrequencyStr.substringAfter("(").substringBefore(")").trim()
                        val dateFormats = listOf(
                            SimpleDateFormat("MMM dd, yyyy", Locale.US),
                            SimpleDateFormat("MMMM dd, yyyy", Locale.US),
                            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        )
                        for (df in dateFormats) {
                            try {
                                val dateObj = df.parse(datePart)
                                if (dateObj != null) {
                                    targetCalendar.time = dateObj
                                    break
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            lower.contains("week") || lower.contains("sun") || lower.contains("mon") || lower.contains("tue") ||
            lower.contains("wed") || lower.contains("thu") || lower.contains("fri") || lower.contains("sat") -> {
                selectedType = TYPE_EVERY_WEEK
                selectedDaysSet.clear()
                if (lower.contains("sun")) selectedDaysSet.add(Calendar.SUNDAY)
                if (lower.contains("mon")) selectedDaysSet.add(Calendar.MONDAY)
                if (lower.contains("tue")) selectedDaysSet.add(Calendar.TUESDAY)
                if (lower.contains("wed")) selectedDaysSet.add(Calendar.WEDNESDAY)
                if (lower.contains("thu")) selectedDaysSet.add(Calendar.THURSDAY)
                if (lower.contains("fri")) selectedDaysSet.add(Calendar.FRIDAY)
                if (lower.contains("sat")) selectedDaysSet.add(Calendar.SATURDAY)

                if (selectedDaysSet.isEmpty()) {
                    selectedDaysSet.addAll(listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY))
                }
            }
            else -> {
                selectedType = TYPE_EVERY_DAY
            }
        }
    }

    private fun setupListeners() {
        binding.btnBackFrequency.setOnClickListener {
            dismiss()
        }

        binding.rgFrequencyType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbOnlyOnce -> selectedType = TYPE_ONLY_ONCE
                R.id.rbEveryDay -> selectedType = TYPE_EVERY_DAY
                R.id.rbEveryWeek -> selectedType = TYPE_EVERY_WEEK
            }
            updateUiState()
        }

        binding.switchSpecificDate.setOnCheckedChangeListener { _, isChecked ->
            isSpecificDateOn = isChecked
            updateUiState()
        }

        binding.rowAlarmRingsOn.setOnClickListener {
            showDatePicker()
        }

        // Setup Day Toggle Buttons
        val dayMap = mapOf(
            binding.btnDaySun to Calendar.SUNDAY,
            binding.btnDayMon to Calendar.MONDAY,
            binding.btnDayTue to Calendar.TUESDAY,
            binding.btnDayWed to Calendar.WEDNESDAY,
            binding.btnDayThu to Calendar.THURSDAY,
            binding.btnDayFri to Calendar.FRIDAY,
            binding.btnDaySat to Calendar.SATURDAY
        )

        dayMap.forEach { (tv, dayCalendarConst) ->
            tv.setOnClickListener {
                if (selectedDaysSet.contains(dayCalendarConst)) {
                    if (selectedDaysSet.size > 1) {
                        selectedDaysSet.remove(dayCalendarConst)
                    }
                } else {
                    selectedDaysSet.add(dayCalendarConst)
                }
                updateDayButtonsUI(dayMap)
            }
        }

        binding.btnSaveFrequency.setOnClickListener {
            val resultString = buildOutputFrequencyString()
            onFrequencySelected(resultString)
            dismiss()
        }
    }

    private fun updateUiState() {
        when (selectedType) {
            TYPE_ONLY_ONCE -> {
                binding.rgFrequencyType.check(R.id.rbOnlyOnce)
                binding.containerOnlyOnce.visibility = View.VISIBLE
                binding.containerEveryWeek.visibility = View.GONE

                binding.switchSpecificDate.isChecked = isSpecificDateOn
                binding.rowAlarmRingsOn.visibility = if (isSpecificDateOn) View.VISIBLE else View.GONE
            }
            TYPE_EVERY_DAY -> {
                binding.rgFrequencyType.check(R.id.rbEveryDay)
                binding.containerOnlyOnce.visibility = View.GONE
                binding.containerEveryWeek.visibility = View.GONE
            }
            TYPE_EVERY_WEEK -> {
                binding.rgFrequencyType.check(R.id.rbEveryWeek)
                binding.containerOnlyOnce.visibility = View.GONE
                binding.containerEveryWeek.visibility = View.VISIBLE

                val dayMap = mapOf(
                    binding.btnDaySun to Calendar.SUNDAY,
                    binding.btnDayMon to Calendar.MONDAY,
                    binding.btnDayTue to Calendar.TUESDAY,
                    binding.btnDayWed to Calendar.WEDNESDAY,
                    binding.btnDayThu to Calendar.THURSDAY,
                    binding.btnDayFri to Calendar.FRIDAY,
                    binding.btnDaySat to Calendar.SATURDAY
                )
                if (selectedDaysSet.isEmpty()) {
                    selectedDaysSet.addAll(listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY))
                }
                updateDayButtonsUI(dayMap)
            }
        }

        updateFormattedDateLabel()
    }

    private fun updateDayButtonsUI(dayMap: Map<TextView, Int>) {
        val activeBgColor = ContextCompat.getColor(requireContext(), R.color.accent_teal)
        val activeTextColor = ContextCompat.getColor(requireContext(), android.R.color.white)
        val inactiveTextColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        dayMap.forEach { (tv, dayConst) ->
            if (selectedDaysSet.contains(dayConst)) {
                tv.backgroundTintList = ColorStateList.valueOf(activeBgColor)
                tv.setTextColor(activeTextColor)
            } else {
                tv.backgroundTintList = null
                tv.setTextColor(inactiveTextColor)
            }
        }
    }

    private fun updateFormattedDateLabel() {
        val sdf = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
        binding.tvSelectedDateValue.text = sdf.format(targetCalendar.time)
    }

    private fun showDatePicker() {
        val dateSheet = DateSelectionSheet(targetCalendar) { updatedCal ->
            targetCalendar.timeInMillis = updatedCal.timeInMillis
            updateFormattedDateLabel()
        }
        dateSheet.show(childFragmentManager, "DateSelectionSheet")
    }

    private fun buildOutputFrequencyString(): String {
        return when (selectedType) {
            TYPE_EVERY_DAY -> "Every day"
            TYPE_ONLY_ONCE -> {
                if (isSpecificDateOn) {
                    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
                    "Only once (${sdf.format(targetCalendar.time)})"
                } else {
                    "Only once"
                }
            }
            TYPE_EVERY_WEEK -> {
                val dayNames = mutableListOf<String>()
                if (selectedDaysSet.contains(Calendar.SUNDAY)) dayNames.add("Sun")
                if (selectedDaysSet.contains(Calendar.MONDAY)) dayNames.add("Mon")
                if (selectedDaysSet.contains(Calendar.TUESDAY)) dayNames.add("Tue")
                if (selectedDaysSet.contains(Calendar.WEDNESDAY)) dayNames.add("Wed")
                if (selectedDaysSet.contains(Calendar.THURSDAY)) dayNames.add("Thu")
                if (selectedDaysSet.contains(Calendar.FRIDAY)) dayNames.add("Fri")
                if (selectedDaysSet.contains(Calendar.SATURDAY)) dayNames.add("Sat")

                if (dayNames.size == 7) {
                    "Every day"
                } else {
                    "Every week (${dayNames.joinToString(", ")})"
                }
            }
            else -> "Every day"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
