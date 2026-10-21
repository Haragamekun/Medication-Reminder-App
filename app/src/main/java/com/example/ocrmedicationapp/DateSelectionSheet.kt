package com.example.ocrmedicationapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CalendarView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DateSelectionSheet(
    private val initialCalendar: Calendar,
    private val onDateSelected: (Calendar) -> Unit
) : BottomSheetDialogFragment() {

    private val selectedCalendar: Calendar = Calendar.getInstance().apply {
        timeInMillis = initialCalendar.timeInMillis
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
        return inflater.inflate(R.layout.dialog_date_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvHeader = view.findViewById<TextView>(R.id.tvBigDateHeader)
        val calendarView = view.findViewById<CalendarView>(R.id.calendarView)
        val btnSave = view.findViewById<View>(R.id.btnSaveDate)

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Set minimum date to today so past days are greyed out and unselectable
        calendarView.minDate = todayStart.timeInMillis

        // If the initial date was in the past, reset it to today
        if (selectedCalendar.before(todayStart)) {
            selectedCalendar.timeInMillis = todayStart.timeInMillis
        }

        fun updateHeader() {
            val sdf = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US)
            tvHeader.text = sdf.format(selectedCalendar.time)
        }

        calendarView.date = selectedCalendar.timeInMillis
        updateHeader()

        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            selectedCalendar.set(Calendar.YEAR, year)
            selectedCalendar.set(Calendar.MONTH, month)
            selectedCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            updateHeader()
        }

        btnSave.setOnClickListener {
            onDateSelected(selectedCalendar)
            dismiss()
        }
    }
}
