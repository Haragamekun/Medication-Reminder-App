package com.example.ocrmedicationapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.RecyclerView
import coil.load

class MainPagerAdapter(private val listener: PageActionListener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var medications: List<Medication> = emptyList()
    private var onlineItems = mutableListOf<MedInfo>()
    private var searchHistory = emptyList<String>()
    private var suggestion: String? = null

    interface PageActionListener {
        fun onManualAdd()
        fun onMedicationEdit(med: Medication)
        fun onMedicationDelete(med: Medication)
        fun onMedicationToggle(index: Int)
        fun onOnlineSearch(query: String)
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
        fun hideKeyboard(view: View)
    }

    fun updateMeds(newList: List<Medication>) {
        this.medications = newList
        // Only notify home and history pages to avoid resetting search input
        notifyItemChanged(0)
        notifyItemChanged(1)
    }

    fun updateOnlineCatalog(newList: List<MedInfo>) {
        val uniqueNewItems = newList.filter { newItem -> 
            onlineItems.none { it.name == newItem.name } 
        }
        if (uniqueNewItems.isNotEmpty()) {
            onlineItems.addAll(uniqueNewItems)
            // Fix: Update existing adapter instead of rebinding page
            gridAdapter?.notifyDataSetChanged()
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

            if (suggestion != null) {
                didYouMeanContainer.visibility = View.VISIBLE
                tvSuggestion.text = suggestion
                tvSuggestion.setOnClickListener {
                    etSearch.setText(suggestion)
                    listener.onOnlineSearch(suggestion)
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
        val listContainer = view.findViewById<ViewGroup>(R.id.medicationList)
        val emptyState = view.findViewById<View>(R.id.emptyStateContainer)
        val tvActive = view.findViewById<TextView>(R.id.tvActiveMedsCount)
        val tvTaken = view.findViewById<TextView>(R.id.tvTakenMedsCount)
        val btnManual = view.findViewById<View>(R.id.btnManualAdd)

        btnManual.setOnClickListener { listener.onManualAdd() }

        listContainer.removeAllViews()
        if (medications.isEmpty()) {
            emptyState.visibility = View.VISIBLE
        } else {
            emptyState.visibility = View.GONE
            medications.forEachIndexed { index, med ->
                val card = LayoutInflater.from(view.context).inflate(R.layout.medication_card_item, listContainer, false)
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

                val cb = card.findViewById<CheckBox>(R.id.cbTaken)
                cb.isChecked = med.isTaken
                cb.setOnCheckedChangeListener { _, _ -> listener.onMedicationToggle(index) }

                card.findViewById<ImageButton>(R.id.btnOptions).setOnClickListener { btn ->
                    val popup = PopupMenu(view.context, btn)
                    popup.menu.add("Edit")
                    popup.menu.add("Delete")
                    popup.setOnMenuItemClickListener { item ->
                        when (item.title) {
                            "Edit" -> listener.onMedicationEdit(med)
                            "Delete" -> listener.onMedicationDelete(med)
                        }
                        true
                    }
                    popup.show()
                }
                listContainer.addView(card)
            }
        }
        tvActive.text = medications.size.toString()
        tvTaken.text = listener.getTakenFormat(medications.count { it.isTaken }, medications.size)
    }

    private fun bindHistoryPage(view: View) {
        val historyList = view.findViewById<ViewGroup>(R.id.historyList)
        historyList.removeAllViews()
        val historyMeds = medications.filter { it.isTaken }
        historyMeds.forEach { med ->
            val card = LayoutInflater.from(view.context).inflate(R.layout.medication_card_item, historyList, false)
            card.findViewById<TextView>(R.id.tvMedName).text = med.name
            card.findViewById<TextView>(R.id.tvMedDetails).text = med.details
            card.findViewById<TextView>(R.id.tvMedTime).text = med.time
            
            val statusPill = card.findViewById<TextView>(R.id.tvStatusPill)
            statusPill.visibility = View.VISIBLE
            statusPill.text = "TAKEN"
            statusPill.setTextColor(view.context.getColor(R.color.status_pill_taken_text))
            statusPill.backgroundTintList = android.content.res.ColorStateList.valueOf(view.context.getColor(R.color.status_pill_taken))

            card.findViewById<CheckBox>(R.id.cbTaken).apply {
                isChecked = true
                isEnabled = false
            }
            card.findViewById<View>(R.id.btnOptions).visibility = View.GONE
            historyList.addView(card)
        }
    }

    private var gridAdapter: RecyclerView.Adapter<*>? = null
    private var flyoutAdapter: RecyclerView.Adapter<*>? = null
    private var onlinePageView: View? = null

    private fun bindOnlinePage(view: View) {
        onlinePageView = view
        val noInternet = view.findViewById<View>(R.id.noInternetView)
        val rvGrid = view.findViewById<RecyclerView>(R.id.rvMedicationGrid)
        val etSearch = view.findViewById<EditText>(R.id.etOnlineSearch)
        val didYouMeanContainer = view.findViewById<View>(R.id.didYouMeanContainer)
        val tvSuggestion = view.findViewById<TextView>(R.id.tvSuggestion)
        val flyoutContainer = view.findViewById<View>(R.id.cardFlyoutSuggestions)
        val rvFlyout = view.findViewById<RecyclerView>(R.id.rvFlyoutSuggestions)
        val dismissOverlay = view.findViewById<View>(R.id.flyoutDismissOverlay)
        val tvFlyoutHeader = view.findViewById<TextView>(R.id.tvFlyoutHeader)

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

        // Fix disappearing text: only set listener if not already set (using a tag)
        if (etSearch.tag == null) {
            etSearch.tag = "listener_attached"
            etSearch.addTextChangedListener { s ->
                val query = s?.toString() ?: ""
                listener.onCatalogFilter(query)

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

        if (!listener.isOnline()) {
            noInternet.visibility = View.VISIBLE
            rvGrid.visibility = View.GONE
            flyoutContainer.visibility = View.GONE
        } else {
            noInternet.visibility = View.GONE
            rvGrid.visibility = View.VISIBLE
            setupGrid(rvGrid)
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
                    holder.itemView.findViewById<TextView>(R.id.tvMedName).text = item.name
                    holder.itemView.findViewById<ImageView>(R.id.ivMedImage).load(item.imageUrl) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                    }
                    holder.itemView.setOnClickListener { listener.onCatalogItemClick(item) }
                }

                override fun getItemCount(): Int = onlineItems.size
            }
            rvGrid.adapter = gridAdapter
        } else {
            gridAdapter?.notifyDataSetChanged()
        }
    }

    private fun updateFlyout(rvFlyout: RecyclerView, query: String, isHistory: Boolean, header: TextView) {
        val displayItems = if (isHistory) {
            header.text = rvFlyout.context.getString(R.string.header_recent_searches)
            searchHistory.take(5)
        } else {
            header.text = rvFlyout.context.getString(R.string.header_suggestions)
            onlineItems.filter { it.name.contains(query, ignoreCase = true) }.map { it.name }.take(5)
        }
        
        flyoutAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val p = (16 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(parent.context.getColor(R.color.text_primary))
                    background = androidx.appcompat.content.res.AppCompatResources.getDrawable(parent.context, android.R.drawable.list_selector_background)
                    isClickable = true
                    isFocusable = true
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val name = displayItems[pos]
                (holder.itemView as TextView).text = name
                holder.itemView.setOnClickListener {
                    listener.onOnlineSearch(name)
                    // Hide flyout and dismiss overlay
                    val flyoutContainer = rvFlyout.parent.parent as View
                    flyoutContainer.visibility = View.GONE
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

        view.findViewById<View>(R.id.btnToggleTheme).setOnClickListener { listener.onToggleTheme(it) }
        view.findViewById<View>(R.id.btnNotificationPrefs).setOnClickListener { listener.onNotificationPrefs() }
        view.findViewById<View>(R.id.btnReliabilityGuide).setOnClickListener { listener.onReliabilityGuide() }
        view.findViewById<View>(R.id.btnClearHistory).setOnClickListener { listener.onClearHistory() }
        view.findViewById<View>(R.id.btnExportHistory).setOnClickListener { listener.onExportHistory() }
        view.findViewById<View>(R.id.btnAbout).setOnClickListener { listener.onAbout() }
    }
}
