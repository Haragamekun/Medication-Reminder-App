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
import android.widget.PopupMenu
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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
import com.example.ocrmedicationapp.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import coil.load
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
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
        val prefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        val savedMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(savedMode)

        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState != null) {
            photoUri = BundleCompat.getParcelable(savedInstanceState, "photoUri", Uri::class.java)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setupViewPager()
        setupClickListeners()
        observeViewModel()
        observeMedIntelligence()
        
        viewModel.fetchLiveCatalog()
        checkBatteryOptimizations()
        ReliabilityHeartbeatWorker.enqueue(this)
    }

    companion object {
        private var hasAskedBatterySession = false
    }

    private fun checkBatteryOptimizations() {
        if (hasAskedBatterySession) return

        val isBatteryRestricted = ReliabilityUtils.isBatteryOptimized(this)
        val isExactAlarmRequired = !ReliabilityUtils.canScheduleExactAlarms(this)

        if (isBatteryRestricted || isExactAlarmRequired) {
            AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
                .setTitle(getString(R.string.title_reliability_guide))
                .setMessage(getString(R.string.msg_battery_guide_prompt))
                .setPositiveButton(getString(R.string.btn_view_guide)) { _, _ ->
                    onReliabilityGuide()
                    hasAskedBatterySession = true
                }
                .setNegativeButton(getString(R.string.btn_cancel)) { _, _ ->
                    hasAskedBatterySession = true
                }
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("photoUri", photoUri)
    }

    private fun observeMedIntelligence() {
        viewModel.selectedMedDetails.observe(this) { info ->
            binding.loadingOverlay.visibility = View.GONE
            if (info != null) {
                binding.mainViewPager.currentItem = 2
                MedicationDetailDialog.newInstance(info)
                    .show(supportFragmentManager, "MedicationDetail")
                viewModel.clearSelectedMedDetails()
            } else {
                Toast.makeText(this, getString(R.string.toast_med_not_found), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupViewPager() {
        pagerAdapter = MainPagerAdapter(this)
        binding.mainViewPager.adapter = pagerAdapter
        binding.mainViewPager.isUserInputEnabled = true
        binding.mainViewPager.offscreenPageLimit = 3

        binding.mainViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateBottomNavUI(position)
            }
        })
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
        viewModel.fetchMedicationInfo(query)
    }

    override fun onCatalogFilter(query: String) {
        viewModel.filterCatalog(query)
    }

    override fun onCatalogItemClick(med: MedInfo) {
        if (med.description.isEmpty()) {
            binding.loadingOverlay.visibility = View.VISIBLE
            viewModel.fetchMedicationInfo(med.name)
        } else {
            MedicationDetailDialog.newInstance(med)
                .show(supportFragmentManager, "MedicationDetail")
        }
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

    override fun onToggleTheme(view: View) {
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val newMode = if (isDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        
        binding.themeRevealView.visibility = View.VISIBLE
        val targetColor = if (isDark) "#F1F5F9".toColorInt() else "#050C18".toColorInt()
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
        val notifyPrefs = getSharedPreferences("notification_prefs", MODE_PRIVATE)
        val options = arrayOf("System Sound", "Vibrations", "Lockscreen Banners")
        val checkedItems = booleanArrayOf(
            notifyPrefs.getBoolean("sound_enabled", true),
            notifyPrefs.getBoolean("vibrate_enabled", true),
            notifyPrefs.getBoolean("banners_enabled", true)
        )
        AlertDialog.Builder(this, R.style.Theme_OCRMedicationApp)
            .setTitle(getString(R.string.label_notifications))
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                val key = when (which) {
                    0 -> "sound_enabled"
                    1 -> "vibrate_enabled"
                    else -> "banners_enabled"
                }
                notifyPrefs.edit { putBoolean(key, isChecked) }
            }
            .setPositiveButton("Done", null)
            .show()
    }

    override fun onReliabilityGuide() {
        startActivity(Intent(this, ReliabilityGuideActivity::class.java))
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

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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
        dialogView.findViewById<TextView>(R.id.tvAboutVersion).text = getString(R.string.version_format, versionName)

        dialogView.findViewById<View>(R.id.btnAboutClose).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.tvAboutLegal).text = getString(R.string.about_legal)

        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
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

            if (isSelected) {
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
    }

    private fun setupClickListeners() {
        binding.fabScanContainer.setOnClickListener {
            showScanOptionsDialog()
        }

        binding.navHomeContainer.setOnClickListener { binding.mainViewPager.currentItem = 0 }
        binding.navHistoryContainer.setOnClickListener { binding.mainViewPager.currentItem = 1 }
        binding.navOnlineContainer.setOnClickListener { binding.mainViewPager.currentItem = 2 }
        binding.navSettingsContainer.setOnClickListener { binding.mainViewPager.currentItem = 3 }

        binding.btnCancelScan.setOnClickListener { binding.cameraGuideOverlay.visibility = View.GONE }
        binding.btnProceedScan.setOnClickListener {
            binding.cameraGuideOverlay.visibility = View.GONE
            showScanSourceDialog()
        }
    }

    private fun showScanOptionsDialog() {
        binding.cameraGuideOverlay.visibility = View.VISIBLE
    }

    private fun showScanSourceDialog() {
        val options = arrayOf("Take Photo with Camera", "Choose from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Scan Medication Label")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkCameraPermissionAndLaunch()
                    1 -> openGalleryIntent()
                }
            }
            .show()
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
            .show()
    }

    fun triggerRescan() {
        showScanSourceDialog()
    }

    private fun openCameraIntent() {
        try {
            photoUri = createImageUri()
            if (photoUri != null) {
                takePictureLauncher.launch(photoUri!!)
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
        // High-fidelity pre-processing for Gemini 2.0 Flash
        val output = createBitmap(source.width, source.height, source.config!!)
        val canvas = Canvas(output)
        
        // 1. Grayscale + High Contrast + Brightness Normalization
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

    private fun processGalleryImage(uri: Uri) {
        try {
            binding.loadingOverlay.visibility = View.VISIBLE
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap == null) {
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                return
            }
                 // Priority 1 High Accuracy AI Scan(Optimizing++)
            val optimizedBitmap = optimizeForOCR(bitmap)
            viewModel.extractWithGemini(optimizedBitmap) { medication ->
                binding.loadingOverlay.visibility = View.GONE
                if (medication != null && medication.name != "Unknown") {
                    showEditMedicationSheet(medication)
                } else {
                       // Balik sa ML kit if di kaya ng AI
                    val legacyEnhanced = optimizeForOCR(bitmap)
                    val image = InputImage.fromBitmap(legacyEnhanced, 0)
                    runTextRecognizer(image)
                }
            }
        } catch (e: Exception) {
            binding.loadingOverlay.visibility = View.GONE
            Toast.makeText(this, "Failed to process image: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
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

    private fun runTextRecognizer(image: InputImage) {
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                parsePrescriptionText(visionText.text)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "OCR processing failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
}
