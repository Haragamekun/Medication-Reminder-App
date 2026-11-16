package com.example.ocrmedicationapp

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.content.res.ColorStateList
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.graphics.Color
import android.speech.RecognizerIntent
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.ocrmedicationapp.databinding.ActivityMainBinding
import coil.load
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.hypot
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity(), MainPagerAdapter.PageActionListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PrescriptionViewModel by viewModels()
    private var ocrWrapper: MlKitOcrWrapper? = null
    private var photoUri: Uri? = null

    private lateinit var pagerAdapter: MainPagerAdapter

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { processGalleryImage(it) }
        } else {
            Toast.makeText(this, "Failed to capture high-res image", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processGalleryImage(uri)
        } else {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notification permission is required for reminders to work", Toast.LENGTH_LONG).show()
        }
    }

    private val speechToTextLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (::pagerAdapter.isInitialized) {
            pagerAdapter.stopVoiceSearchAnimation()
        }
        if (result.resultCode == RESULT_OK) {
            val spokenTextList = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = spokenTextList?.firstOrNull() ?: ""
            if (spokenText.isNotBlank()) {
                binding.mainViewPager.currentItem = 2
                if (::pagerAdapter.isInitialized) {
                    pagerAdapter.setSearchQuery(spokenText)
                }
                showSearchLoadingOverlay()
                viewModel.fetchMedicationInfo(spokenText)
                Toast.makeText(this, "Searching: \"$spokenText\"", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, show source dialog again to proceed
            showScanSourceDialog()
        } else {
            showSettingsRedirectDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRITICAL_CRASH", "Crash on ${thread.name}: ${throwable.message}", throwable)
            // Note: Don't show toast here as we are dying, but log it
        }

        try {
            installSplashScreen()
        } catch (t: Throwable) {
            Log.e("MainActivity", "SplashScreen failed", t)
        }
        
        val prefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        val savedMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(savedMode)

        super.onCreate(savedInstanceState)
        
        try {
            enableHighRefreshRate()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT
                ),
                navigationBarStyle = SystemBarStyle.auto(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT
                )
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }

            if (savedInstanceState != null) {
                photoUri = BundleCompat.getParcelable(savedInstanceState, "photoUri", Uri::class.java)
            }

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Apply status bar window insets to keep top header cards positioned safely below status bar icons
            ViewCompat.setOnApplyWindowInsetsListener(binding.mainViewPager) { view, insets ->
                val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                view.updatePadding(top = statusBarInset)
                insets
            }

            // Raise floating navigation bar comfortably above the system navigation / gesture bar
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavRow) { view, insets ->
                val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val density = resources.displayMetrics.density
                val basePaddingPx = (24 * density).toInt()
                view.updatePadding(bottom = navBarInset + basePaddingPx)
                insets
            }

            // Initialize ML Kit OCR Engine
            binding.root.postDelayed({
                lifecycleScope.launch {
                    try {
                        Log.d("MainActivity", "Initializing ML Kit OCR Engine")
                        ocrWrapper = MlKitOcrWrapper()
                        Log.d("MainActivity", "ML Kit OCR Engine initialized successfully")
                    } catch (t: Throwable) {
                        Log.e("MainActivity", "ML Kit OCR background failure: ${t.message}", t)
                    }
                }
            }, 500)



            setupViewPager()
            setupClickListeners()
            setupBackPressHandler()
            setupLottieAnimations()
            observeViewModel()
            observeMedIntelligence()
            handleNavigationIntent(intent)
            
            // Postpone non-essential tasks to ensure UI stability
            binding.root.postDelayed({
                if (!isFinishing) {
                    viewModel.fetchLiveCatalog()
                    ReliabilityHeartbeatWorker.enqueue(this)
                }
            }, 1000)
            
            checkBatteryOptimizations()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Critical failure in onCreate", t)
            Toast.makeText(this, "Safe Boot: Application starting with reduced features", Toast.LENGTH_LONG).show()
        }
    }

    private var activeReliabilityDialogView: View? = null

    override fun onResume() {
        super.onResume()
        binding.btnCameraScan.scaleX = 1.0f
        binding.btnCameraScan.scaleY = 1.0f

        activeReliabilityDialogView?.let {
            updateReliabilityModalStatus(it)
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            ReliabilityUtils.performMidnightResetIfNeeded(applicationContext)
        }
        
        // Safe permission request delay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!hasAskedNotifications) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    hasAskedNotifications = true
                }
            }
        }
        checkBatteryOptimizations()
    }

    companion object {
        private var hasAskedBatterySession = false
        private var hasAskedNotifications = false
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

    private fun checkBatteryOptimizations() {
        if (hasAskedBatterySession) return

        val prefs = getSharedPreferences("reliability_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("reliability_guide_completed", false)) {
            return
        }

        val isBatteryRestricted = ReliabilityUtils.isBatteryOptimized(this)
        val isExactAlarmRequired = !ReliabilityUtils.canScheduleExactAlarms(this)
        val isNotificationMissing = !ReliabilityUtils.isNotificationPermissionGranted(this)
        val isMusicAudioMissing = !ReliabilityUtils.isMusicAudioPermissionGranted(this)
        val isOverlayMissing = !ReliabilityUtils.canDrawOverlays(this)
        val isUnusedAppActive = !ReliabilityUtils.isUnusedAppRestrictionDisabled(this)

        if (!isBatteryRestricted && !isExactAlarmRequired && !isNotificationMissing && !isMusicAudioMissing && !isOverlayMissing && !isUnusedAppActive) {
            prefs.edit { putBoolean("reliability_guide_completed", true) }
            return
        }

        if (isBatteryRestricted || isExactAlarmRequired || isNotificationMissing || isMusicAudioMissing || isOverlayMissing || isUnusedAppActive) {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reliability_prompt, null)
            val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
                .setView(dialogView)
                .create()

            dialogView.findViewById<View>(R.id.btnReliabilityGuide).setOnClickListener {
                dialog.dismiss()
                onReliabilityGuide()
                hasAskedBatterySession = true
            }

            dialogView.findViewById<View>(R.id.btnReliabilityCancel).setOnClickListener {
                dialog.dismiss()
                hasAskedBatterySession = true
            }

            dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
            dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.65f)
            dialog.show()

            val displayMetrics = resources.displayMetrics
            val dialogWidth = (320 * displayMetrics.density).toInt()
            dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            dialog.window?.setGravity(android.view.Gravity.CENTER)
        }
    }



    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        val targetTab = intent?.getIntExtra("NAVIGATE_TO_TAB", -1) ?: -1
        if (targetTab >= 0 && ::pagerAdapter.isInitialized) {
            binding.mainViewPager.currentItem = targetTab
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("photoUri", photoUri)
    }

    private fun observeMedIntelligence() {
        viewModel.searchEvent.observe(this) { event ->
            val result = event?.getContentIfNotHandled() ?: return@observe
            hideLoadingOverlay()
            when (result) {
                is SearchEvent.Success -> {
                    binding.mainViewPager.currentItem = 2
                    MedicationDetailDialog.newInstance(result.medInfo)
                        .show(supportFragmentManager, "MedicationDetail")
                }
                is SearchEvent.NotFound -> {
                    Toast.makeText(this, getString(R.string.toast_med_not_found), Toast.LENGTH_LONG).show()
                }
                is SearchEvent.Error -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var isScanningActive = false

    private fun showSearchLoadingOverlay() {
        isScanningActive = false
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.pbSearchingFallback.visibility = View.GONE
        binding.lottieScanning.visibility = View.GONE
        binding.tvScanning.visibility = View.GONE
        binding.lottieSearching.visibility = View.VISIBLE
        binding.tvSearching.visibility = View.VISIBLE
        try {
            binding.lottieSearching.setAnimation(R.raw.searching)
            binding.lottieSearching.repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
            binding.lottieSearching.playAnimation()
        } catch (e: Exception) {
            Log.e("LottieError", "Failed to parse searching.json", e)
            binding.lottieSearching.visibility = View.GONE
            binding.tvSearching.visibility = View.GONE
            binding.pbSearchingFallback.visibility = View.VISIBLE
        }
    }

    private fun showScanLoadingOverlay() {
        isScanningActive = true
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.pbSearchingFallback.visibility = View.GONE
        binding.lottieSearching.visibility = View.GONE
        binding.tvSearching.visibility = View.GONE
        binding.lottieScanning.visibility = View.VISIBLE
        binding.tvScanning.visibility = View.VISIBLE
        try {
            binding.lottieScanning.setAnimation(R.raw.scanning)
            binding.lottieScanning.repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
            binding.lottieScanning.playAnimation()
        } catch (e: Exception) {
            Log.e("LottieError", "Failed to parse scanning.json", e)
            binding.lottieScanning.visibility = View.GONE
            binding.tvScanning.visibility = View.GONE
            binding.pbSearchingFallback.visibility = View.VISIBLE
        }
    }

    private fun hideLoadingOverlay() {
        isScanningActive = false
        binding.loadingOverlay.visibility = View.GONE
        binding.lottieSearching.visibility = View.GONE
        binding.tvSearching.visibility = View.GONE
        binding.lottieScanning.visibility = View.GONE
        binding.tvScanning.visibility = View.GONE
        if (binding.lottieSearching.isAnimating) binding.lottieSearching.cancelAnimation()
        if (binding.lottieScanning.isAnimating) binding.lottieScanning.cancelAnimation()
    }

    private fun setupLottieAnimations() {
        binding.lottieSearching.clearColorFilter()
        binding.lottieSearching.setFailureListener { throwable ->
            Log.e("LottieError", "Failed to load searching.json: ${throwable.message}", throwable)
            binding.lottieSearching.visibility = View.GONE
            binding.tvSearching.visibility = View.GONE
            binding.pbSearchingFallback.visibility = View.VISIBLE
        }

        binding.lottieScanning.clearColorFilter()
        binding.lottieScanning.setRenderMode(com.airbnb.lottie.RenderMode.SOFTWARE)
        binding.lottieScanning.setImageAssetDelegate { asset ->
            try {
                val fileName = asset.fileName
                if (!fileName.isNullOrEmpty() && fileName.contains("base64,")) {
                    val base64Data = fileName.substringAfter("base64,").trim()
                    val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("LottieError", "Failed to decode Lottie image asset: ${asset.id}", e)
                null
            }
        }
        binding.lottieScanning.setFailureListener { throwable ->
            Log.e("LottieError", "Failed to load scanning.json: ${throwable.message}", throwable)
            binding.lottieScanning.visibility = View.GONE
            binding.tvScanning.visibility = View.GONE
            binding.pbSearchingFallback.visibility = View.VISIBLE
        }
    }

    private fun setupViewPager() {
        pagerAdapter = MainPagerAdapter(this)
        binding.mainViewPager.adapter = pagerAdapter
        binding.mainViewPager.isUserInputEnabled = true
        binding.mainViewPager.offscreenPageLimit = 3

        binding.mainViewPager.setPageTransformer { page, position ->
            when {
                position < -1f -> page.alpha = 0f
                position <= 1f -> {
                    val scaleFactor = Math.max(0.93f, 1f - Math.abs(position) * 0.07f)
                    val alphaFactor = Math.max(0.5f, 1f - Math.abs(position) * 0.5f)
                    page.scaleX = scaleFactor
                    page.scaleY = scaleFactor
                    page.alpha = alphaFactor
                }
                else -> page.alpha = 0f
            }
        }

        binding.mainViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateBottomNavUI(position)
            }
        })
    }

    override fun onSaveOrder(orderedList: List<MedicationItemUi>) {
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.updateMedicationOrder(orderedList)
        }
    }

    private val fastOutSlowInInterpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()

    override fun onEditModeChanged(isEditMode: Boolean) {
        val bottomNavRow = binding.bottomNavRow
        val scheduleHeader = binding.mainViewPager.findViewById<View>(R.id.scheduleHeaderLayout)

        if (isEditMode) {
            // Animate bottom navigation bar row sliding down and fading out with smooth Material motion curve
            bottomNavRow.animate()
                .translationY(bottomNavRow.height.toFloat() + 300f)
                .alpha(0f)
                .setDuration(240)
                .setInterpolator(fastOutSlowInInterpolator)
                .start()

            // Animate Current Schedule label & + Manual Add button fading and hiding
            scheduleHeader?.let { header ->
                header.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .setInterpolator(fastOutSlowInInterpolator)
                    .withEndAction { header.visibility = View.GONE }
                    .start()
            }
        } else {
            // Animate bottom navigation bar row sliding back up into view
            bottomNavRow.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(240)
                .setInterpolator(fastOutSlowInInterpolator)
                .start()

            // Animate Current Schedule label & + Manual Add button back into view
            scheduleHeader?.let { header ->
                header.visibility = View.VISIBLE
                header.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(fastOutSlowInInterpolator)
                    .start()
            }
        }
    }

    override fun onManualAdd() {
        showEditMedicationSheet(Medication(name = "", dosage = "", quantity = "", frequency = "", time = "08:00 AM"), false)
    }

    override fun onMedicationEdit(med: Medication) {
        showEditMedicationSheet(med, true)
    }

    override fun onMedicationDelete(med: Medication) {
        showConfirmationDialog(
            title = getString(R.string.confirm_delete_title),
            message = getString(R.string.confirm_delete_msg, med.name)
        ) {
            cancelAlarm(med)
            viewModel.deleteMedication(med.id)
            Toast.makeText(this, getString(R.string.toast_med_deleted), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMedicationToggle(index: Int) {
        viewModel.toggleMedicationStatus(index)
    }

    override fun onOnlineSearch(query: String) {
        currentFocus?.let { hideKeyboard(it) }
        showSearchLoadingOverlay()
        viewModel.fetchMedicationInfo(query)
    }

    override fun onVoiceSearchRequest() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.prompt_voice_search))
        }
        try {
            speechToTextLauncher.launch(intent)
        } catch (e: Exception) {
            if (::pagerAdapter.isInitialized) {
                pagerAdapter.stopVoiceSearchAnimation()
            }
            Toast.makeText(this, "Voice search is not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRemoveSearchHistory(query: String) {
        viewModel.removeFromSearchHistory(query)
    }

    override fun onCatalogFilter(query: String) {
        viewModel.filterCatalog(query)
    }

    override fun onCatalogItemClick(med: MedInfo) {
        currentFocus?.let { hideKeyboard(it) }
        if (med.description.isEmpty()) {
            showSearchLoadingOverlay()
            viewModel.fetchMedicationInfo(med.name)
        } else {
            MedicationDetailDialog.newInstance(med)
                .show(supportFragmentManager, "MedicationDetail")
        }
    }

    override fun getSuggestionsForQuery(query: String): List<String> {
        return viewModel.getSuggestionsForQuery(query)
    }

    override fun getDidYouMeanSuggestion(): String? {
        return viewModel.didYouMeanSuggestion.value
    }

    override fun onExportHistory() {
        val uri = viewModel.exportHistoryToCSV()
        if (uri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Medication Report"))
        } else {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOpenCalendarView() {
        val sheet = CalendarHistorySheet(
            calendarRecords = viewModel.calendarRecords.value ?: emptyList(),
            scheduledMedications = viewModel.medicationList.value ?: emptyList()
        )
        sheet.show(supportFragmentManager, "CalendarHistorySheet")
    }

    override fun onToggleTheme(view: View) {
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val newMode = if (isDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        
        binding.themeRevealView.visibility = View.VISIBLE
        val targetColor = if (isDark) "#F8FAFC".toColorInt() else "#0B0F19".toColorInt()
        binding.themeRevealView.setBackgroundColor(targetColor)
        
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rootLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        
        val cx = location[0] - rootLocation[0] + view.width / 2
        val cy = location[1] - rootLocation[1] + view.height / 2
        val finalRadius = hypot(binding.root.width.toDouble(), binding.root.height.toDouble()).toFloat() * 1.2f
        
        val anim = android.view.ViewAnimationUtils.createCircularReveal(binding.themeRevealView, cx, cy, 0f, finalRadius)
        anim.duration = 600
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                getSharedPreferences("theme_prefs", MODE_PRIVATE).edit {
                    putInt("theme_mode", newMode)
                }
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newMode)
            }
        })
        anim.start()
    }

    override fun onNotificationPrefs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        val notifyPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_notification_prefs, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setView(dialogView)
            .create()

        val switchSound = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSound)
        val switchVibrate = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchVibrate)
        val switchBanners = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchBanners)

        switchSound.isChecked = notifyPrefs.getBoolean("sound_enabled", true)
        switchVibrate.isChecked = notifyPrefs.getBoolean("vibrate_enabled", true)
        switchBanners.isChecked = notifyPrefs.getBoolean("banners_enabled", true)

        val rbBanner = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBanner)
        val rbFullScreen = dialogView.findViewById<android.widget.RadioButton>(R.id.rbFullScreen)
        val rowBannerOption = dialogView.findViewById<View>(R.id.rowBannerOption)
        val rowFullScreenOption = dialogView.findViewById<View>(R.id.rowFullScreenOption)

        fun selectStyle(style: String) {
            val isFullScreen = (style == "fullscreen")
            rbBanner?.isChecked = !isFullScreen
            rbFullScreen?.isChecked = isFullScreen

            rowBannerOption?.isSelected = !isFullScreen
            rowFullScreenOption?.isSelected = isFullScreen

            notifyPrefs.edit { putString("notification_style", if (isFullScreen) "fullscreen" else "banner") }
        }

        val currentStyle = notifyPrefs.getString("notification_style", "banner") ?: "banner"
        selectStyle(currentStyle)

        rowBannerOption?.setOnClickListener { selectStyle("banner") }
        rbBanner?.setOnClickListener { selectStyle("banner") }
        rowFullScreenOption?.setOnClickListener { selectStyle("fullscreen") }
        rbFullScreen?.setOnClickListener { selectStyle("fullscreen") }

        switchSound.setOnCheckedChangeListener { _, isChecked -> notifyPrefs.edit { putBoolean("sound_enabled", isChecked) } }
        switchVibrate.setOnCheckedChangeListener { _, isChecked -> notifyPrefs.edit { putBoolean("vibrate_enabled", isChecked) } }
        switchBanners.setOnCheckedChangeListener { _, isChecked -> notifyPrefs.edit { putBoolean("banners_enabled", isChecked) } }

        dialogView.findViewById<View>(R.id.btnDonePrefs).setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
        dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.65f)
        dialog.show()

        // Force center and precise width
        val displayMetrics = resources.displayMetrics
        val dialogWidth = (350 * displayMetrics.density).toInt()
        dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }

    override fun onReliabilityGuide() {
        showReliabilityGuideModal()
    }

    private fun showReliabilityGuideModal() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reliability_guide, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setView(dialogView)
            .create()

        activeReliabilityDialogView = dialogView
        dialog.setOnDismissListener {
            activeReliabilityDialogView = null
        }

        val btnGrantNotification = dialogView.findViewById<View>(R.id.btnGrantNotification)
        val btnGrantMusicAudio = dialogView.findViewById<View>(R.id.btnGrantMusicAudio)
        val btnExactAlarm = dialogView.findViewById<View>(R.id.btnExactAlarm)
        val btnGrantOverlay = dialogView.findViewById<View>(R.id.btnGrantOverlay)
        val btnDisableUnusedApp = dialogView.findViewById<View>(R.id.btnDisableUnusedApp)
        val btnOptimize = dialogView.findViewById<View>(R.id.btnOptimize)
        val btnAutoStart = dialogView.findViewById<View>(R.id.btnAutoStart)
        val btnTestAlarm = dialogView.findViewById<View>(R.id.btnTestAlarm)
        val btnDone = dialogView.findViewById<View>(R.id.btnDone)

        btnGrantNotification?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Permission already granted by system.", Toast.LENGTH_SHORT).show()
            }
        }

        btnGrantMusicAudio?.setOnClickListener {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissionLauncher.launch(perm)
        }

        btnExactAlarm?.setOnClickListener {
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

        btnGrantOverlay?.setOnClickListener {
            try {
                startActivity(ReliabilityUtils.getOverlaySettingsIntent(this))
            } catch (_: Exception) {
                Toast.makeText(this, "Unable to open overlay settings.", Toast.LENGTH_SHORT).show()
            }
        }

        btnDisableUnusedApp?.setOnClickListener {
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

        btnOptimize?.setOnClickListener {
            try {
                startActivity(ReliabilityUtils.getBatteryOptimizationIntent(this))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        btnAutoStart?.setOnClickListener {
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
            updateReliabilityModalStatus(dialogView)
            if (!success) {
                Toast.makeText(this, "Please look for 'Auto-start' or 'Background' settings manually.", Toast.LENGTH_LONG).show()
            }
        }

        btnTestAlarm?.setOnClickListener {
            ReliabilityUtils.scheduleTestAlarm(this)
            Toast.makeText(this, "Test alarm scheduled for 5 seconds...", Toast.LENGTH_SHORT).show()
        }

        btnDone?.setOnClickListener {
            getSharedPreferences("reliability_prefs", MODE_PRIVATE).edit {
                putBoolean("reliability_guide_completed", true)
            }
            dialog.dismiss()
        }

        updateReliabilityModalStatus(dialogView)

        dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
        dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.65f, blurRadius = 35)
        dialog.show()

        val displayMetrics = resources.displayMetrics
        val dialogWidth = (360 * displayMetrics.density).toInt()
        dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }

    private fun updateReliabilityModalStatus(dialogView: View) {
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

        val setStatusGranted = { tv: TextView, label: String ->
            tv.text = label
            tv.setTextColor(ContextCompat.getColor(this, R.color.status_pill_taken_text))
            tv.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pill_taken))
        }

        val setStatusRequired = { tv: TextView, label: String ->
            tv.text = label
            tv.setTextColor(ContextCompat.getColor(this, R.color.status_pill_pending_text))
            tv.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_pill_pending))
        }

        // Step 1: Notification
        val cardNotification = dialogView.findViewById<View>(R.id.cardNotificationPerm)
        val tvNotificationStatus = dialogView.findViewById<TextView>(R.id.tvNotificationStatus)
        val btnGrantNotification = dialogView.findViewById<View>(R.id.btnGrantNotification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            totalSteps++
            cardNotification?.visibility = View.VISIBLE
            if (isNotificationGranted) {
                completedSteps++
                tvNotificationStatus?.let { setStatusGranted(it, getString(R.string.status_granted)) }
                btnGrantNotification?.isEnabled = false
                btnGrantNotification?.alpha = 0.5f
            } else {
                tvNotificationStatus?.let { setStatusRequired(it, getString(R.string.status_required)) }
                btnGrantNotification?.isEnabled = true
                btnGrantNotification?.alpha = 1.0f
            }
        } else {
            cardNotification?.visibility = View.GONE
        }

        // Step 2: Music & Audio
        totalSteps++
        val tvMusicAudioStatus = dialogView.findViewById<TextView>(R.id.tvMusicAudioStatus)
        val btnGrantMusicAudio = dialogView.findViewById<View>(R.id.btnGrantMusicAudio)
        if (isMusicAudioGranted) {
            completedSteps++
            tvMusicAudioStatus?.let { setStatusGranted(it, getString(R.string.status_granted)) }
            btnGrantMusicAudio?.isEnabled = false
            btnGrantMusicAudio?.alpha = 0.5f
        } else {
            tvMusicAudioStatus?.let { setStatusRequired(it, getString(R.string.status_required)) }
            btnGrantMusicAudio?.isEnabled = true
            btnGrantMusicAudio?.alpha = 1.0f
        }

        // Step 3: Exact Alarms
        totalSteps++
        val tvExactStatus = dialogView.findViewById<TextView>(R.id.tvExactStatus)
        val btnExactAlarm = dialogView.findViewById<View>(R.id.btnExactAlarm)
        if (isExactAlarmGranted) {
            completedSteps++
            tvExactStatus?.let { setStatusGranted(it, getString(R.string.status_granted)) }
            btnExactAlarm?.isEnabled = false
            btnExactAlarm?.alpha = 0.5f
        } else {
            tvExactStatus?.let { setStatusRequired(it, getString(R.string.status_required)) }
            btnExactAlarm?.isEnabled = true
            btnExactAlarm?.alpha = 1.0f
        }

        // Step 4: Display Over Other Apps
        totalSteps++
        val tvOverlayStatus = dialogView.findViewById<TextView>(R.id.tvOverlayStatus)
        val btnGrantOverlay = dialogView.findViewById<View>(R.id.btnGrantOverlay)
        if (isOverlayGranted) {
            completedSteps++
            tvOverlayStatus?.let { setStatusGranted(it, getString(R.string.status_granted)) }
            btnGrantOverlay?.isEnabled = false
            btnGrantOverlay?.alpha = 0.5f
        } else {
            tvOverlayStatus?.let { setStatusRequired(it, getString(R.string.status_required)) }
            btnGrantOverlay?.isEnabled = true
            btnGrantOverlay?.alpha = 1.0f
        }

        // Step 5: Unused App Settings
        val cardUnusedApp = dialogView.findViewById<View>(R.id.cardUnusedAppPerm)
        val tvUnusedAppStatus = dialogView.findViewById<TextView>(R.id.tvUnusedAppStatus)
        val btnDisableUnusedApp = dialogView.findViewById<View>(R.id.btnDisableUnusedApp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            totalSteps++
            cardUnusedApp?.visibility = View.VISIBLE
            if (isUnusedAppDisabled) {
                completedSteps++
                tvUnusedAppStatus?.let { setStatusGranted(it, getString(R.string.status_unused_app_disabled)) }
                btnDisableUnusedApp?.isEnabled = false
                btnDisableUnusedApp?.alpha = 0.5f
            } else {
                tvUnusedAppStatus?.let { setStatusRequired(it, getString(R.string.status_unused_app_enabled)) }
                btnDisableUnusedApp?.isEnabled = true
                btnDisableUnusedApp?.alpha = 1.0f
            }
        } else {
            cardUnusedApp?.visibility = View.GONE
        }

        // Step 6: Battery Optimization
        totalSteps++
        val tvBatteryStatus = dialogView.findViewById<TextView>(R.id.tvBatteryStatus)
        val btnOptimize = dialogView.findViewById<View>(R.id.btnOptimize)
        if (isBatteryOptimized) {
            completedSteps++
            tvBatteryStatus?.let { setStatusGranted(it, getString(R.string.status_optimized)) }
            btnOptimize?.isEnabled = false
            btnOptimize?.alpha = 0.5f
        } else {
            tvBatteryStatus?.let { setStatusRequired(it, getString(R.string.status_restricted)) }
            btnOptimize?.isEnabled = true
            btnOptimize?.alpha = 1.0f
        }

        // Step 7: Auto-Start
        totalSteps++
        val tvAutoStartStatus = dialogView.findViewById<TextView>(R.id.tvAutoStartStatus)
        val btnAutoStart = dialogView.findViewById<View>(R.id.btnAutoStart)
        btnAutoStart?.isEnabled = !isAutoStartDone
        if (isAutoStartDone) {
            completedSteps++
            tvAutoStartStatus?.let { setStatusGranted(it, getString(R.string.status_done)) }
            btnAutoStart?.alpha = 0.5f
        } else {
            tvAutoStartStatus?.let { setStatusRequired(it, getString(R.string.status_pending)) }
            btnAutoStart?.alpha = 1.0f
        }

        // Progress counter and progress bar
        val tvGuideProgress = dialogView.findViewById<TextView>(R.id.tvGuideProgress)
        val pbGuideProgress = dialogView.findViewById<ProgressBar>(R.id.pbGuideProgress)
        tvGuideProgress?.text = "$completedSteps/$totalSteps Done"
        val progressPercent = if (totalSteps > 0) (completedSteps * 100) / totalSteps else 0
        pbGuideProgress?.progress = progressPercent

        val cardTestAlarm = dialogView.findViewById<View>(R.id.cardTestAlarm)
        cardTestAlarm?.visibility = if (isNotificationGranted && isExactAlarmGranted) View.VISIBLE else View.GONE
    }

    override fun onClearHistory() {
        showConfirmationDialog(
            title = getString(R.string.dialog_clear_history_title),
            message = getString(R.string.dialog_clear_history_msg)
        ) {
            viewModel.clearHistory()
            Toast.makeText(this, getString(R.string.toast_history_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_confirmation, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.tvConfirmTitle).text = title
        dialogView.findViewById<TextView>(R.id.tvConfirmMessage).text = message

        dialogView.findViewById<View>(R.id.btnConfirmAction).setOnClickListener {
            onConfirm()
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnCancelAction).setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
        dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.65f)
        dialog.show()

        // Force center and precise width
        val displayMetrics = resources.displayMetrics
        val dialogWidth = (320 * displayMetrics.density).toInt()
        dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }

    override fun onAbout() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.tvAboutDescription).text = getString(R.string.about_text)
        
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }

        val tvVersion = dialogView.findViewById<TextView>(R.id.tvAboutVersion)
        tvVersion.text = getString(R.string.version_format, versionName)

        var versionClickCount = 0
        var lastClickTime = 0L

        tvVersion.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime > 2000) {
                versionClickCount = 0
            }
            lastClickTime = currentTime
            versionClickCount++

            when (versionClickCount) {
                3 -> Toast.makeText(this, "You are 2 steps away from Secret Tweaks.", Toast.LENGTH_SHORT).show()
                4 -> Toast.makeText(this, "You are 1 step away from Secret Tweaks.", Toast.LENGTH_SHORT).show()
                5 -> {
                    versionClickCount = 0
                    dialog.dismiss()
                    showSecretTweaksDialog()
                }
            }
        }

        dialogView.findViewById<View>(R.id.btnAboutClose).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.tvAboutLegal).text = getString(R.string.about_legal)

        dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
        dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.65f)
        dialog.show()

        val displayMetrics = resources.displayMetrics
        val dialogWidth = (320 * displayMetrics.density).toInt()
        val targetHeight = (displayMetrics.heightPixels * 0.68).toInt()
        val window = dialog.window
        if (window != null) {
            window.setLayout(dialogWidth, targetHeight)
            window.setGravity(android.view.Gravity.CENTER)
        }
    }

    private fun showSecretTweaksDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_secret_tweaks, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setView(dialogView)
            .create()

        val btnPreferredScannerCard = dialogView.findViewById<View>(R.id.btnPreferredScannerCard)
        val panelChoices = dialogView.findViewById<View>(R.id.panelPreferredScannerChoices)
        val ivChevron = dialogView.findViewById<ImageView>(R.id.ivExpandChevron)
        val tvCurrentScannerMode = dialogView.findViewById<TextView>(R.id.tvCurrentScannerMode)
        val rgScannerChoices = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgScannerChoices)
        val rbMlKit = dialogView.findViewById<android.widget.RadioButton>(R.id.rbScannerMlKit)
        val rbGemini = dialogView.findViewById<android.widget.RadioButton>(R.id.rbScannerGemini)
        val rbBoth = dialogView.findViewById<android.widget.RadioButton>(R.id.rbScannerBoth)

        val notifyPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
        val currentMode = notifyPrefs.getString("preferred_scanner", "both") ?: "both"

        val updateSummaryLabel = { mode: String ->
            when (mode) {
                "mlkit" -> tvCurrentScannerMode?.text = "Google ML Kit (On-Device)"
                "gemini" -> tvCurrentScannerMode?.text = "Gemini API (Cloud AI)"
                else -> tvCurrentScannerMode?.text = "Both (ML Kit + Gemini API)"
            }
        }

        updateSummaryLabel(currentMode)
        when (currentMode) {
            "mlkit" -> rbMlKit?.isChecked = true
            "gemini" -> rbGemini?.isChecked = true
            else -> rbBoth?.isChecked = true
        }

        var isExpanded = false
        btnPreferredScannerCard?.setOnClickListener {
            isExpanded = !isExpanded
            if (panelChoices != null) {
                if (isExpanded) {
                    expandView(panelChoices)
                } else {
                    collapseView(panelChoices)
                }
            }
            ivChevron?.animate()?.rotation(if (isExpanded) 90f else 0f)?.setDuration(280)?.start()
        }

        rgScannerChoices?.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                R.id.rbScannerMlKit -> "mlkit"
                R.id.rbScannerGemini -> "gemini"
                else -> "both"
            }
            notifyPrefs.edit { putString("preferred_scanner", selectedMode) }
            updateSummaryLabel(selectedMode)
        }

        val btnGeminiChatOption = dialogView.findViewById<View>(R.id.btnGeminiChatOption)
        btnGeminiChatOption?.addTactileTouchEffect(0.96f)
        btnGeminiChatOption?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, GeminiLiveChatActivity::class.java))
        }

        dialogView.findViewById<View>(R.id.btnSecretTweaksClose)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnSecretTweaksDone)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
        dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.65f, blurRadius = 35)
        dialog.show()

        val displayMetrics = resources.displayMetrics
        val dialogWidth = (340 * displayMetrics.density).toInt()
        dialog.window?.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }

    private fun expandView(view: View, duration: Long = 300L) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = view.measuredHeight

        view.layoutParams.height = 0
        view.visibility = View.VISIBLE

        val animator = ValueAnimator.ofInt(0, targetHeight)
        animator.addUpdateListener { valueAnimator ->
            view.layoutParams.height = valueAnimator.animatedValue as Int
            view.requestLayout()
        }
        animator.duration = duration
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    private fun collapseView(view: View, duration: Long = 250L) {
        val initialHeight = view.measuredHeight

        val animator = ValueAnimator.ofInt(initialHeight, 0)
        animator.addUpdateListener { valueAnimator ->
            val value = valueAnimator.animatedValue as Int
            if (value == 0) {
                view.visibility = View.GONE
                view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                view.layoutParams.height = value
                view.requestLayout()
            }
        }
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.start()
    }

    override fun getTakenFormat(taken: Int, total: Int): String {
        return getString(R.string.taken_format, taken, total)
    }

    override fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun isOnline(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun updateBottomNavUI(position: Int) {
        val navItems = listOf(binding.navHomeContainer, binding.navHistoryContainer, binding.navOnlineContainer, binding.navSettingsContainer)
        val selectedColor = ContextCompat.getColor(this, R.color.nav_selected)
        val unselectedColor = ContextCompat.getColor(this, R.color.nav_unselected)
        
        navItems.forEachIndexed { index, container ->
            val isSelected = index == position
            val color = if (isSelected) selectedColor else unselectedColor
            
            // Find icon and text within container
            val icon = (container as ViewGroup).getChildAt(0) as? ImageButton
            val text = container.getChildAt(1) as? TextView
            
            icon?.setColorFilter(color)
            text?.setTextColor(color)
            text?.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            val targetScale = if (isSelected) 1.15f else 1.0f
            icon?.animate()
                ?.scaleX(targetScale)
                ?.scaleY(targetScale)
                ?.setDuration(200)
                ?.start()

            if (isSelected && binding.navIndicator.visibility == View.VISIBLE) {
                container.post {
                    val targetX = container.x + (container.width / 2f) - (binding.navIndicator.width / 2f)
                    binding.navIndicator.animate()
                        .x(targetX)
                        .setDuration(300)
                        .start()
                }
            }
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            if (isLoading) showSearchLoadingOverlay() else hideLoadingOverlay()
        }

        viewModel.medicationList.observe(this) { list ->
            pagerAdapter.updateMeds(list)
        }
        
        viewModel.onlineCatalog.observe(this) { list ->
            pagerAdapter.updateOnlineCatalog(list)
        }

        viewModel.didYouMeanSuggestion.observe(this) { suggestion ->
            pagerAdapter.updateSuggestion(suggestion)
        }

        viewModel.searchHistory.observe(this) { history ->
            pagerAdapter.updateHistory(history)
        }

        viewModel.historyRecords.observe(this) { records ->
            pagerAdapter.updateHistoryRecords(records)
        }

        viewModel.calendarRecords.observe(this) { records ->
            pagerAdapter.updateCalendarRecords(records)
            val existingSheet = supportFragmentManager.findFragmentByTag("CalendarHistorySheet") as? CalendarHistorySheet
            existingSheet?.updateData(records, viewModel.medicationList.value ?: emptyList())
        }
    }

    private var backPressedTime: Long = 0

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. Check if scan/search loading overlay is active
                if (binding.loadingOverlay.visibility == View.VISIBLE) {
                    if (isScanningActive) {
                        // Cancel optical scan/processing tasks immediately and return directly to Home (tab 0)
                        viewModel.cancelScanOperation()
                        hideLoadingOverlay()
                        binding.mainViewPager.setCurrentItem(0, true)
                        Toast.makeText(this@MainActivity, "Scan operation canceled", Toast.LENGTH_SHORT).show()
                    } else {
                        // Cancel pending search operation immediately and remain on/return to Search page (tab 2, keeping search input query)
                        viewModel.cancelSearchOperation()
                        hideLoadingOverlay()
                        binding.mainViewPager.setCurrentItem(2, true)
                        Toast.makeText(this@MainActivity, "Search canceled", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                // 2. Back Navigation to Home from secondary tabs (History, Search, Settings)
                if (binding.mainViewPager.currentItem != 0) {
                    binding.mainViewPager.setCurrentItem(0, true)
                } else {
                    // 3. Double-Back to Exit on Home Screen
                    if (backPressedTime + 2000 > System.currentTimeMillis()) {
                        finish()
                    } else {
                        Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                        backPressedTime = System.currentTimeMillis()
                    }
                }
            }
        })
    }

    private fun setupClickListeners() {
        binding.btnCameraScan.addTactileTouchEffect(0.91f)
        binding.btnCameraScan.setOnClickListener {
            showScanSourceDialog()
        }

        val navContainers = listOf(
            binding.navHomeContainer,
            binding.navHistoryContainer,
            binding.navOnlineContainer,
            binding.navSettingsContainer
        )

        navContainers.forEachIndexed { index, container ->
            container.addTactileTouchEffect(0.93f)
            container.setOnClickListener {
                binding.mainViewPager.currentItem = index
            }
        }
    }

    private fun showScanSourceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_scan_source, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.setCanceledOnTouchOutside(true)

        val cardCamera = dialogView.findViewById<View>(R.id.cardOptionCamera)
        val cardGallery = dialogView.findViewById<View>(R.id.cardOptionGallery)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseScanSource)

        cardCamera?.addTactileTouchEffect(0.96f)
        cardCamera?.setOnClickListener {
            dialog.dismiss()
            checkCameraPermissionAndLaunch()
        }

        cardGallery?.addTactileTouchEffect(0.96f)
        cardGallery?.setOnClickListener {
            dialog.dismiss()
            openGalleryIntent()
        }

        btnClose?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
        dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.65f)
        dialog.show()

        val dialogWidth = (340 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(dialogWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }

    private fun checkCameraPermissionAndLaunch() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> {
                openCameraIntent()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> {
                showSettingsRedirectDialog()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun showSettingsRedirectDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission Denied")
            .setMessage("Camera access was previously denied. Please enable camera permission manually in settings or select the Gallery choice option to complete your scan.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply { applyLiquidGlassModalEffect() }
            .show()
    }

    fun triggerRescan() {
        showScanSourceDialog()
    }

    private fun openCameraIntent() {
        try {
            val uri = createImageUri()
            photoUri = uri
            if (uri != null) {
                takePictureLauncher.launch(uri)
            } else {
                Toast.makeText(this, "Failed to create image file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Camera Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openGalleryIntent() {
        pickImageLauncher.launch("image/*")
    }

    private fun createImageUri(): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
        val image = File.createTempFile(imageFileName, ".jpg", storageDir)
        
        return FileProvider.getUriForFile(
            this,
            "$packageName.provider",
            image
        )
    }

    private fun optimizeForOCR(source: Bitmap): Bitmap {
        // High-fidelity pre-processing for Gemini 1.5 Flash
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE
        val targetConfig = if (isHardware || source.config == null) {
            Bitmap.Config.ARGB_8888
        } else {
            source.config ?: Bitmap.Config.ARGB_8888
        }
        val output = createBitmap(source.width, source.height, targetConfig)
        val canvas = Canvas(output)
        
        // Grayscale + High Contrast + Brightness
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f) // Remove color noise
        }
        
        // Increase contrast significantly and adjust brightness to isolate text
        val contrast = 1.5f
        val brightness = -20f
        val scale = contrast + 1f
        val translate = (-.5f * scale + .5f) * 255f + brightness
        
        val contrastMatrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        
        colorMatrix.postConcat(contrastMatrix)
        
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
            isAntiAlias = true
            isFilterBitmap = true
        }
        
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun prepareBitmapForVision(source: Bitmap): Bitmap {
        val maxDim = 2048
        if (source.width <= maxDim && source.height <= maxDim) {
            return source
        }
        val ratio = source.width.toFloat() / source.height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (source.width > source.height) {
            targetWidth = maxDim
            targetHeight = (maxDim / ratio).toInt()
        } else {
            targetHeight = maxDim
            targetWidth = (maxDim * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun processGalleryImage(uri: Uri) {
        try {
            showScanLoadingOverlay()
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap == null) {
                hideLoadingOverlay()
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                return
            }

            val visionBitmap = prepareBitmapForVision(bitmap)
            val optimizedBitmap = optimizeForOCR(bitmap)
            val wrapper = ocrWrapper ?: MlKitOcrWrapper().also { ocrWrapper = it }

            val notifyPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
            val scannerMode = notifyPrefs.getString("preferred_scanner", "both") ?: "both"

            lifecycleScope.launch {
                try {
                    when (scannerMode) {
                        "mlkit" -> {
                            val mlKitText = wrapper.recognizeText(optimizedBitmap)
                            viewModel.processStandaloneMlKitOcr(mlKitText) { singleMed ->
                                hideLoadingOverlay()
                                if (singleMed != null) {
                                    showEditMedicationSheet(singleMed)
                                } else {
                                    Toast.makeText(this@MainActivity, "No medication details found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        "gemini" -> {
                            val mlKitText = wrapper.recognizeText(optimizedBitmap)
                            viewModel.processOcrWithGeminiValidation(mlKitText, visionBitmap) { isMultiple, singleMed, regions ->
                                hideLoadingOverlay()
                                if (regions.isNotEmpty()) {
                                    showMultiPrescriptionSelectionDialog(visionBitmap, regions)
                                } else if (singleMed != null) {
                                    showEditMedicationSheet(singleMed)
                                } else {
                                    Toast.makeText(this@MainActivity, "No medication details found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        else -> { // "both"
                            val mlKitText = wrapper.recognizeText(optimizedBitmap)
                            viewModel.processOcrWithGeminiValidation(mlKitText, visionBitmap) { isMultiple, singleMed, regions ->
                                hideLoadingOverlay()
                                if (regions.isNotEmpty()) {
                                    showMultiPrescriptionSelectionDialog(visionBitmap, regions)
                                } else if (singleMed != null) {
                                    showEditMedicationSheet(singleMed)
                                } else {
                                    Toast.makeText(this@MainActivity, "No medication details found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    hideLoadingOverlay()
                    Log.e("OcrPipeline", "Scan error: ${e.message}", e)
                    Toast.makeText(this@MainActivity, "Failed to scan prescription", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            hideLoadingOverlay()
            Toast.makeText(this, "Failed to process image: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMultiPrescriptionSelectionDialog(
        bitmap: Bitmap,
        regions: List<PrescriptionRegionItem>
    ) {
        val count = regions.size
        val notifText = if (count == 1) "🎯 1 Medication Detected! Tap circle to select" else "🎯 $count Medications Detected! Tap a green circle to select"
        Toast.makeText(this, notifText, Toast.LENGTH_SHORT).show()

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_multi_prescription_selector, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val tvHeader = dialogView.findViewById<TextView>(R.id.tvMultiScanHeader)
        val tvBadge = dialogView.findViewById<TextView>(R.id.tvCountBadge)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseMultiScan)
        val overlayView = dialogView.findViewById<PrescriptionCircleOverlayView>(R.id.overlayCircleView)

        tvHeader?.text = if (count == 1) "1 Medication Detected" else "$count Medications Detected"
        tvBadge?.text = "$count DETECTED"

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        overlayView.setImageAndRegions(bitmap, regions) { selectedRegion ->
            dialog.dismiss()
            val normalizedFreq = viewModel.expandFrequencyShorthand(selectedRegion.frequency)
            val defaultTime = viewModel.deduceDefaultTimeFromFrequency(normalizedFreq)
            val selectedMed = Medication(
                name = selectedRegion.name,
                dosage = if (selectedRegion.dosage.isBlank()) "As prescribed" else selectedRegion.dosage,
                quantity = if (selectedRegion.quantity.isBlank()) "1" else selectedRegion.quantity,
                frequency = if (normalizedFreq.isBlank()) "1 time a day • Every day" else normalizedFreq,
                time = defaultTime,
                isVerified = true,
                verificationMessage = "Selected from multi-prescription scan"
            )
            showEditMedicationSheet(selectedMed)
        }

        dialog.window?.setWindowAnimations(R.style.BottomSheetSlideAnimation)
        dialog.window?.applyLiquidGlassModalEffect(dimAmount = 0.70f)
        dialog.show()

        val displayMetrics = resources.displayMetrics
        val dialogWidth = (360 * displayMetrics.density).toInt()
        val dialogHeight = (520 * displayMetrics.density).toInt()
        dialog.window?.setLayout(dialogWidth, dialogHeight)
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }

    private fun showEditMedicationSheet(medication: Medication, isEdit: Boolean = false) {
        val editSheet = EditMedicationSheet(
            initialName = medication.name,
            initialDosage = medication.dosage,
            initialQty = medication.quantity,
            initialFreq = medication.frequency,
            initialTime = medication.time,
            medication = medication,
            onSave = { name, dosage, qty, freq, time, ringtoneUri ->
                val updatedMed = medication.copy(
                    name = name,
                    dosage = dosage,
                    quantity = qty,
                    frequency = freq,
                    time = time,
                    ringtoneUri = ringtoneUri ?: medication.ringtoneUri
                )
                if (isEdit) {
                    cancelAlarm(medication)
                    viewModel.updateMedication(updatedMed)
                } else {
                    viewModel.addMedication(updatedMed)
                }
                scheduleAlarm(updatedMed)
                Toast.makeText(this, "Saved: $name", Toast.LENGTH_SHORT).show()
            },
            onRescan = { triggerRescan() }
        )
        editSheet.show(supportFragmentManager, "EditMedicationSheet")
    }

    private fun scheduleAlarm(medication: Medication) {
        ReliabilityUtils.scheduleAlarm(this, medication)
    }

    private fun cancelAlarm(medication: Medication) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            medication.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    private fun runTextRecognizer(bitmap: Bitmap) {
        val wrapper = ocrWrapper ?: MlKitOcrWrapper().also { ocrWrapper = it }
        
        lifecycleScope.launch {
            val text = wrapper.recognizeText(bitmap)
            if (text.isNotEmpty()) {
                parsePrescriptionText(text)
            } else {
                Toast.makeText(this@MainActivity, "No text detected in image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parsePrescriptionText(rawText: String) {
        val cleanText = rawText.replace("\\s+".toRegex(), " ")
        val allLines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val rxRawIndex = allLines.indexOfFirst {
            it.contains("Rx", ignoreCase = true) || it.contains("R×", ignoreCase = true)
        }

        val linesToProcess = if (rxRawIndex != -1) allLines.drop(rxRawIndex) else allLines

        val skipKeywords = listOf(
            "clinic", "hours", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "address", "patient", "age", "sex", "date", "general", "practice", "center",
            "tel", "cp#", "ptr", "lic", "md", "dr.", "polo", "aklan", "hospital",
            "jeremiah", "saith", "lord", "heal", "restore", "wounds",
            "prescription", "medical", "certificate", "name:", "signature", "phone", "email",
            "refill", "no.", "id:", "weight", "height", "allergies", "history"
        )

        val filteredLines = linesToProcess.filter { line ->
            skipKeywords.none { keyword -> line.contains(keyword, ignoreCase = true) }
        }

        var extractedName = "Unknown"
        val rxLineIndex = filteredLines.indexOfFirst {
            it.contains("Rx", ignoreCase = true) || it.contains("R×", ignoreCase = true)
        }

        if (rxLineIndex != -1) {
            val rxLine = filteredLines[rxLineIndex]
            val afterRx = rxLine.replace("(?i)Rx|R×".toRegex(), "").trim()
            if (afterRx.isNotEmpty() && afterRx.length > 2) {
                extractedName = afterRx.replace(Regex("[^a-zA-Z0-9\\s\\-]"), "").trim()
            } else if (rxLineIndex + 1 < filteredLines.size) {
                extractedName = filteredLines[rxLineIndex + 1].replace(Regex("[^a-zA-Z0-9\\s\\-]"), "").trim()
            }
        } else {
            extractedName = filteredLines.firstOrNull { 
                it.length > 3 && it.any { char -> char.isLetter() } && 
                !it.contains("Date", ignoreCase = true) 
            }?.trim() ?: "Unknown"
        }
        
        if (extractedName.length > 40) extractedName = extractedName.take(40) + "..."

        val dosagePattern = Pattern.compile("(\\d+\\s?(mg|ml|mcg|g|iu|tab|cap|ug|units|units/ml))", Pattern.CASE_INSENSITIVE)
        val dosageMatcher = dosagePattern.matcher(cleanText)
        val extractedDosage = if (dosageMatcher.find()) dosageMatcher.group(1) else ""

        val qtyPattern = Pattern.compile("(#|qty|count|amount|total)[:\\s]*(\\d+)", Pattern.CASE_INSENSITIVE)
        val qtyMatcher = qtyPattern.matcher(cleanText)
        val extractedQty = if (qtyMatcher.find()) qtyMatcher.group(2) ?: "" else ""

        val freqPatterns = listOf(
            "\\d\\s?x\\s?/?\\s?day",
            "\\b(twice|once|thrice|four times)\\s?daily\\b",
            "\\b[btd]id\\b",
            "\\bqid\\b",
            "every\\s?\\d+\\s?hours",
            "once\\s?a\\s?day",
            "at\\s?bedtime",
            "before\\s?meals",
            "after\\s?meals"
        )
        val extractedFreq = freqPatterns.firstNotNullOfOrNull { patternStr ->
            val matcher = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE).matcher(cleanText)
            if (matcher.find()) matcher.group(0) else null
        } ?: ""

        Toast.makeText(this, "Basic Scan Complete. Please verify details.", Toast.LENGTH_SHORT).show()
        showEditMedicationSheet(Medication(name = extractedName, dosage = extractedDosage, quantity = extractedQty, frequency = extractedFreq, time = "12:00 PM"))
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrWrapper?.release()
    }
}
