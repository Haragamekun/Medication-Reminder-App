package com.example.ocrmedicationapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

class MainPagerAdapter(private val listener: PageActionListener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var medications: List<Medication> = emptyList()
    private var historyRecords: List<MedicationRecord> = emptyList()
    private var onlineItems = mutableListOf<MedInfo>()
    private var searchHistory = emptyList<String>()
    private var suggestion: String? = null
    private var homePageView: View? = null
    private var historyPageView: View? = null

    interface PageActionListener {
        fun onManualAdd()
        fun onMedicationEdit(med: Medication)
        fun onMedicationDelete(med: Medication)
        fun onMedicationToggle(index: Int)
        fun onOnlineSearch(query: String)
        fun onRemoveSearchHistory(query: String)
        fun onCatalogFilter(query: String)
        fun onCatalogItemClick(med: MedInfo)
        fun onExportHistory()
        fun onToggleTheme(view: View)
        fun onNotificationPrefs()
        fun onReliabilityGuide()
        fun onClearHistory()
        fun onAbout()
        fun isOnline(): Boolean
        fun getTakenFormat(taken: Int, total: Int): String
        fun hideKeyboard(view: View) {
            val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        fun getSuggestionsForQuery(query: String): List<String>
        fun getDidYouMeanSuggestion(): String?
        fun onVoiceSearchRequest()
    }

    fun updateMeds(newList: List<Medication>) {
        this.medications = newList
        homePageView?.let { bindHomePage(it) }
        historyPageView?.let { bindHistoryPage(it) }
        notifyDataSetChanged()
    }

    fun updateHistoryRecords(records: List<MedicationRecord>) {
        this.historyRecords = records
        historyPageView?.let { bindHistoryPage(it) }
        notifyDataSetChanged()
    }

    fun updateOnlineCatalog(newList: List<MedInfo>) {
        this.onlineItems = newList.toMutableList()
        gridAdapter?.notifyDataSetChanged()

        onlinePageView?.let { view ->
            val rvGrid = view.findViewById<RecyclerView>(R.id.rvMedicationGrid)
            val noResults = view.findViewById<View>(R.id.noResultsView)
            if (onlineItems.isEmpty()) {
                rvGrid?.visibility = View.GONE
                noResults?.visibility = View.VISIBLE
            } else {
                rvGrid?.visibility = View.VISIBLE
                noResults?.visibility = View.GONE
            }
        }
    }

    fun updateHistory(newList: List<String>) {
        this.searchHistory = newList
        // Refresh online page to show history if empty
        notifyItemChanged(2)
    }

    fun updateSuggestion(suggestion: String?) {
        if (this.suggestion == suggestion) return
        this.suggestion = suggestion
        
        onlinePageView?.let { view ->
            val didYouMeanContainer = view.findViewById<View>(R.id.didYouMeanContainer)
            val tvSuggestion = view.findViewById<TextView>(R.id.tvSuggestion)
            val etSearch = view.findViewById<EditText>(R.id.etOnlineSearch)
            val flyoutContainer = view.findViewById<View>(R.id.cardFlyoutSuggestions)

            val sug = suggestion
            if (sug != null) {
                didYouMeanContainer.visibility = View.VISIBLE
                tvSuggestion.text = sug
                tvSuggestion.setOnClickListener {
                    etSearch.setText(sug)
                    listener.onOnlineSearch(sug)
                    listener.hideKeyboard(etSearch)
                    flyoutContainer.visibility = View.GONE
                }
            } else {
                didYouMeanContainer.visibility = View.GONE
            }
        }
    }

    override fun getItemViewType(position: Int): Int = position

    override fun getItemCount(): Int = 4

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = when (viewType) {
            0 -> R.layout.page_home
            1 -> R.layout.page_history
            2 -> R.layout.page_online
            else -> R.layout.page_settings
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (position) {
            0 -> bindHomePage(holder.itemView)
            1 -> bindHistoryPage(holder.itemView)
            2 -> bindOnlinePage(holder.itemView)
            3 -> bindSettingsPage(holder.itemView)
        }
    }

    private fun bindHomePage(view: View) {
        homePageView = view
        val listContainer = view.findViewById<ViewGroup>(R.id.medicationList)
        val emptyState = view.findViewById<View>(R.id.emptyStateContainer)
        val tvActive = view.findViewById<TextView>(R.id.tvActiveMedsCount)
        val tvTaken = view.findViewById<TextView>(R.id.tvTakenMedsCount)
        val btnManual = view.findViewById<View>(R.id.btnManualAdd)

        btnManual.addTactileTouchEffect(0.94f)
        btnManual.setOnClickListener { listener.onManualAdd() }

        listContainer.removeAllViews()
        if (medications.isEmpty()) {
            emptyState.visibility = View.VISIBLE
        } else {
            emptyState.visibility = View.GONE
            medications.forEachIndexed { index, med ->
                val card = LayoutInflater.from(view.context).inflate(R.layout.medication_card_item, listContainer, false)
                card.addTactileTouchEffect(0.98f)
                card.findViewById<TextView>(R.id.tvMedName).text = med.name
                card.findViewById<TextView>(R.id.tvMedDetails).text = med.details
                card.findViewById<TextView>(R.id.tvMedTime).text = med.time
                
                val statusPill = card.findViewById<TextView>(R.id.tvStatusPill)
                if (med.isTaken) {
                    statusPill.visibility = View.VISIBLE
                    statusPill.text = "TAKEN"
                    statusPill.setTextColor(view.context.getColor(R.color.status_pill_taken_text))
                    statusPill.backgroundTintList = android.content.res.ColorStateList.valueOf(view.context.getColor(R.color.status_pill_taken))
                } else {
                    statusPill.visibility = View.VISIBLE
                    statusPill.text = "PENDING"
                    statusPill.setTextColor(view.context.getColor(R.color.status_pill_pending_text))
                    statusPill.backgroundTintList = android.content.res.ColorStateList.valueOf(view.context.getColor(R.color.status_pill_pending))
                }

                val cb = card.findViewById<ImageView>(R.id.cbTaken)
                if (med.isTaken) {
                    cb.setImageResource(R.drawable.ic_checkbox_checked)
                } else {
                    cb.setImageResource(R.drawable.ic_checkbox_unchecked)
                }
                cb.setOnClickListener {
                    cb.animateRotatePop()
                    statusPill.animateSpringPop(1.15f)
                    listener.onMedicationToggle(index)
                }

                val btnOpt = card.findViewById<ImageButton>(R.id.btnOptions)
                btnOpt.addTactileTouchEffect(0.90f)
                btnOpt.setOnClickListener { btn ->
                    showActionMenu(btn, med)
                }
                listContainer.addView(card)
            }
        }
        tvActive.text = medications.size.toString()
        tvTaken.text = listener.getTakenFormat(medications.count { it.isTaken }, medications.size)
    }

    private fun showActionMenu(anchor: View, med: Medication) {
        val context = anchor.context
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_medication_actions, null)
        val popup = android.widget.PopupWindow(
            dialogView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        dialogView.findViewById<View>(R.id.btnEditMed).setOnClickListener {
            listener.onMedicationEdit(med)
            popup.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnDeleteMed).setOnClickListener {
            listener.onMedicationDelete(med)
            popup.dismiss()
        }

        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.elevation = 0f
        popup.showAsDropDown(anchor, -180, 0)
    }

    private fun getDateHeaderLabel(timestamp: Long): String {
        if (timestamp <= 0L) return "TAKEN TODAY"

        val nowCalendar = java.util.Calendar.getInstance()
        val medCalendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }

        val nowYear = nowCalendar.get(java.util.Calendar.YEAR)
        val nowDayOfYear = nowCalendar.get(java.util.Calendar.DAY_OF_YEAR)

        val medYear = medCalendar.get(java.util.Calendar.YEAR)
        val medDayOfYear = medCalendar.get(java.util.Calendar.DAY_OF_YEAR)

        if (nowYear == medYear && nowDayOfYear == medDayOfYear) {
            return "TAKEN TODAY"
        }

        val yesterdayCalendar = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        val yYear = yesterdayCalendar.get(java.util.Calendar.YEAR)
        val yDayOfYear = yesterdayCalendar.get(java.util.Calendar.DAY_OF_YEAR)

        if (medYear == yYear && medDayOfYear == yDayOfYear) {
            return "YESTERDAY"
        }

        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US)
        return sdf.format(medCalendar.time).uppercase()
    }

    private fun bindHistoryPage(view: View) {
        historyPageView = view
        val historyList = view.findViewById<ViewGroup>(R.id.historyList)
        val emptyHistory = view.findViewById<View>(R.id.emptyHistoryContainer)
        historyList.removeAllViews()

        if (historyRecords.isEmpty()) {
            emptyHistory?.visibility = View.VISIBLE
        } else {
            emptyHistory?.visibility = View.GONE

            val groupedHistory = historyRecords
                .sortedByDescending { if (it.takenTimestamp > 0L) it.takenTimestamp else Long.MAX_VALUE }
                .groupBy { getDateHeaderLabel(it.takenTimestamp) }

            groupedHistory.forEach { (dateHeader, recordsInGroup) ->
                val headerTv = TextView(view.context).apply {
                    text = dateHeader
                    setTextColor(view.context.getColor(R.color.accent_teal))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    letterSpacing = 0.1f
                    setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
                }
                historyList.addView(headerTv)

                recordsInGroup.forEach { record ->
                    val card = LayoutInflater.from(view.context).inflate(R.layout.medication_card_item, historyList, false)
                    card.findViewById<TextView>(R.id.tvMedName).text = record.name
                    card.findViewById<TextView>(R.id.tvMedDetails).text = record.details
                    
                    val sdfTime = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
                    val completionTimeStr = if (record.takenTimestamp > 0L) sdfTime.format(java.util.Date(record.takenTimestamp)) else record.time
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
                        statusPill.setTextColor(view.context.getColor(R.color.status_pill_pending_text))
                        statusPill.backgroundTintList = android.content.res.ColorStateList.valueOf(view.context.getColor(R.color.status_pill_pending))
                        cb.setImageResource(R.drawable.ic_checkbox_warning)
                    } else {
                        statusPill.text = "TAKEN"
                        statusPill.setTextColor(view.context.getColor(R.color.status_pill_taken_text))
                        statusPill.backgroundTintList = android.content.res.ColorStateList.valueOf(view.context.getColor(R.color.status_pill_taken))
                        cb.setImageResource(R.drawable.ic_checkbox_checked)
                    }

                    cb.isClickable = false
                    cb.isFocusable = false
                    card.findViewById<View>(R.id.btnOptions).visibility = View.GONE
                    historyList.addView(card)
                }
            }
        }
    }

    private var gridAdapter: RecyclerView.Adapter<*>? = null
    private var flyoutAdapter: RecyclerView.Adapter<*>? = null
    private var onlinePageView: View? = null

    private fun bindOnlinePage(view: View) {
        onlinePageView = view
        val noInternet = view.findViewById<View>(R.id.noInternetView)
        val noResults = view.findViewById<View>(R.id.noResultsView)
        val rvGrid = view.findViewById<RecyclerView>(R.id.rvMedicationGrid)
        val etSearch = view.findViewById<EditText>(R.id.etOnlineSearch)
        val btnClear = view.findViewById<ImageButton>(R.id.btnClearSearch)
        val btnVoice = view.findViewById<ImageButton>(R.id.btnVoiceSearch)
        val didYouMeanContainer = view.findViewById<View>(R.id.didYouMeanContainer)
        val tvSuggestion = view.findViewById<TextView>(R.id.tvSuggestion)
        val flyoutContainer = view.findViewById<View>(R.id.cardFlyoutSuggestions)
        val rvFlyout = view.findViewById<RecyclerView>(R.id.rvFlyoutSuggestions)
        val dismissOverlay = view.findViewById<View>(R.id.flyoutDismissOverlay)
        val tvFlyoutHeader = view.findViewById<TextView>(R.id.tvFlyoutHeader)

        val voiceOverlay = view.findViewById<View>(R.id.voiceRevealOverlay)
        voiceOverlay?.setOnClickListener {
            stopVoiceSearchAnimation()
        }

        btnVoice?.addTactileTouchEffect(0.88f)
        btnVoice?.setOnClickListener {
            startVoiceSearchAnimation()
            listener.onVoiceSearchRequest()
        }

        btnClear.setOnClickListener {
            etSearch.text.clear()
            flyoutContainer.visibility = View.GONE
            dismissOverlay.visibility = View.GONE
        }

        dismissOverlay.setOnClickListener {
            flyoutContainer.visibility = View.GONE
            dismissOverlay.visibility = View.GONE
            listener.hideKeyboard(etSearch)
        }

        // Suggestion management
        if (suggestion != null) {
            didYouMeanContainer.visibility = View.VISIBLE
            tvSuggestion.text = suggestion
            tvSuggestion.setOnClickListener {
                etSearch.setText(suggestion)
                listener.onOnlineSearch(suggestion!!)
                listener.hideKeyboard(etSearch)
                flyoutContainer.visibility = View.GONE
                dismissOverlay.visibility = View.GONE
            }
        } else {
            didYouMeanContainer.visibility = View.GONE
        }

        // Search logic
        etSearch.setOnEditorActionListener { _, _, _ ->
            val query = etSearch.text.toString()
            if (query.isNotEmpty()) {
                listener.onOnlineSearch(query)
                listener.hideKeyboard(etSearch)
                flyoutContainer.visibility = View.GONE
                dismissOverlay.visibility = View.GONE
            }
            true
        }

        etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && etSearch.text.isEmpty() && searchHistory.isNotEmpty()) {
                flyoutContainer.visibility = View.VISIBLE
                dismissOverlay.visibility = View.VISIBLE
                updateFlyout(rvFlyout, "", isHistory = true, header = tvFlyoutHeader)
            }
        }

        etSearch.setOnClickListener {
            if (etSearch.text.isEmpty() && searchHistory.isNotEmpty()) {
                flyoutContainer.visibility = View.VISIBLE
                dismissOverlay.visibility = View.VISIBLE
                updateFlyout(rvFlyout, "", isHistory = true, header = tvFlyoutHeader)
            }
        }

        if (etSearch.tag == null) {
            etSearch.tag = "listener_attached"
            etSearch.addTextChangedListener { s ->
                val query = s?.toString() ?: ""
                listener.onCatalogFilter(query)
                
                btnClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                if (query.isNotEmpty()) {
                    flyoutContainer.visibility = View.VISIBLE
                    dismissOverlay.visibility = View.VISIBLE
                    updateFlyout(rvFlyout, query, isHistory = false, header = tvFlyoutHeader)
                } else if (searchHistory.isNotEmpty()) {
                    flyoutContainer.visibility = View.VISIBLE
                    dismissOverlay.visibility = View.VISIBLE
                    updateFlyout(rvFlyout, "", isHistory = true, header = tvFlyoutHeader)
                } else {
                    flyoutContainer.visibility = View.GONE
                    dismissOverlay.visibility = View.GONE
                }
            }
        }

        val searchContainer = view.findViewById<View>(R.id.onlineSearchContainer)

        if (!listener.isOnline()) {
            noInternet.visibility = View.VISIBLE
            searchContainer.visibility = View.GONE
            didYouMeanContainer.visibility = View.GONE
            rvGrid.visibility = View.GONE
            noResults.visibility = View.GONE
            flyoutContainer.visibility = View.GONE
        } else {
            noInternet.visibility = View.GONE
            searchContainer.visibility = View.VISIBLE
            searchContainer.translationY = 0f
            searchContainer.alpha = 1f
            rvGrid.visibility = if (onlineItems.isEmpty()) View.GONE else View.VISIBLE
            noResults.visibility = if (onlineItems.isEmpty()) View.VISIBLE else View.GONE
            setupGrid(rvGrid)
            rvGrid.clearOnScrollListeners()
            rvGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var isSearchHidden = false

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    // Scroll Down -> Hide Search Bar
                    if (dy > 12 && !isSearchHidden && flyoutContainer.visibility != View.VISIBLE) {
                        isSearchHidden = true
                        searchContainer.animate()
                            .translationY(-searchContainer.height.toFloat() - 40f)
                            .alpha(0f)
                            .setDuration(220)
                            .start()
                    }
                    // Scroll Up -> Show Search Bar
                    else if (dy < -12 && isSearchHidden) {
                        isSearchHidden = false
                        searchContainer.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(220)
                            .start()
                    }
                }
            })
        }
    }

    private fun setupGrid(rvGrid: RecyclerView) {
        if (gridAdapter == null) {
            gridAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val v = LayoutInflater.from(parent.context).inflate(R.layout.item_medication_grid, parent, false)
                    return object : RecyclerView.ViewHolder(v) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                    val item = onlineItems[pos]
                    holder.itemView.addTactileTouchEffect(0.96f)
                    holder.itemView.findViewById<TextView>(R.id.tvMedName).text = item.name
                    val tvSub = holder.itemView.findViewById<TextView>(R.id.tvMedSub)
                    if (tvSub != null) {
                        val subText = when {
                            item.indication.isNotBlank() -> item.indication
                            item.description.isNotBlank() -> item.description
                            item.form.isNotBlank() -> item.form
                            else -> "Tap for full details"
                        }
                        tvSub.text = subText
                    }
                    holder.itemView.findViewById<ImageView>(R.id.ivMedImage).load(item.imageUrl) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                    }
                    holder.itemView.setOnClickListener { listener.onCatalogItemClick(item) }
                }

                override fun getItemCount(): Int = onlineItems.size
            }
        }
        rvGrid.adapter = gridAdapter
        gridAdapter?.notifyDataSetChanged()
    }

    private fun updateFlyout(rvFlyout: RecyclerView, query: String, isHistory: Boolean, header: TextView) {
        val displayItems = if (isHistory) {
            header.text = rvFlyout.context.getString(R.string.header_recent_searches)
            searchHistory.take(5)
        } else {
            header.text = rvFlyout.context.getString(R.string.header_suggestions)
            listener.getSuggestionsForQuery(query)
        }
        
        flyoutAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_flyout_suggestion, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                if (pos !in displayItems.indices) return
                val name = displayItems[pos]
                val v = holder.itemView
                val tv = v.findViewById<TextView>(R.id.tvSuggestionText)
                val btnRemove = v.findViewById<ImageButton>(R.id.btnRemoveHistory)

                tv.text = name

                val icon = if (isHistory) android.R.drawable.ic_menu_recent_history else R.drawable.ic_search
                tv.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                    tv,
                    android.content.res.ColorStateList.valueOf(v.context.getColor(R.color.accent_teal))
                )

                if (isHistory) {
                    btnRemove.visibility = View.VISIBLE
                    btnRemove.setOnClickListener {
                        listener.onRemoveSearchHistory(name)
                        val updatedHistory = searchHistory.filter { it != name }
                        searchHistory = updatedHistory
                        if (updatedHistory.isEmpty()) {
                            onlinePageView?.findViewById<View>(R.id.cardFlyoutSuggestions)?.visibility = View.GONE
                            onlinePageView?.findViewById<View>(R.id.flyoutDismissOverlay)?.visibility = View.GONE
                        } else {
                            updateFlyout(rvFlyout, query, isHistory = true, header = header)
                        }
                    }
                } else {
                    btnRemove.visibility = View.GONE
                }

                v.setOnClickListener {
                    listener.onOnlineSearch(name)
                    // Hide flyout and dismiss overlay
                    onlinePageView?.findViewById<View>(R.id.cardFlyoutSuggestions)?.visibility = View.GONE
                    onlinePageView?.findViewById<View>(R.id.flyoutDismissOverlay)?.visibility = View.GONE
                }
            }

            override fun getItemCount(): Int = displayItems.size
        }
        rvFlyout.adapter = flyoutAdapter
    }

    private fun bindSettingsPage(view: View) {
        val tvCurrentTheme = view.findViewById<TextView>(R.id.tvCurrentTheme)
        val isDark = (view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        tvCurrentTheme.text = if (isDark) "Dark" else "Light"

        val btnTheme = view.findViewById<View>(R.id.btnToggleTheme)
        val btnNotif = view.findViewById<View>(R.id.btnNotificationPrefs)
        val btnReliability = view.findViewById<View>(R.id.btnReliabilityGuide)
        val btnClear = view.findViewById<View>(R.id.btnClearHistory)
        val btnExport = view.findViewById<View>(R.id.btnExportHistory)
        val btnAbout = view.findViewById<View>(R.id.btnAbout)

        listOf(btnTheme, btnNotif, btnReliability, btnClear, btnExport, btnAbout).forEach { btn ->
            btn?.addTactileTouchEffect(0.97f)
        }

        btnTheme?.setOnClickListener { listener.onToggleTheme(it) }
        btnNotif?.setOnClickListener { listener.onNotificationPrefs() }
        btnReliability?.setOnClickListener { listener.onReliabilityGuide() }
        btnClear?.setOnClickListener { listener.onClearHistory() }
        btnExport?.setOnClickListener { listener.onExportHistory() }
        btnAbout?.setOnClickListener { listener.onAbout() }
    }

    fun startVoiceSearchAnimation() {
        val pageView = onlinePageView ?: return
        val container = pageView.findViewById<View>(R.id.onlineSearchContainer) ?: return
        val btnVoice = pageView.findViewById<View>(R.id.btnVoiceSearch) ?: return
        val overlay = pageView.findViewById<View>(R.id.voiceRevealOverlay) ?: return
        val revealView = pageView.findViewById<View>(R.id.voiceRevealView) ?: return
        val tvListening = pageView.findViewById<TextView>(R.id.tvListeningStatus) ?: return
        val ivListening = pageView.findViewById<ImageView>(R.id.ivListeningMic) ?: return

        container.clipToOutline = true

        val accentColor = ContextCompat.getColor(pageView.context, R.color.accent_teal)
        revealView.setBackgroundColor(accentColor)

        val isDark = (pageView.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#FFFFFF")
        tvListening.setTextColor(textColor)
        ivListening.setColorFilter(textColor)

        container.post {
            val locationBtn = IntArray(2)
            btnVoice.getLocationOnScreen(locationBtn)
            val locationContainer = IntArray(2)
            container.getLocationOnScreen(locationContainer)

            val cx = if (btnVoice.width > 0) locationBtn[0] - locationContainer[0] + btnVoice.width / 2 else container.width - 40
            val cy = if (btnVoice.height > 0) locationBtn[1] - locationContainer[1] + btnVoice.height / 2 else container.height / 2
            val finalRadius = Math.hypot(container.width.toDouble(), container.height.toDouble()).toFloat()

            overlay.visibility = View.VISIBLE
            val anim = ViewAnimationUtils.createCircularReveal(overlay, cx, cy, 0f, finalRadius)
            anim.duration = 450
            anim.start()

            // Pulse micro-animation on listening mic icon
            (ivListening.tag as? Animator)?.cancel()
            val pulse = ObjectAnimator.ofPropertyValuesHolder(
                ivListening,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.25f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.25f, 1.0f)
            ).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
            ivListening.tag = pulse
        }
    }

    fun stopVoiceSearchAnimation() {
        val pageView = onlinePageView ?: return
        val container = pageView.findViewById<View>(R.id.onlineSearchContainer) ?: return
        val btnVoice = pageView.findViewById<View>(R.id.btnVoiceSearch) ?: return
        val overlay = pageView.findViewById<View>(R.id.voiceRevealOverlay) ?: return
        val ivListening = pageView.findViewById<ImageView>(R.id.ivListeningMic)

        (ivListening?.tag as? Animator)?.cancel()
        ivListening?.scaleX = 1.0f
        ivListening?.scaleY = 1.0f

        if (overlay.visibility != View.VISIBLE) return

        val locationBtn = IntArray(2)
        btnVoice.getLocationOnScreen(locationBtn)
        val locationContainer = IntArray(2)
        container.getLocationOnScreen(locationContainer)

        val cx = if (btnVoice.width > 0) locationBtn[0] - locationContainer[0] + btnVoice.width / 2 else container.width - 40
        val cy = if (btnVoice.height > 0) locationBtn[1] - locationContainer[1] + btnVoice.height / 2 else container.height / 2
        val startRadius = Math.hypot(container.width.toDouble(), container.height.toDouble()).toFloat()

        val anim = ViewAnimationUtils.createCircularReveal(overlay, cx, cy, startRadius, 0f)
        anim.duration = 350
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                overlay.visibility = View.GONE
            }
        })
        anim.start()
    }

    fun setSearchQuery(query: String) {
        val etSearch = onlinePageView?.findViewById<EditText>(R.id.etOnlineSearch)
        etSearch?.setText(query)
        etSearch?.setSelection(query.length)
    }
}
