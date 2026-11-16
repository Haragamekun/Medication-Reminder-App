package com.example.ocrmedicationapp

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CalendarHistorySheet(
    private var calendarRecords: List<CalendarRecord> = emptyList(),
    private var scheduledMedications: List<Medication> = emptyList()
) : BottomSheetDialogFragment() {

    private val displayedMonthCalendar: Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val selectedDateCalendar: Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private lateinit var tvMonthYear: TextView
    private lateinit var rvCalendarGrid: RecyclerView
    private lateinit var tvSelectedDateHeader: TextView
    private lateinit var containerDateMedications: LinearLayout
    private lateinit var emptyDateMedicationsContainer: View

    private var calendarGridAdapter: CalendarGridAdapter? = null

    fun updateData(newCalendarRecords: List<CalendarRecord>, newScheduledMedications: List<Medication>) {
        this.calendarRecords = newCalendarRecords
        this.scheduledMedications = newScheduledMedications
        if (isAdded) {
            refreshMonthGrid()
            bindSelectedDateList()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setWindowAnimations(R.style.BottomSheetSlideAnimation)
            applyLiquidGlassModalEffect(dimAmount = 0.60f)
        }
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let { sheet ->
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_calendar_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvMonthYear = view.findViewById(R.id.tvMonthYear)
        rvCalendarGrid = view.findViewById(R.id.rvCalendarGrid)
        tvSelectedDateHeader = view.findViewById(R.id.tvSelectedDateHeader)
        containerDateMedications = view.findViewById(R.id.containerDateMedications)
        emptyDateMedicationsContainer = view.findViewById(R.id.emptyDateMedicationsContainer)

        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseCalendar)
        val btnPrevMonth = view.findViewById<ImageButton>(R.id.btnPrevMonth)
        val btnNextMonth = view.findViewById<ImageButton>(R.id.btnNextMonth)

        btnClose.setOnClickListener { dismiss() }

        btnPrevMonth.setOnClickListener {
            displayedMonthCalendar.add(Calendar.MONTH, -1)
            refreshMonthGrid()
        }

        btnNextMonth.setOnClickListener {
            displayedMonthCalendar.add(Calendar.MONTH, 1)
            refreshMonthGrid()
        }

        rvCalendarGrid.layoutManager = GridLayoutManager(requireContext(), 7)
        calendarGridAdapter = CalendarGridAdapter()
        rvCalendarGrid.adapter = calendarGridAdapter

        refreshMonthGrid()
        bindSelectedDateList()
    }

    private fun formatDateString(cal: Calendar): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(cal.time)
    }

    private fun refreshMonthGrid() {
        val monthSdf = SimpleDateFormat("MMMM yyyy", Locale.US)
        tvMonthYear.text = monthSdf.format(displayedMonthCalendar.time)

        val items = mutableListOf<CalendarDayItem>()

        val firstDayCal = (displayedMonthCalendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
        }

        val firstDayOfWeek = firstDayCal.get(Calendar.DAY_OF_WEEK) // SUNDAY = 1
        val paddingCount = firstDayOfWeek - 1

        for (i in 0 until paddingCount) {
            items.add(CalendarDayItem(0, "", null, false, emptyList(), DayColorStatus.NONE))
        }

        val maxDays = displayedMonthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        for (day in 1..maxDays) {
            val dayCal = (displayedMonthCalendar.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, day)
            }
            val dateStr = sdfDate.format(dayCal.time)
            val dayRecords = calendarRecords.filter { it.dateString == dateStr }

            val status = when {
                dayRecords.isNotEmpty() -> {
                    if (dayRecords.all { it.status.equals("TAKEN", ignoreCase = true) }) {
                        DayColorStatus.GREEN
                    } else {
                        DayColorStatus.YELLOW
                    }
                }
                else -> DayColorStatus.NONE
            }

            items.add(CalendarDayItem(day, dateStr, dayCal, true, dayRecords, status))
        }

        calendarGridAdapter?.submitList(items, selectedDateCalendar)
    }

    private fun bindSelectedDateList() {
        val headerSdf = SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.US)
        tvSelectedDateHeader.text = "SCHEDULED MEDICATIONS FOR ${headerSdf.format(selectedDateCalendar.time).uppercase()}"

        containerDateMedications.removeAllViews()
        val selectedDateStr = formatDateString(selectedDateCalendar)
        val recordsForDate = calendarRecords.filter { it.dateString == selectedDateStr }

        if (recordsForDate.isNotEmpty()) {
            emptyDateMedicationsContainer.visibility = View.GONE
            val sdfTime = SimpleDateFormat("hh:mm a", Locale.US)

            recordsForDate.forEach { record ->
                val card = LayoutInflater.from(requireContext()).inflate(R.layout.medication_card_item, containerDateMedications, false)
                card.findViewById<TextView>(R.id.tvMedName).text = record.name
                card.findViewById<TextView>(R.id.tvMedDetails).text = record.details

                val completionTimeStr = if (record.takenTimestamp > 0L) sdfTime.format(Date(record.takenTimestamp)) else record.time
                val slotTimeStr = if (record.time.isNotBlank() && !record.time.contains(",")) " • Slot: ${record.time}" else ""
                val timeLabel = when {
                    record.status.equals("MISSED", ignoreCase = true) -> "Missed at $completionTimeStr$slotTimeStr"
                    record.status.equals("SKIPPED", ignoreCase = true) -> "Skipped at $completionTimeStr$slotTimeStr"
                    else -> "Taken at $completionTimeStr$slotTimeStr"
                }
                card.findViewById<TextView>(R.id.tvMedTime).text = timeLabel

                val statusPill = card.findViewById<TextView>(R.id.tvStatusPill)
                statusPill.visibility = View.VISIBLE
                val cb = card.findViewById<ImageView>(R.id.cbTaken)

                if (record.status.equals("MISSED", ignoreCase = true) || record.status.equals("SKIPPED", ignoreCase = true)) {
                    statusPill.text = record.status.uppercase()
                    statusPill.setTextColor(requireContext().getColor(R.color.status_pill_pending_text))
                    statusPill.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.status_pill_pending))
                    cb.setImageResource(R.drawable.ic_checkbox_warning)
                } else {
                    statusPill.text = "TAKEN"
                    statusPill.setTextColor(requireContext().getColor(R.color.status_pill_taken_text))
                    statusPill.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.status_pill_taken))
                    cb.setImageResource(R.drawable.ic_checkbox_checked)
                }

                cb.isClickable = false
                cb.isFocusable = false
                card.findViewById<View>(R.id.btnOptions).visibility = View.GONE
                containerDateMedications.addView(card)
            }
        } else if (scheduledMedications.isNotEmpty()) {
            emptyDateMedicationsContainer.visibility = View.GONE
            scheduledMedications.forEach { med ->
                val card = LayoutInflater.from(requireContext()).inflate(R.layout.medication_card_item, containerDateMedications, false)
                card.findViewById<TextView>(R.id.tvMedName).text = med.name
                card.findViewById<TextView>(R.id.tvMedDetails).text = med.details
                card.findViewById<TextView>(R.id.tvMedTime).text = "Scheduled for ${med.time}"

                val statusPill = card.findViewById<TextView>(R.id.tvStatusPill)
                statusPill.visibility = View.VISIBLE
                statusPill.text = "SCHEDULED"
                statusPill.setTextColor(requireContext().getColor(R.color.status_pill_pending_text))
                statusPill.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(R.color.status_pill_pending))

                val cb = card.findViewById<ImageView>(R.id.cbTaken)
                cb.setImageResource(R.drawable.ic_checkbox_unchecked)
                cb.isClickable = false
                cb.isFocusable = false
                card.findViewById<View>(R.id.btnOptions).visibility = View.GONE
                containerDateMedications.addView(card)
            }
        } else {
            emptyDateMedicationsContainer.visibility = View.VISIBLE
        }
    }

    private inner class CalendarGridAdapter : RecyclerView.Adapter<CalendarGridAdapter.ViewHolder>() {

        private var items: List<CalendarDayItem> = emptyList()
        private var selectedCal: Calendar? = null

        fun submitList(newItems: List<CalendarDayItem>, selected: Calendar) {
            this.items = newItems
            this.selectedCal = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            if (item.dayNumber == 0) {
                holder.tvDayNumber.text = ""
                holder.vDayBackground.visibility = View.GONE
                holder.itemView.isClickable = false
            } else {
                holder.tvDayNumber.text = item.dayNumber.toString()
                holder.itemView.isClickable = true

                val isSelected = selectedCal?.let { sel ->
                    item.calendar?.get(Calendar.YEAR) == sel.get(Calendar.YEAR) &&
                            item.calendar?.get(Calendar.DAY_OF_YEAR) == sel.get(Calendar.DAY_OF_YEAR)
                } ?: false

                holder.vDayBackground.visibility = if (isSelected) View.VISIBLE else View.GONE

                val context = holder.itemView.context
                val dayTextColor = when (item.colorStatus) {
                    DayColorStatus.GREEN -> Color.parseColor("#22C55E") // Green when all taken
                    DayColorStatus.YELLOW -> Color.parseColor("#EAB308") // Yellow when skipped/missed
                    DayColorStatus.NONE -> context.getColor(R.color.text_primary) // Default theme color (White in Dark Mode / Black in Light Mode)
                }
                holder.tvDayNumber.setTextColor(dayTextColor)

                holder.itemView.setOnClickListener {
                    item.calendar?.let { cal ->
                        selectedDateCalendar.timeInMillis = cal.timeInMillis
                        selectedCal = cal
                        notifyDataSetChanged()
                        bindSelectedDateList()
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvDayNumber: TextView = v.findViewById(R.id.tvDayNumber)
            val vDayBackground: View = v.findViewById(R.id.vDayBackground)
        }
    }

    enum class DayColorStatus {
        NONE,
        GREEN,
        YELLOW
    }

    private data class CalendarDayItem(
        val dayNumber: Int,
        val dateString: String,
        val calendar: Calendar?,
        val isCurrentMonth: Boolean,
        val records: List<CalendarRecord>,
        val colorStatus: DayColorStatus
    )
}
