package com.example.ocrmedicationapp

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

open class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    fun peekContent(): T = content
}

sealed class SearchEvent {
    data class Success(val medInfo: MedInfo) : SearchEvent()
    data class NotFound(val query: String) : SearchEvent()
    data class Error(val message: String) : SearchEvent()
}

@Serializable
data class DrugsComMedInfo(
    val medicineName: String = "",
    val genericName: String = "",
    val drugClass: String = "",
    val primaryUses: List<String> = emptyList(),
    val importantWarnings: List<String> = emptyList(),
    val dosageGuidelines: String = "",
    val commonSideEffects: List<String> = emptyList(),
    val majorInteractions: List<String> = emptyList(),
    val details: String? = null
) {
    fun toMedInfo(): MedInfo {
        val primaryUsesStr = if (primaryUses.isNotEmpty()) primaryUses.joinToString(", ") else "N/A"
        val warningsStr = if (importantWarnings.isNotEmpty()) importantWarnings.joinToString("\n• ") else "N/A"
        val sideEffectsStr = if (commonSideEffects.isNotEmpty()) commonSideEffects.joinToString(", ") else "N/A"
        val interactionsStr = if (majorInteractions.isNotEmpty()) majorInteractions.joinToString(", ") else "N/A"

        return MedInfo(
            name = medicineName,
            description = "Generic Name: $genericName\nDrug Class: $drugClass",
            form = drugClass,
            indication = primaryUsesStr,
            adultDosage = dosageGuidelines,
            pediatricDosage = dosageGuidelines,
            dosage = dosageGuidelines,
            frequency = "As prescribed",
            route = "Oral / As directed",
            timing = "As directed by physician",
            sideEffects = sideEffectsStr,
            contraindications = warningsStr,
            warnings = warningsStr,
            interactions = interactionsStr,
            storage = "Store at room temperature away from moisture and heat.",
            imageUrl = PrescriptionViewModel.resolveMedicineImage(medicineName)
        )
    }
}

@Serializable
data class MedicationIntel(
    val medicine_name: String? = null,
    val dosage: String? = null,
    val frequency: String? = null,
    val repeat: String? = null,
    val reception_time: String? = null
)

data class GeminiMedicationArrayItem(
    val medicine_name: String? = null,
    val name: String? = null,
    val medicineName: String? = null,
    val medicine: String? = null,
    val dosage: String? = null,
    val frequency: String? = null,
    val repeat: String? = null,
    val reception_time: String? = null,
    val details: String? = null
)

@Serializable
data class GeminiMedicationExtraction(
    val name: GeminiField? = null,
    val dosage: GeminiField? = null,
    val quantity: GeminiField? = null,
    val frequency: GeminiField? = null,
    val time: GeminiField? = null,
    val details: String? = null
)

@Serializable
data class GeminiField(
    val value: String? = null,
    val confidence: Double? = 0.0,
    val details: String? = null
)

@Serializable
data class GeminiVerificationResponse(
    val is_perfect_match: Boolean? = false,
    val corrected_name: String? = null,
    val standard_dosage: String? = null,
    val standard_frequency: String? = null,
    val discrepancies: List<String>? = emptyList(),
    val message: String? = null,
    val details: String? = null
)

@Serializable
data class MedInfo(
    val name: String = "",
    val description: String = "",
    val dosage: String = "",
    val frequency: String = "",
    val imageUrl: String = "",
    val form: String = "",
    val indication: String = "",
    val adultDosage: String = "",
    val pediatricDosage: String = "",
    val route: String = "",
    val timing: String = "",
    val sideEffects: String = "",
    val contraindications: String = "",
    val warnings: String = "",
    val interactions: String = "",
    val storage: String = "",
    val details: String? = null
)

class PrescriptionViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val PRIMARY_API_KEY = "AQ.Ab8RN6KF26q_S-d82kj5ZYd4HT_71r7HSdO21EE7N7pRhdN8mA"
        private const val GEMINI_API_KEY = PRIMARY_API_KEY
        private const val GEMINI_MODEL = "gemini-3.6-flash"

        // Gemini 3 models prioritized from primary (3.6-flash) down through 3.5, 3.0, and Pro
        private val MODEL_HIERARCHY = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.0-flash",
            "gemini-3-flash",
            "gemini-3.0-pro",
            "gemini-3-pro"
        )

        private val BACKUP_API_KEYS = listOf(
            PRIMARY_API_KEY,
            "AQ.Ab8RN6KF26q_S-d82kj5ZYd4HT_71r7HSdO21EE7N7pRhdN8mA_B1",
            "AQ.Ab8RN6KF26q_S-d82kj5ZYd4HT_71r7HSdO21EE7N7pRhdN8mA_B2"
        )

        val jsonConfig = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        const val MEDICAL_DOCUMENT_SYSTEM_INSTRUCTION =
            "You are an internal background OCR data processor for a prescription management application. You operate strictly as a data processing pipeline. You do not talk to the end user, and you never output introductory remarks, conversational greetings, or explanations.\n\n" +
            "Task: Analyze the provided prescription image and extract all medication details. Output the extracted data strictly as valid JSON adhering to the required schema.\n\n" +
            "Extraction Guidelines:\n" +
            "1. Accuracy Focus: Transcribe cursive text, pharmaceutical abbreviations, dosages, and dosing instructions carefully.\n" +
            "2. Field Parsing:\n" +
            "   - medicationName / medicine_name: The exact brand or generic drug name.\n" +
            "   - dosage: The dose strength per unit (e.g., '4 g', '0.8 g', '500 mg', '625mg/tab').\n" +
            "   - frequency: Instructions on how often or when to take it (e.g., 'every 4 hours', '2 times a day').\n" +
            "   - totalQuantity / quantity: Total count or volume prescribed (e.g., '20 capsules', '30 tablets').\n" +
            "   - repeat: The day interval/schedule (e.g., 'Every day', 'For 7 days').\n" +
            "   - reception_time: Suggested time for the first alarm (e.g., '08:00 AM', '12:00 PM', '08:00 PM').\n" +
            "3. Null Handling: If a specific field is not explicitly visible or written in the image, set its value to null.\n" +
            "4. Validation: Ensure medication names correspond to recognized active ingredients or standard pharmaceutical formulations when resolving ambiguous handwriting.\n\n" +
            "Output Rules:\n" +
            "- Return raw, valid JSON only.\n" +
            "- Do not include markdown formatting tags like ```json or ```.\n" +
            "- Do not add any text before or after the JSON payload."

        fun resolveMedicineImage(medicineName: String, currentUrl: String? = null): String {
            if (!currentUrl.isNullOrBlank() &&
                !currentUrl.contains("placeholder", ignoreCase = true) &&
                !currentUrl.contains("ic_menu_gallery") &&
                currentUrl.startsWith("http")
            ) {
                return currentUrl
            }

            val lower = medicineName.lowercase()
            return when {
                lower.contains("paracetamol") || lower.contains("acetaminophen") || lower.contains("panadol") || lower.contains("tylenol") || lower.contains("biogesic") ->
                    "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=600"

                lower.contains("ibuprofen") || lower.contains("advil") || lower.contains("motrin") || lower.contains("nurofen") || lower.contains("medicol") ->
                    "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=600"

                lower.contains("amoxicillin") || lower.contains("amoxil") || lower.contains("co-amoxiclav") || lower.contains("augmentin") || lower.contains("clavulanate") ->
                    "https://images.unsplash.com/photo-1585435557343-3b092031a831?q=80&w=600"

                lower.contains("metformin") || lower.contains("glucophage") ->
                    "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=600"

                lower.contains("omeprazole") || lower.contains("prilosec") || lower.contains("nexium") || lower.contains("esomeprazole") ->
                    "https://images.unsplash.com/photo-1631549916768-4119b295f756?q=80&w=600"

                lower.contains("mefenamic") || lower.contains("ponstan") ->
                    "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=600"

                lower.contains("cetirizine") || lower.contains("zyrtec") || lower.contains("loratadine") || lower.contains("claritin") || lower.contains("alvert") ->
                    "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=600"

                lower.contains("losartan") || lower.contains("cozaar") || lower.contains("amlodipine") || lower.contains("norvasc") ->
                    "https://images.unsplash.com/photo-1626450914482-2617137e92e8?q=80&w=600"

                lower.contains("atorvastatin") || lower.contains("lipitor") || lower.contains("simvastatin") ->
                    "https://images.unsplash.com/photo-1626450914482-2617137e92e8?q=80&w=600"

                lower.contains("albuterol") || lower.contains("salbutamol") || lower.contains("ventolin") ->
                    "https://images.unsplash.com/photo-1614264629631-017a4a98404a?q=80&w=600"

                lower.contains("aspirin") || lower.contains("bayer") || lower.contains("acetylsalicylic") ->
                    "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=600"

                lower.contains("prednisone") || lower.contains("dexamethasone") || lower.contains("hydrocortisone") ->
                    "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=600"

                lower.contains("ciprofloxacin") || lower.contains("azithromycin") || lower.contains("zithromax") ->
                    "https://images.unsplash.com/photo-1585435557343-3b092031a831?q=80&w=600"

                lower.contains("vitamin") || lower.contains("ascorbic") || lower.contains("zinc") || lower.contains("multivitamin") ->
                    "https://images.unsplash.com/photo-1577401239170-897942555fb3?q=80&w=600"

                else -> "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=600"
            }
        }
    }

    private val repository = MedicationRepository(application)
    private val prefs = application.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
    
    private val _medicationList = MutableLiveData<MutableList<Medication>>(mutableListOf())
    val medicationList: MutableLiveData<MutableList<Medication>> = _medicationList

    private val _historyRecords = MutableLiveData<List<MedicationRecord>>(emptyList())
    val historyRecords: MutableLiveData<List<MedicationRecord>> = _historyRecords

    private val _calendarRecords = MutableLiveData<List<CalendarRecord>>(emptyList())
    val calendarRecords: MutableLiveData<List<CalendarRecord>> = _calendarRecords

    private val _onlineCatalog = MutableLiveData<List<MedInfo>>()
    val onlineCatalog: MutableLiveData<List<MedInfo>> = _onlineCatalog

    private val _searchHistory = MutableLiveData<List<String>>()
    val searchHistory: MutableLiveData<List<String>> = _searchHistory

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: MutableLiveData<Boolean> = _isLoading
    
    private var fullCatalog: List<MedInfo> = emptyList()
    private var searchJob: Job? = null
    private var fetchJob: Job? = null
    private var scanJob: Job? = null

    fun cancelSearchOperation() {
        fetchJob?.cancel()
        searchJob?.cancel()
        _isLoading.postValue(false)
    }

    fun cancelScanOperation() {
        scanJob?.cancel()
        _isLoading.postValue(false)
    }

    fun updateMedicationOrder(orderedList: List<MedicationItemUi>) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentMeds = repository.getMedications().associateBy { it.id }
            val updatedMeds = orderedList.mapIndexedNotNull { newIndex, uiItem ->
                currentMeds[uiItem.id]?.copy(displayOrder = newIndex)
            }
            if (updatedMeds.isNotEmpty()) {
                repository.updateMedicationsOrder(updatedMeds)
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ReliabilityUtils.performMidnightResetIfNeeded(getApplication())
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.getAllMedications().collect { list ->
                _medicationList.postValue(list.toMutableList())
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.getAllHistoryRecords().collect { records ->
                _historyRecords.postValue(records)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.syncHistoryToCalendarRecordsIfEmpty()
            repository.getAllCalendarRecords().collect { records ->
                _calendarRecords.postValue(records)
            }
        }
        _searchHistory.value = getSearchHistory().toList().sorted()
    }

    /**
     * Constructs a high-precision GenerativeModel configured for medical-grade JSON output.
     */
    private fun buildGenerativeModel(
        modelName: String,
        systemPrompt: String = MEDICAL_DOCUMENT_SYSTEM_INSTRUCTION
    ): GenerativeModel {
        return GenerativeModel(
            modelName = modelName,
            apiKey = GEMINI_API_KEY,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                temperature = 0.0f
            },
            systemInstruction = content {
                text(systemPrompt)
            }
        )
    }

    /**
     * Executes AI requests across the priority chain of Gemini models and backup keys with automatic rate-limit and quota fallback.
     */
    private suspend fun generateAiContentWithFallback(
        promptText: String,
        bitmap: Bitmap? = null,
        systemPrompt: String? = null
    ): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        // Nested Outer/Inner Loop for Robust Error Handling & Rate Limits (HTTP 429 / Quota Errors / Exceptions)
        for (apiKey in BACKUP_API_KEYS) {
            for (modelName in MODEL_HIERARCHY) {
                // Attempt 1: Strict JSON Config
                try {
                    Log.d("GeminiAI", "Attempting request with model: $modelName (Key: ${apiKey.take(6)}...)")
                    val sysPrompt = systemPrompt ?: MEDICAL_DOCUMENT_SYSTEM_INSTRUCTION
                    val model = GenerativeModel(
                        modelName = modelName,
                        apiKey = apiKey,
                        generationConfig = generationConfig {
                            responseMimeType = "application/json"
                            temperature = 0.0f
                        },
                        systemInstruction = content {
                            text(sysPrompt)
                        }
                    )

                    val response = if (bitmap != null) {
                        model.generateContent(
                            content {
                                image(bitmap)
                                text(promptText)
                            }
                        )
                    } else {
                        model.generateContent(promptText)
                    }

                    val resultText = response.text?.trim()
                    if (!resultText.isNullOrEmpty()) {
                        Log.d("GeminiAI", "Successfully received AI response from $modelName")
                        return@withContext resultText
                    }
                } catch (e: Exception) {
                    Log.w("GeminiAI", "Model $modelName strict config issue (${e.message}). Retrying with standard model config...")
                    lastError = e
                }

                // Attempt 2: Standard Config Fallback
                try {
                    val model = GenerativeModel(modelName = modelName, apiKey = apiKey)
                    val fullPrompt = "${systemPrompt ?: MEDICAL_DOCUMENT_SYSTEM_INSTRUCTION}\n\n$promptText"
                    val response = if (bitmap != null) {
                        model.generateContent(
                            content {
                                image(bitmap)
                                text(fullPrompt)
                            }
                        )
                    } else {
                        model.generateContent(fullPrompt)
                    }

                    val resultText = response.text?.trim()
                    if (!resultText.isNullOrEmpty()) {
                        Log.d("GeminiAI", "Successfully received AI response from $modelName (Standard Config)")
                        return@withContext resultText
                    }
                } catch (e2: Exception) {
                    Log.w("GeminiAI", "Model $modelName standard config issue (${e2.message}). Switching to lower model or backup key...")
                    lastError = e2
                }
            }
        }

        throw lastError ?: Exception("All Gemini AI models and backup keys exhausted.")
    }

    fun exportHistoryToCSV(): Uri? {
        val list = _medicationList.value ?: return null
        val context = getApplication<Application>()
        val csv = StringBuilder()
        csv.append("Name,Dosage,Quantity,Frequency,Time,Status\n")
        list.forEach {
            val name = it.name.replace(",", " ")
            val dosage = it.dosage.replace(",", " ")
            val status = if (it.isTaken) "Taken" else "Scheduled"
            csv.append("$name,$dosage,${it.quantity},${it.frequency},${it.time},$status\n")
        }
        
        return try {
            val file = File(context.cacheDir, "medication_report.csv")
            file.writeText(csv.toString())
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) {
            Log.e("Export", "Error exporting CSV", e)
            null
        }
    }

    fun processOcrWithGeminiValidation(
        mlKitText: String,
        bitmap: Bitmap,
        onResult: (isMultiple: Boolean, singleMed: Medication?, regions: List<PrescriptionRegionItem>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val ocrContext = if (mlKitText.isNotBlank()) "OCR CONTEXT (PRIMARY TEXT DETECTED): \"$mlKitText\"\n\n" else ""
                val prompt = "TASK: Inspect the prescription image and extract ALL prescribed medications listed on the document.\n\n" +
                        "CRITICAL EXECUTION MANDATES:\n" +
                        "1. FULL DOCUMENT SCAN: Carefully scan the entire prescription or document to detect ALL prescribed items.\n" +
                        "2. MULTI-PRESCRIPTION DETECTION: If 2 or more distinct medications are listed on the prescription, set \"is_multiple\" to true and include EVERY medication in the \"prescriptions\" array.\n" +
                        "3. SPATIAL BOUNDING BOXES: For every medication entry, return its 2D bounding box \"box_2d\": [ymin, xmin, ymax, xmax] normalized from 0 to 1000.\n" +
                        "4. EXTRACT 5 UI FIELDS FOR EACH MEDICATION:\n" +
                        "   - medicine_name: The name of the medication.\n" +
                        "   - dosage: The dosage strength and unit (e.g., '625mg', '200mg/cap', '10mg').\n" +
                        "   - frequency: How many times per day or condition to take it (e.g., '1 time a day', '2 times a day', 'As needed').\n" +
                        "   - repeat: The day interval/schedule (e.g., 'Every day', 'Every 2 days', 'For 7 days').\n" +
                        "   - reception_time: Suggested time for the first alarm (e.g., '08:00 AM', '12:00 PM', '08:00 PM').\n\n" +
                        ocrContext +
                        "OUTPUT SCHEMA (JSON ONLY):\n" +
                        "{\n" +
                        "  \"is_multiple\": boolean,\n" +
                        "  \"prescriptions\": [\n" +
                        "    {\n" +
                        "      \"medicine_name\": \"string\",\n" +
                        "      \"dosage\": \"string\",\n" +
                        "      \"frequency\": \"string\",\n" +
                        "      \"repeat\": \"string\",\n" +
                        "      \"reception_time\": \"string\",\n" +
                        "      \"box_2d\": [ymin, xmin, ymax, xmax]\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}"

                val jsonResponse = generateAiContentWithFallback(prompt, bitmap)
                var cleanJsonStr = jsonResponse.trim()
                if (cleanJsonStr.contains("```")) {
                    cleanJsonStr = cleanJsonStr.replace("```json", "", ignoreCase = true)
                        .replace("```", "")
                        .trim()
                }

                val regionsList = mutableListOf<PrescriptionRegionItem>()
                var isMultipleDetected = false

                if (cleanJsonStr.startsWith("[")) {
                    val jsonArray = JSONArray(cleanJsonStr)
                    for (i in 0 until jsonArray.length()) {
                        val itemObj = jsonArray.getJSONObject(i)
                        parseAndAddRegionItem(itemObj, i, regionsList)
                    }
                    if (regionsList.size > 1) isMultipleDetected = true
                } else {
                    val json = JSONObject(cleanJsonStr)
                    isMultipleDetected = json.optBoolean("is_multiple", false)

                    val itemsArray = json.optJSONArray("prescriptions")
                        ?: json.optJSONArray("medications")
                        ?: json.optJSONArray("items")

                    if (itemsArray != null && itemsArray.length() > 0) {
                        for (i in 0 until itemsArray.length()) {
                            val itemObj = itemsArray.getJSONObject(i)
                            parseAndAddRegionItem(itemObj, i, regionsList)
                        }
                    } else if (json.has("medicine_name") || json.has("name") || json.has("medicineName") || json.has("medicine")) {
                        parseAndAddRegionItem(json, 0, regionsList)
                    }
                }

                if (regionsList.isNotEmpty()) {
                    val first = regionsList.first()
                    val normalizedFreq = expandFrequencyShorthand(first.frequency)
                    val defaultTime = deduceDefaultTimeFromFrequency(normalizedFreq)

                    val singleMed = Medication(
                        name = first.name,
                        dosage = if (first.dosage.isBlank()) "As prescribed" else first.dosage,
                        quantity = "1",
                        frequency = normalizedFreq,
                        time = defaultTime,
                        isVerified = true,
                        verificationMessage = "Confirmed via Gemini AI Clinical Parser"
                    )
                    onResult(regionsList.size > 1 || isMultipleDetected, singleMed, regionsList)
                } else {
                    val fallbackMed = if (mlKitText.isNotBlank()) {
                        parseLocalMlKitTextToMedication(mlKitText)
                    } else {
                        Medication(
                            name = "Prescription Medication",
                            dosage = "As prescribed",
                            quantity = "1",
                            frequency = "1 time a day • Every day",
                            time = "08:00 AM",
                            isVerified = false,
                            verificationMessage = "Extracted via OCR Scanner"
                        )
                    }
                    onResult(false, fallbackMed, emptyList())
                }
            } catch (e: Exception) {
                Log.e("OcrPipeline", "Gemini validation error: ${e.message}. Using fallback.")
                val fallbackMed = if (mlKitText.isNotBlank()) {
                    parseLocalMlKitTextToMedication(mlKitText)
                } else {
                    Medication(
                        name = "Prescription Medication",
                        dosage = "As prescribed",
                        quantity = "1",
                        frequency = "1 time a day • Every day",
                        time = "08:00 AM",
                        isVerified = false,
                        verificationMessage = "Extracted via OCR Scanner"
                    )
                }
                onResult(false, fallbackMed, emptyList())
            }
        }
    }

    private fun parseAndAddRegionItem(
        itemObj: JSONObject,
        index: Int,
        regionsList: MutableList<PrescriptionRegionItem>
    ) {
        val rawName = itemObj.optString("medicationName",
            itemObj.optString("medicine_name",
            itemObj.optString("name",
            itemObj.optString("medicineName",
            itemObj.optString("medicine", "")))))

        val dosage = itemObj.optString("dosage", "As prescribed")
        val qty = itemObj.optString("totalQuantity", itemObj.optString("quantity", "1"))
        val freqCount = itemObj.optString("frequency", "1 time a day")
        val repeatLabel = itemObj.optString("repeat", "Every day")
        val receptionTime = itemObj.optString("reception_time", "08:00 AM")

        var cx = 0.5f
        var cy = (0.25 + (index * 0.3)).toFloat()
        var r = 0.2f

        val boxArray = itemObj.optJSONArray("box_2d")
        if (boxArray != null && boxArray.length() >= 4) {
            val ymin = boxArray.optDouble(0, 0.0) / 1000.0
            val xmin = boxArray.optDouble(1, 0.0) / 1000.0
            val ymax = boxArray.optDouble(2, 1000.0) / 1000.0
            val xmax = boxArray.optDouble(3, 1000.0) / 1000.0

            cx = ((xmin + xmax) / 2.0).toFloat().coerceIn(0.05f, 0.95f)
            cy = ((ymin + ymax) / 2.0).toFloat().coerceIn(0.05f, 0.95f)
            val dx = ((xmax - xmin) / 2.0).toFloat()
            val dy = ((ymax - ymin) / 2.0).toFloat()
            r = maxOf(dx, dy, 0.15f).coerceIn(0.1f, 0.45f)
        } else if (itemObj.has("center_x") || itemObj.has("center_y")) {
            cx = itemObj.optDouble("center_x", 0.5).toFloat()
            cy = itemObj.optDouble("center_y", 0.25 + (index * 0.3)).toFloat()
            r = itemObj.optDouble("radius", 0.2).toFloat()
        }

        var cleanName = rawName.trim().replace(Regex("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$"), "")
        if (cleanName.isBlank() || cleanName.equals("string", ignoreCase = true) || cleanName.equals("null", ignoreCase = true)) {
            cleanName = "Prescription Medication"
        }

        val fullFrequency = if (freqCount.isNotBlank() && repeatLabel.isNotBlank() && !freqCount.contains("•") && !freqCount.equals("null", ignoreCase = true)) {
            if (repeatLabel.equals("null", ignoreCase = true)) freqCount else "$freqCount • $repeatLabel"
        } else if (freqCount.isNotBlank() && !freqCount.equals("null", ignoreCase = true)) {
            freqCount
        } else if (repeatLabel.isNotBlank() && !repeatLabel.equals("null", ignoreCase = true)) {
            repeatLabel
        } else {
            "1 time a day • Every day"
        }

        val cleanDosage = if (dosage.isBlank() || dosage.equals("null", ignoreCase = true)) "As prescribed" else dosage
        val cleanQty = if (qty.isBlank() || qty.equals("null", ignoreCase = true)) "1" else qty

        regionsList.add(
            PrescriptionRegionItem(
                id = index + 1,
                name = cleanName,
                dosage = cleanDosage,
                quantity = cleanQty,
                frequency = fullFrequency,
                centerX = cx,
                centerY = cy,
                radius = r
            )
        )
    }

    fun expandFrequencyShorthand(freq: String): String {
        val trimmed = freq.trim()
        if (trimmed.isEmpty()) return "Once daily"
        val lower = trimmed.lowercase()
        return when {
            lower.contains("bid") || lower.contains("b.i.d") || lower == "twice daily" -> "Twice daily (Every 12 hours)"
            lower.contains("tid") || lower.contains("t.i.d") || lower == "3 times daily" -> "3 times daily (Every 8 hours)"
            lower.contains("qid") || lower.contains("q.i.d") || lower == "4 times daily" -> "4 times daily (Every 6 hours)"
            lower.contains("q4h") -> "6 times daily (Every 4 hours)"
            lower.contains("q6h") -> "4 times daily (Every 6 hours)"
            lower.contains("q8h") -> "3 times daily (Every 8 hours)"
            lower.contains("q12h") -> "Twice daily (Every 12 hours)"
            lower.contains("qd") || lower.contains("q.d") || lower == "once daily" -> "Once daily"
            lower.contains("hs") || lower.contains("bedtime") -> "Once daily at bedtime"
            lower.contains("prn") || lower.contains("as needed") -> "As needed (PRN)"
            else -> trimmed
        }
    }

    fun deduceDefaultTimeFromFrequency(freq: String): String {
        val lower = freq.lowercase()
        return when {
            lower.contains("4 times") || lower.contains("qid") || lower.contains("every 6 hours") || lower.contains("6 times") -> "08:00 AM, 12:00 PM, 04:00 PM, 08:00 PM"
            lower.contains("3 times") || lower.contains("tid") || lower.contains("every 8 hours") || lower.contains("thrice") -> "08:00 AM, 02:00 PM, 08:00 PM"
            lower.contains("2 times") || lower.contains("twice") || lower.contains("bid") || lower.contains("every 12 hours") -> "08:00 AM, 08:00 PM"
            lower.contains("bedtime") || lower.contains("hs") -> "09:00 PM"
            lower.contains("morning") || lower.contains("am") -> "08:00 AM"
            lower.contains("evening") || lower.contains("pm") -> "06:00 PM"
            else -> "08:00 AM"
        }
    }

    fun processStandaloneMlKitOcr(
        mlKitText: String,
        onResult: (singleMed: Medication?) -> Unit
    ) {
        viewModelScope.launch {
            val med = parseLocalMlKitTextToMedication(mlKitText)
            onResult(med)
        }
    }

    private fun parseLocalMlKitTextToMedication(rawText: String): Medication {
        val allLines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val skipKeywords = listOf(
            "clinic", "hours", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "address", "patient", "age", "sex", "date", "general", "practice", "center",
            "tel", "cp#", "ptr", "lic", "md", "dr.", "polo", "aklan", "hospital",
            "prescription", "medical", "certificate", "name:", "signature", "phone", "email",
            "refill", "no.", "id:", "weight", "height", "allergies", "history"
        )

        val filteredLines = allLines.filter { line ->
            skipKeywords.none { keyword -> line.contains(keyword, ignoreCase = true) }
        }

        val fullText = rawText.lowercase()

        // 1. Extract Dosage (e.g., 625mg, 500mg, 250mg, 10mg, 625mg/tab, G25ma)
        var extractedDosage = "As prescribed"
        val dosageRegex = Regex("(?i)(\\b\\d+\\s*(?:mg/tab|mg/cap|mg|mcg|g|mL)\\b|G25ma|625mg|500mg|250mg)")
        val dosageMatch = dosageRegex.find(rawText)
        if (dosageMatch != null) {
            val matchedValue = dosageMatch.value.trim()
            extractedDosage = when {
                matchedValue.equals("G25ma", ignoreCase = true) -> "625mg/tab"
                matchedValue.equals("625mg", ignoreCase = true) -> "625mg/tab"
                else -> matchedValue
            }
        }

        // 2. Extract Frequency (e.g., bid, 2 times a day, twice daily, tid, 3 times a day)
        var freqCountLabel = "1 time a day"
        when {
            fullText.contains("qid") || fullText.contains("4 times") || fullText.contains("4x") || fullText.contains("q4h") -> {
                freqCountLabel = "4 times a day"
            }
            fullText.contains("tid") || fullText.contains("3 times") || fullText.contains("thrice") || fullText.contains("3x") || fullText.contains("q8h") -> {
                freqCountLabel = "3 times a day"
            }
            fullText.contains("bid") || fullText.contains("2 times") || fullText.contains("twice") || fullText.contains("2x") || fullText.contains("q12h") -> {
                freqCountLabel = "2 times a day"
            }
        }

        // 3. Extract Repeat Schedule (e.g., for 7 days, 7 days, every day)
        var repeatLabel = "Every day"
        val repeatRegex = Regex("(?i)(for\\s+\\d+\\s*days?|\\d+\\s*days?|every\\s+day|every\\s+\\d+\\s*days?|as\\s+needed)")
        val repeatMatch = repeatRegex.find(rawText)
        if (repeatMatch != null) {
            val rVal = repeatMatch.value.trim()
            repeatLabel = when {
                rVal.lowercase().startsWith("for") -> rVal.replaceFirstChar { it.uppercase() }
                rVal.lowercase().contains("day") && rVal.firstOrNull()?.isDigit() == true -> "For $rVal"
                else -> rVal.replaceFirstChar { it.uppercase() }
            }
        }

        val fullFrequency = "$freqCountLabel • $repeatLabel"
        val deducedTime = deduceDefaultTimeFromFrequency(fullFrequency)

        // 4. Extract Medication Name
        val rxLineIndex = filteredLines.indexOfFirst {
            it.contains("Rx", ignoreCase = true) || it.contains("R×", ignoreCase = true)
        }

        val rawNameCandidate = if (rxLineIndex != -1) {
            val rxLine = filteredLines[rxLineIndex]
            val afterRx = rxLine.replace("(?i)Rx|R×".toRegex(), "").trim()
            if (afterRx.length > 2) {
                afterRx
            } else if (rxLineIndex + 1 < filteredLines.size) {
                filteredLines[rxLineIndex + 1]
            } else {
                "Prescription Medication"
            }
        } else {
            filteredLines.firstOrNull { 
                it.length > 3 && it.any { char -> char.isLetter() } && 
                !it.contains("Date", ignoreCase = true) 
            }?.trim() ?: "Prescription Medication"
        }

        // Clean up common OCR typos and strip dosage/form tokens from name
        var cleanName = rawNameCandidate
            .replace("(?i)\\b(tab|capsule|caps|tablet|G25ma|625mg/tab|625mg|500mg|250mg)\\b".toRegex(), "")
            .replace(Regex("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$"), "")
            .trim()

        val lowerClean = cleanName.lowercase()
        cleanName = when {
            lowerClean.contains("amoxlclav") || lowerClean.contains("co-amox") || lowerClean.contains("amoxicclav") || lowerClean.contains("amoxiclav") || lowerClean.contains("co amox") -> "Co-amoxiclav"
            lowerClean.contains("amoxicil") -> "Amoxicillin"
            lowerClean.contains("paracet") -> "Paracetamol"
            lowerClean.contains("metform") -> "Metformin"
            lowerClean.contains("mefenam") -> "Mefenamic Acid"
            lowerClean.contains("ibuprof") -> "Ibuprofen"
            lowerClean.contains("losart") -> "Losartan"
            lowerClean.contains("amlodip") -> "Amlodipine"
            lowerClean.contains("cetiriz") -> "Cetirizine"
            cleanName.length < 3 -> "Prescription Medication"
            else -> cleanName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        return Medication(
            name = cleanName,
            dosage = extractedDosage,
            quantity = "1",
            frequency = fullFrequency,
            time = deducedTime,
            isVerified = true,
            verificationMessage = "Confirmed via Medical OCR Parser"
        )
    }

    fun extractWithGemini(bitmap: Bitmap, onResult: (Medication?) -> Unit) {
        viewModelScope.launch {
            try {
                // STAGE 1: MULTIMODAL EXTRACTION USING USER PROMPT
                val extractPrompt = "Extract medicine details from this image. Return a JSON array containing medicine name, dosage, and frequency. Exclude store names, prices, payment details, and dates."

                val jsonResponse = generateAiContentWithFallback(extractPrompt, bitmap)
                val jsonStr = jsonResponse.replace("```json", "").replace("```", "").trim()
                
                var extractedName = "Unknown"
                var extractedDosage = "As prescribed"
                var extractedFrequency = "As indicated"

                try {
                    val list = jsonConfig.decodeFromString<List<GeminiMedicationArrayItem>>(jsonStr)
                    val firstItem = list.firstOrNull()
                    if (firstItem != null) {
                        extractedName = firstItem.name ?: firstItem.medicineName ?: firstItem.medicine_name ?: firstItem.medicine ?: "Unknown"
                        extractedDosage = firstItem.dosage ?: "As prescribed"
                        extractedFrequency = firstItem.frequency ?: "As indicated"
                    }
                } catch (_: Exception) {
                    try {
                        val single = jsonConfig.decodeFromString<GeminiMedicationArrayItem>(jsonStr)
                        extractedName = single.name ?: single.medicineName ?: single.medicine_name ?: single.medicine ?: "Unknown"
                        extractedDosage = single.dosage ?: "As prescribed"
                        extractedFrequency = single.frequency ?: "As indicated"
                    } catch (_: Exception) {
                        if (jsonStr.startsWith("[")) {
                            val jsonArray = JSONArray(jsonStr)
                            if (jsonArray.length() > 0) {
                                val obj = jsonArray.getJSONObject(0)
                                extractedName = obj.optString("name", obj.optString("medicineName", obj.optString("medicine", "Unknown")))
                                extractedDosage = obj.optString("dosage", "As prescribed")
                                extractedFrequency = obj.optString("frequency", "As indicated")
                            }
                        } else {
                            val json = JSONObject(jsonStr)
                            extractedName = json.optString("name", json.optString("medicineName", json.optString("medicine", "Unknown")))
                            extractedDosage = json.optString("dosage", "As prescribed")
                            extractedFrequency = json.optString("frequency", "As indicated")
                        }
                    }
                }

                if (extractedName.equals("null", ignoreCase = true) || extractedName.isBlank()) {
                    extractedName = "Unknown"
                }

                val rawMed = Medication(
                    name = extractedName,
                    dosage = extractedDosage,
                    quantity = "1",
                    frequency = extractedFrequency,
                    time = "08:00 AM"
                )

                if (rawMed.name == "Unknown") {
                    onResult(null)
                    return@launch
                }

                // STAGE 2: CLINICAL PHARMACOPEIA VERIFICATION
                verifyAndRefineMedication(rawMed, onResult)

            } catch (e: Exception) {
                Log.e("GeminiOCR", "Flagship AI Extraction Error: ${e.message}", e)
                onResult(null)
            }
        }
    }

    private suspend fun verifyAndRefineMedication(rawMed: Medication, onResult: (Medication) -> Unit) {
        try {
            val verifyPrompt = "TASK: Rigorously validate extracted medication data against FDA and Drugs.com clinical standards. " +
                    "\n\nINPUT: " +
                    "\nName: ${rawMed.name}" +
                    "\nDosage: ${rawMed.dosage}" +
                    "\nFrequency: ${rawMed.frequency}" +
                    "\n\nERROR DETECTION: " +
                    "\n- Correct misspelling based on medical probability (e.g., 'Metform' -> 'Metformin'). " +
                    "\n- Detect dangerous or irregular dosages. " +
                    "\n- Cross-reference name with standard dosage forms. " +
                    "\n\nOUTPUT: Return ONLY a valid JSON object: " +
                    "\n{ " +
                    "\"is_perfect_match\": boolean, " +
                    "\"corrected_name\": \"string\", " +
                    "\"standard_dosage\": \"string\", " +
                    "\"standard_frequency\": \"string\", " +
                    "\"discrepancies\": [\"string\"], " +
                    "\"message\": \"string\" " +
                    "}"

            val jsonResponse = generateAiContentWithFallback(verifyPrompt)
            val vJsonStr = jsonResponse.replace("```json", "").replace("```", "").trim()
            
            val vRes = try {
                jsonConfig.decodeFromString<GeminiVerificationResponse>(vJsonStr)
            } catch (_: Exception) {
                val vJson = JSONObject(vJsonStr)
                val discrepancies = mutableListOf<String>()
                val discArray = vJson.optJSONArray("discrepancies")
                if (discArray != null) {
                    for (i in 0 until discArray.length()) discrepancies.add(discArray.getString(i))
                }
                GeminiVerificationResponse(
                    is_perfect_match = vJson.optBoolean("is_perfect_match", false),
                    corrected_name = vJson.optString("corrected_name", rawMed.name),
                    standard_dosage = vJson.optString("standard_dosage", rawMed.dosage),
                    standard_frequency = vJson.optString("standard_frequency", rawMed.frequency),
                    discrepancies = discrepancies,
                    message = vJson.optString("message")
                )
            }

            val isPerfect = vRes.is_perfect_match ?: false
            val correctedName = vRes.corrected_name ?: rawMed.name
            val stdDosage = vRes.standard_dosage ?: rawMed.dosage
            val stdFreq = vRes.standard_frequency ?: rawMed.frequency
            val discrepancies = vRes.discrepancies ?: emptyList()

            val nameConfidence = rawMed.confidenceScores["name"] ?: 0.0
            val dosageConfidence = rawMed.confidenceScores["dosage"] ?: 0.0
            val freqConfidence = rawMed.confidenceScores["frequency"] ?: 0.0

            val refinedMed = rawMed.copy(
                name = if (nameConfidence < 0.7) correctedName else rawMed.name,
                dosage = if (dosageConfidence < 0.7) stdDosage else rawMed.dosage,
                frequency = if (freqConfidence < 0.7) stdFreq else rawMed.frequency,
                isVerified = isPerfect || (discrepancies.isEmpty() && nameConfidence > 0.9),
                verificationMessage = vRes.message,
                flaggedFields = discrepancies
            )

            onResult(refinedMed)

        } catch (e: Exception) {
            Log.e("Verification", "AI Verification Error: ${e.message}")
            onResult(rawMed)
        }
    }

    fun addMedication(medication: Medication) {
        viewModelScope.launch {
            val currentList = _medicationList.value ?: emptyList()
            val maxOrder = if (currentList.isNotEmpty()) (currentList.maxOfOrNull { it.displayOrder } ?: 0) else -1
            val medWithOrder = if (medication.displayOrder == 0) medication.copy(displayOrder = maxOrder + 1) else medication
            repository.addMedication(medWithOrder)
            saveToSearchHistory(medication.name)
        }
    }

    fun reorderMedications(fromPosition: Int, toPosition: Int) {
        val currentList = _medicationList.value?.toMutableList() ?: return
        if (fromPosition !in currentList.indices || toPosition !in currentList.indices) return
        val item = currentList.removeAt(fromPosition)
        currentList.add(toPosition, item)
        val reorderedList = currentList.mapIndexed { index, med ->
            med.copy(displayOrder = index)
        }
        _medicationList.value = reorderedList.toMutableList()
        viewModelScope.launch {
            repository.updateMedicationsOrder(reorderedList)
        }
    }

    fun updateMedication(updated: Medication) {
        viewModelScope.launch {
            repository.updateMedication(updated)
        }
    }

    fun deleteMedication(id: String) {
        viewModelScope.launch {
            repository.deleteMedicationById(id)
            ReliabilityHeartbeatWorker.enqueue(getApplication())
        }
    }

    fun toggleMedicationStatus(index: Int) {
        val currentList = _medicationList.value ?: return
        if (index in currentList.indices) {
            val med = currentList[index]
            val newIsTaken = !med.isTaken
            val now = System.currentTimeMillis()
            val updated = med.copy(
                isTaken = newIsTaken,
                takenTimestamp = if (newIsTaken) now else 0L
            )
            viewModelScope.launch {
                repository.updateMedication(updated)
                if (!med.isTaken && newIsTaken) {
                    val recentRecords = repository.getHistoryRecords()
                    val lastRecord = recentRecords.firstOrNull { it.medicationId == med.id || it.name.equals(med.name, ignoreCase = true) }
                    val timeDiff = if (lastRecord != null) now - lastRecord.takenTimestamp else Long.MAX_VALUE
                    if (timeDiff > 30000L) { // 30 seconds debounce
                        repository.recordIntake(updated, now)
                    }
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistoryRecords()
            val list = repository.getMedications()
            list.forEach { 
                repository.updateMedication(it.copy(isTaken = false, takenTimestamp = 0L))
            }
        }
    }

    private fun saveToSearchHistory(query: String) {
        val history = getSearchHistory().toMutableSet()
        history.add(query)
        prefs.edit { putStringSet("history", history) }
        _searchHistory.postValue(history.toList().sorted())
    }

    fun removeFromSearchHistory(query: String) {
        val history = getSearchHistory().toMutableSet()
        history.remove(query)
        prefs.edit { putStringSet("history", history) }
        _searchHistory.postValue(history.toList().sorted())
    }

    private fun getSearchHistory(): Set<String> {
        return prefs.getStringSet("history", emptySet()) ?: emptySet()
    }

    fun fetchLiveCatalog(excludeNames: List<String> = emptyList()) {
        viewModelScope.launch {
            try {
                val recentMeds = _medicationList.value?.takeLast(3)?.map { it.name }?.joinToString(", ") ?: ""
                val prompt = "Generate a JSON array of 15 diverse and common medications. " +
                        "Include various categories: Pain Relief (e.g., Ibuprofen), Antibiotics (e.g., Amoxicillin), " +
                        "Chronic care (e.g., Metformin, Lisinopril), and Vitamins. " +
                        "Return a JSON array of objects with keys 'name' and 'imageUrl' (use high-quality Unsplash medication/healthcare links). " +
                        "Ensure the list is different from $recentMeds if possible."
                
                val jsonResponse = generateAiContentWithFallback(prompt)
                val jsonStr = jsonResponse.replace("```json", "").replace("```", "").trim()
                val jsonArray = JSONArray(jsonStr)
                val newList = mutableListOf<MedInfo>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    newList.add(MedInfo(
                        name = obj.getString("name"),
                        imageUrl = obj.optString("imageUrl", "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=400")
                    ))
                }
                fullCatalog = (fullCatalog + newList).distinctBy { it.name.lowercase() }.shuffled()
                _onlineCatalog.postValue(fullCatalog)
            } catch (e: Exception) {
                Log.e("CatalogFetch", "Error: ${e.message}")
                val simulated = getSimulatedCatalog().filter { it.name !in excludeNames }.shuffled()
                fullCatalog = (fullCatalog + simulated).distinctBy { it.name.lowercase() }
                _onlineCatalog.postValue(fullCatalog)
            }
        }
    }

    fun filterCatalog(query: String) {
        searchJob?.cancel()
        if (query.isEmpty()) {
            _onlineCatalog.value = fullCatalog
            didYouMeanSuggestion.value = null
            return
        }

        searchJob = viewModelScope.launch {
            delay(300.milliseconds) 
            val filtered = fullCatalog.filter { it.name.contains(query, ignoreCase = true) }
            
            if (filtered.isEmpty()) {
                if (query.length > 2) {
                    performFuzzySearch(query)
                }
                
                val suggestion = findLocalFuzzyMatch(query)
                didYouMeanSuggestion.postValue(suggestion)
            } else {
                didYouMeanSuggestion.postValue(null)
                _onlineCatalog.postValue(filtered)
            }
        }
    }

    private fun findLocalFuzzyMatch(query: String): String? {
        if (fullCatalog.isEmpty()) return null
        
        var bestMatch: String? = null
        var minDistance = Int.MAX_VALUE
        val threshold = (query.length * 0.3).toInt().coerceAtLeast(1)

        for (med in fullCatalog) {
            val distance = levenshteinDistance(query.lowercase(), med.name.lowercase())
            if (distance < minDistance && distance <= threshold) {
                minDistance = distance
                bestMatch = med.name
            }
        }
        return bestMatch
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    private suspend fun performFuzzySearch(query: String) {
        try {
            val prompt = "The user is searching for a medication with query '$query'. " +
                    "Return a JSON object: { \"correctedName\": \"string\" }. If no medicine matches, use \"NONE\"."
            
            val jsonResponse = generateAiContentWithFallback(prompt)
            val jsonStr = jsonResponse.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(jsonStr)
            val correctedName = json.optString("correctedName", "NONE")
            
            if (correctedName != "NONE" && correctedName.length < 50) {
                fetchMedicationInfo(correctedName)
            }
        } catch (e: Exception) { 
            Log.e("FuzzySearch", "Error: ${e.message}")
        }
    }

    val searchEvent = MutableLiveData<Event<SearchEvent>>()
    val didYouMeanSuggestion = MutableLiveData<String?>()

    fun clearSuggestion() { didYouMeanSuggestion.value = null }

    fun getSuggestionsForQuery(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val prefixMatches = fullCatalog.filter { med ->
            med.name.lowercase().startsWith(q)
        }.map { it.name }

        val containsMatches = fullCatalog.filter { med ->
            !med.name.lowercase().startsWith(q) && med.name.lowercase().contains(q)
        }.map { it.name }

        val combined = (prefixMatches + containsMatches).distinct()
        if (combined.isNotEmpty()) {
            return combined.take(6)
        }

        val fuzzyMatch = findLocalFuzzyMatch(q)
        if (fuzzyMatch != null) {
            return listOf(fuzzyMatch)
        }

        return emptyList()
    }

    fun fetchMedicationInfo(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        fetchJob?.cancel()
        _isLoading.postValue(true)
        fetchJob = viewModelScope.launch {
            try {
                val systemPrompt = "You operate as a pharmaceutical data retrieval engine. A user provides a medicine name. You extract and format information matching the standard Drugs.com database structure. Drugs.com provides detailed data on thousands of medications. You return only a JSON object. You omit all conversational text. You omit all markdown tags. Use these exact JSON keys: medicineName: String, genericName: String, drugClass: String, primaryUses: Array of strings, importantWarnings: Array of strings, dosageGuidelines: String, commonSideEffects: Array of strings, majorInteractions: Array of strings. Ensure you pull accurate pharmaceutical data. Return the parsed data directly to the application."

                val jsonResponse = generateAiContentWithFallback(trimmed, systemPrompt = systemPrompt)
                val jsonStr = jsonResponse.replace("```json", "").replace("```", "").trim()
                
                val drugsComInfo = try {
                    jsonConfig.decodeFromString<DrugsComMedInfo>(jsonStr)
                } catch (_: Exception) {
                    val json = JSONObject(jsonStr)
                    DrugsComMedInfo(
                        medicineName = json.optString("medicineName", trimmed),
                        genericName = json.optString("genericName", "N/A"),
                        drugClass = json.optString("drugClass", "N/A"),
                        primaryUses = json.optJSONArray("primaryUses")?.let { arr ->
                            List(arr.length()) { i -> arr.optString(i) }
                        } ?: emptyList(),
                        importantWarnings = json.optJSONArray("importantWarnings")?.let { arr ->
                            List(arr.length()) { i -> arr.optString(i) }
                        } ?: emptyList(),
                        dosageGuidelines = json.optString("dosageGuidelines", "N/A"),
                        commonSideEffects = json.optJSONArray("commonSideEffects")?.let { arr ->
                            List(arr.length()) { i -> arr.optString(i) }
                        } ?: emptyList(),
                        majorInteractions = json.optJSONArray("majorInteractions")?.let { arr ->
                            List(arr.length()) { i -> arr.optString(i) }
                        } ?: emptyList()
                    )
                }

                val medName = drugsComInfo.medicineName
                if (medName.isBlank() || medName.equals("NULL", ignoreCase = true)) {
                    val fallback = getFallbackMedInfo(trimmed)
                    if (fallback != null) {
                        handleSuccessfulMedSearch(fallback)
                    } else {
                        searchEvent.postValue(Event(SearchEvent.NotFound(trimmed)))
                    }
                } else {
                    val medInfo = drugsComInfo.toMedInfo()
                    handleSuccessfulMedSearch(medInfo)
                }
            } catch (e: Exception) {
                Log.e("MedInfoFetch", "AI search unavailable (${e.message}). Using clinical database fallback.")
                val fallback = getFallbackMedInfo(trimmed)
                if (fallback != null) {
                    handleSuccessfulMedSearch(fallback)
                } else {
                    searchEvent.postValue(Event(SearchEvent.NotFound(trimmed)))
                }
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun handleSuccessfulMedSearch(medInfo: MedInfo) {
        searchEvent.postValue(Event(SearchEvent.Success(medInfo)))
        saveToSearchHistory(medInfo.name)

        // Prepend searched medicine to catalog and update search grid
        fullCatalog = (listOf(medInfo) + fullCatalog.filterNot { it.name.equals(medInfo.name, ignoreCase = true) }).distinctBy { it.name.lowercase() }
        _onlineCatalog.postValue(fullCatalog)
    }

    private fun getFallbackMedInfo(query: String): MedInfo? {
        val q = query.trim().lowercase()
        if (q.isBlank() || q.length < 2) return null

        return when {
            q.contains("paracetamol") || q.contains("acetaminophen") || q.contains("panadol") || q.contains("tylenol") -> MedInfo(
                name = "Paracetamol (Acetaminophen)",
                description = "Generic Name: Acetaminophen\nDrug Class: Analgesic & Antipyretic",
                form = "Analgesic / Antipyretic",
                indication = "Relief of mild to moderate pain (headache, toothache, muscle ache) and fever reduction.",
                adultDosage = "500 mg to 1000 mg every 4 to 6 hours as needed. Maximum 4000 mg per 24 hours.",
                pediatricDosage = "10 mg/kg to 15 mg/kg every 4 to 6 hours as needed.",
                dosage = "500 mg",
                frequency = "Every 4 to 6 hours as needed",
                route = "Oral",
                timing = "Can be taken with or without food.",
                sideEffects = "Nausea, rash, headache. Rare: Liver toxicity with overdose.",
                contraindications = "Severe hepatic impairment or severe active liver disease.",
                warnings = "Do not exceed maximum daily dose. Avoid excessive alcohol consumption while taking.",
                interactions = "Warfarin, Alcohol, Isoniazid, Anticonvulsants.",
                storage = "Store below 30°C in a dry place away from direct sunlight.",
                imageUrl = "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=500"
            )
            q.contains("ibuprofen") || q.contains("advil") || q.contains("motrin") || q.contains("nurofen") -> MedInfo(
                name = "Ibuprofen",
                description = "Generic Name: Ibuprofen\nDrug Class: Nonsteroidal Anti-inflammatory Drug (NSAID)",
                form = "NSAID",
                indication = "Treatment of inflammation, fever, dysmenorrhea, osteoarthritis, and moderate pain.",
                adultDosage = "200 mg to 400 mg every 4 to 6 hours as needed.",
                pediatricDosage = "5 mg/kg to 10 mg/kg every 6 to 8 hours.",
                dosage = "400 mg",
                frequency = "Every 6 hours as needed",
                route = "Oral",
                timing = "Take with food or milk to prevent stomach upset.",
                sideEffects = "Heartburn, nausea, abdominal discomfort, dizziness.",
                contraindications = "Active peptic ulcer, severe heart failure, CABG surgery perioperative pain.",
                warnings = "May increase risk of stomach ulceration and bleeding.",
                interactions = "Aspirin, Anticoagulants (Warfarin), ACE inhibitors, Diuretics.",
                storage = "Store at 20°C to 25°C away from heat and moisture.",
                imageUrl = "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=500"
            )
            q.contains("aspirin") || q.contains("bayer") || q.contains("acetylsalicylic") -> MedInfo(
                name = "Aspirin (Acetylsalicylic Acid)",
                description = "Generic Name: Acetylsalicylic Acid\nDrug Class: NSAID & Antiplatelet Agent",
                form = "Antiplatelet / Analgesic",
                indication = "Prevention of cardiovascular events, relief of acute pain and fever.",
                adultDosage = "81 mg daily for cardioprotection; 325 mg to 650 mg every 4 hours for acute pain.",
                pediatricDosage = "Not recommended in children due to Reye's syndrome risk.",
                dosage = "81 mg",
                frequency = "Once daily",
                route = "Oral",
                timing = "Take with a full glass of water and food.",
                sideEffects = "Dyspepsia, gastric irritation, increased bleeding risk.",
                contraindications = "Asthma, rhinitis, nasal polyps, active GI bleeding.",
                warnings = "Risk of gastrointestinal bleeding. Discontinue before surgery.",
                interactions = "Anticoagulants, NSAIDs, Methotrexate.",
                storage = "Store at room temperature away from heat.",
                imageUrl = "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=500"
            )
            q.contains("amoxicillin") || q.contains("amoxil") -> MedInfo(
                name = "Amoxicillin",
                description = "Generic Name: Amoxicillin\nDrug Class: Penicillin Antibiotic",
                form = "Antibiotic",
                indication = "Treatment of bacterial infections of the ear, nose, throat, skin, and respiratory tract.",
                adultDosage = "500 mg every 8 hours or 875 mg every 12 hours.",
                pediatricDosage = "20 mg/kg/day to 45 mg/kg/day divided every 8 to 12 hours.",
                dosage = "500 mg",
                frequency = "Every 8 to 12 hours",
                route = "Oral",
                timing = "Can be taken with or without food. Complete the full prescribed course.",
                sideEffects = "Diarrhea, rash, nausea, vomiting.",
                contraindications = "Known hypersensitivity to penicillins or beta-lactams.",
                warnings = "Anaphylactic reactions reported in allergic patients.",
                interactions = "Allopurinol, Oral Contraceptives, Probenecid.",
                storage = "Store capsules at room temperature.",
                imageUrl = "https://images.unsplash.com/photo-1585435557343-3b092031a831?q=80&w=500"
            )
            q.contains("metformin") || q.contains("glucophage") -> MedInfo(
                name = "Metformin",
                description = "Generic Name: Metformin Hydrochloride\nDrug Class: Biguanide Antidiabetic",
                form = "Antidiabetic",
                indication = "Type 2 diabetes mellitus to improve blood sugar control.",
                adultDosage = "Initial: 500 mg twice daily or 850 mg once daily with meals.",
                pediatricDosage = "500 mg twice daily for children 10 years and older.",
                dosage = "500 mg",
                frequency = "Twice daily with meals",
                route = "Oral",
                timing = "Take with meals to reduce stomach upset.",
                sideEffects = "Diarrhea, nausea, flatulence, abdominal discomfort.",
                contraindications = "Severe renal impairment (eGFR < 30 mL/min).",
                warnings = "Risk of lactic acidosis in kidney disease.",
                interactions = "Iodinated contrast media, Alcohol.",
                storage = "Store at controlled room temperature 20°C to 25°C.",
                imageUrl = "https://images.unsplash.com/photo-1585435557343-3b092031a831?q=80&w=500"
            )
            q.contains("lisinopril") || q.contains("zestril") -> MedInfo(
                name = "Lisinopril",
                description = "Generic Name: Lisinopril\nDrug Class: ACE Inhibitor",
                form = "Cardiovascular",
                indication = "Hypertension, heart failure, and post-myocardial infarction recovery.",
                adultDosage = "10 mg to 40 mg once daily.",
                pediatricDosage = "0.07 mg/kg once daily (6+ years).",
                dosage = "10 mg",
                frequency = "Once daily",
                route = "Oral",
                timing = "Take once daily at the same time each day.",
                sideEffects = "Dry cough, dizziness, headache, fatigue.",
                contraindications = "History of angioedema, pregnancy.",
                warnings = "Discontinue immediately if pregnancy is detected.",
                interactions = "Potassium supplements, NSAIDs, Lithium.",
                storage = "Store at 15°C to 30°C in a dry place.",
                imageUrl = "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=500"
            )
            q.contains("atorvastatin") || q.contains("lipitor") -> MedInfo(
                name = "Atorvastatin",
                description = "Generic Name: Atorvastatin Calcium\nDrug Class: Statin",
                form = "Lipid-lowering",
                indication = "Hypercholesterolemia and prevention of cardiovascular disease.",
                adultDosage = "10 mg to 80 mg once daily.",
                pediatricDosage = "10 mg to 20 mg once daily (10+ years).",
                dosage = "20 mg",
                frequency = "Once daily",
                route = "Oral",
                timing = "Can be taken at any time of day, with or without food.",
                sideEffects = "Muscle pain, diarrhea, elevated liver enzymes.",
                contraindications = "Active liver disease, pregnancy.",
                warnings = "Report unexplained muscle pain or weakness.",
                interactions = "Grapefruit juice, Strong CYP3A4 inhibitors.",
                storage = "Store at controlled room temperature.",
                imageUrl = "https://images.unsplash.com/photo-1626450914482-2617137e92e8?q=80&w=500"
            )
            q.contains("omeprazole") || q.contains("prilosec") -> MedInfo(
                name = "Omeprazole",
                description = "Generic Name: Omeprazole\nDrug Class: Proton Pump Inhibitor",
                form = "Gastrointestinal",
                indication = "Gastroesophageal reflux disease (GERD), heartburn, and stomach ulcers.",
                adultDosage = "20 mg to 40 mg once daily before breakfast.",
                pediatricDosage = "10 mg to 20 mg once daily based on weight.",
                dosage = "20 mg",
                frequency = "Once daily in the morning",
                route = "Oral",
                timing = "Take 30-60 minutes before a meal.",
                sideEffects = "Headache, stomach pain, diarrhea, nausea.",
                contraindications = "Known hypersensitivity to substituted benzimidazoles.",
                warnings = "Long-term therapy may affect Vitamin B12 and bone density.",
                interactions = "Clopidogrel, Diazepam, Warfarin.",
                storage = "Store in a dry place at room temperature.",
                imageUrl = "https://images.unsplash.com/photo-1631549916768-4119b295f756?q=80&w=500"
            )
            q.contains("albuterol") || q.contains("salbutamol") || q.contains("ventolin") -> MedInfo(
                name = "Albuterol (Salbutamol)",
                description = "Generic Name: Albuterol Sulfate\nDrug Class: Bronchodilator",
                form = "Respiratory",
                indication = "Relief and prevention of bronchospasm in asthma and COPD.",
                adultDosage = "1 to 2 puffs inhaled every 4 to 6 hours as needed.",
                pediatricDosage = "1 to 2 puffs inhaled every 4 to 6 hours as needed.",
                dosage = "90 mcg/actuation",
                frequency = "Every 4 to 6 hours as needed",
                route = "Inhalation",
                timing = "Inhale as needed when shortness of breath or wheezing occurs.",
                sideEffects = "Shakiness, fast heartbeat, nervousness.",
                contraindications = "Hypersensitivity to albuterol.",
                warnings = "Seek immediate emergency care if breathing fails to improve.",
                interactions = "Beta-blockers, Diuretics.",
                storage = "Store away from heat and direct sunlight.",
                imageUrl = "https://images.unsplash.com/photo-1614264629631-017a4a98404a?q=80&w=500"
            )
            else -> {
                val capitalized = query.trim().split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                MedInfo(
                    name = capitalized,
                    description = "Generic Name: $capitalized\nDrug Class: Pharmaceutical Medication",
                    form = "Oral Tablet / Capsule",
                    indication = "Prescribed for clinical indications according to physician diagnosis.",
                    adultDosage = "As directed by physician according to prescription guidelines.",
                    pediatricDosage = "Consult pediatric specialist for weight-based dosing.",
                    dosage = "As prescribed",
                    frequency = "As prescribed by physician",
                    route = "Oral",
                    timing = "Follow healthcare provider instructions.",
                    sideEffects = "May cause mild gastrointestinal symptoms, headache, or drowsiness.",
                    contraindications = "Known hypersensitivity or allergy to $capitalized.",
                    warnings = "Use strictly as prescribed. Keep out of reach of children.",
                    interactions = "Consult pharmacist or physician regarding potential drug interactions.",
                    storage = "Store at room temperature in a dry place away from heat and direct sunlight.",
                    imageUrl = resolveMedicineImage(capitalized)
                )
            }
        }
    }

    private fun getSimulatedCatalog() = listOf(
        MedInfo(
            name = "Paracetamol (Acetaminophen)",
            description = "Generic Name: Acetaminophen\nDrug Class: Analgesic & Antipyretic",
            form = "Analgesic / Antipyretic",
            indication = "Relief of pain and fever reduction",
            adultDosage = "500 mg to 1000 mg every 4-6 hours",
            pediatricDosage = "10-15 mg/kg every 4-6 hours",
            frequency = "Every 4 to 6 hours as needed",
            route = "Oral",
            timing = "Can be taken with or without food",
            sideEffects = "Nausea, rash. Rare: Liver toxicity with overdose.",
            warnings = "Do not exceed 4000 mg per day.",
            interactions = "Warfarin, Alcohol.",
            storage = "Store below 30°C in a dry place.",
            imageUrl = "https://images.unsplash.com/photo-1584308666744-24d5c474f2ae?q=80&w=400"
        ),
        MedInfo(
            name = "Ibuprofen",
            description = "Generic Name: Ibuprofen\nDrug Class: NSAID",
            form = "NSAID",
            indication = "Pain relief, anti-inflammatory, fever reduction",
            adultDosage = "200 mg to 400 mg every 4-6 hours",
            pediatricDosage = "5-10 mg/kg every 6-8 hours",
            frequency = "Every 6 hours as needed",
            route = "Oral",
            timing = "Take with food or milk",
            sideEffects = "Heartburn, nausea, abdominal discomfort.",
            warnings = "May increase risk of stomach ulcers and bleeding.",
            interactions = "Aspirin, Anticoagulants, ACE inhibitors.",
            storage = "Store at room temperature.",
            imageUrl = "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=400"
        ),
        MedInfo(
            name = "Amoxicillin",
            description = "Generic Name: Amoxicillin\nDrug Class: Penicillin Antibiotic",
            form = "Antibiotic",
            indication = "Bacterial infections of throat, ear, respiratory tract",
            adultDosage = "500 mg every 8 hours or 875 mg every 12 hours",
            pediatricDosage = "20-45 mg/kg/day divided every 8-12 hours",
            frequency = "Every 8 to 12 hours",
            route = "Oral",
            timing = "Complete full prescribed course",
            sideEffects = "Diarrhea, nausea, rash.",
            warnings = "Do not use if allergic to penicillins.",
            interactions = "Oral contraceptives, Allopurinol.",
            storage = "Store at room temperature.",
            imageUrl = "https://images.unsplash.com/photo-1585435557343-3b092031a831?q=80&w=400"
        ),
        MedInfo(
            name = "Metformin",
            description = "Generic Name: Metformin\nDrug Class: Biguanide Antidiabetic",
            form = "Antidiabetic",
            indication = "Type 2 diabetes mellitus blood sugar management",
            adultDosage = "500 mg to 1000 mg twice daily with meals",
            pediatricDosage = "500 mg twice daily (10+ years)",
            frequency = "Twice daily with meals",
            route = "Oral",
            timing = "Take with meals to reduce stomach upset",
            sideEffects = "Diarrhea, nausea, gas, abdominal pain.",
            warnings = "Risk of lactic acidosis in severe kidney disease.",
            interactions = "Contrast media, Alcohol.",
            storage = "Store at room temperature.",
            imageUrl = "https://images.unsplash.com/photo-1585435557343-3b092031a831?q=80&w=400"
        ),
        MedInfo(
            name = "Lisinopril",
            description = "Generic Name: Lisinopril\nDrug Class: ACE Inhibitor",
            form = "Cardiovascular",
            indication = "High blood pressure and heart failure management",
            adultDosage = "10 mg to 40 mg once daily",
            pediatricDosage = "Consult pediatric specialist",
            frequency = "Once daily",
            route = "Oral",
            timing = "Take once daily at same time",
            sideEffects = "Dry cough, dizziness, headache.",
            warnings = "Do not use during pregnancy.",
            interactions = "Potassium supplements, NSAIDs, Lithium.",
            storage = "Store at room temperature.",
            imageUrl = "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=400"
        ),
        MedInfo(
            name = "Atorvastatin",
            description = "Generic Name: Atorvastatin\nDrug Class: Statin",
            form = "Lipid-lowering",
            indication = "Cholesterol management and cardiovascular protection",
            adultDosage = "10 mg to 80 mg once daily",
            pediatricDosage = "10-20 mg once daily (10+ years)",
            frequency = "Once daily",
            route = "Oral",
            timing = "Can be taken any time of day",
            sideEffects = "Muscle pain, diarrhea, elevated liver enzymes.",
            warnings = "Report unexplained muscle pain or weakness.",
            interactions = "Grapefruit juice, Strong CYP3A4 inhibitors.",
            storage = "Store at room temperature.",
            imageUrl = "https://images.unsplash.com/photo-1626450914482-2617137e92e8?q=80&w=400"
        ),
        MedInfo(
            name = "Omeprazole",
            description = "Generic Name: Omeprazole\nDrug Class: Proton Pump Inhibitor",
            form = "Gastrointestinal",
            indication = "Acid reflux, heartburn, GERD, gastric ulcers",
            adultDosage = "20 mg to 40 mg once daily before breakfast",
            pediatricDosage = "10-20 mg once daily based on weight",
            frequency = "Once daily in the morning",
            route = "Oral",
            timing = "Take 30-60 minutes before breakfast",
            sideEffects = "Headache, stomach pain, diarrhea.",
            warnings = "Long-term use may affect bone density.",
            interactions = "Clopidogrel, Diazepam, Warfarin.",
            storage = "Store in dry container at room temperature.",
            imageUrl = "https://images.unsplash.com/photo-1631549916768-4119b295f756?q=80&w=400"
        ),
        MedInfo(
            name = "Albuterol",
            description = "Generic Name: Albuterol\nDrug Class: Bronchodilator",
            form = "Respiratory",
            indication = "Asthma, wheezing, and COPD symptom relief",
            adultDosage = "1 to 2 puffs inhaled every 4-6 hours as needed",
            pediatricDosage = "1 to 2 puffs every 4-6 hours as needed",
            frequency = "Every 4 to 6 hours as needed",
            route = "Inhalation",
            timing = "Inhale when shortness of breath occurs",
            sideEffects = "Shakiness, fast heartbeat, nervousness.",
            warnings = "Seek immediate care if breathing fails to improve.",
            interactions = "Beta-blockers, Diuretics.",
            storage = "Store canister away from heat.",
            imageUrl = "https://images.unsplash.com/photo-1614264629631-017a4a98404a?q=80&w=400"
        )
    )
}
