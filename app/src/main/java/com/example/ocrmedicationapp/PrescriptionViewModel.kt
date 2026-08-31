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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class PrescriptionViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val GEMINI_API_KEY = "AQ.Ab8RN6KF26q_S-d82kj5ZYd4HT_71r7HSdO21EE7N7pRhdN8mA"
        private const val GEMINI_MODEL = "gemini-2.0-flash"
    }

    private val repository = MedicationRepository(application)
    private val prefs = application.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
    
    private val _medicationList = MutableLiveData<MutableList<Medication>>(mutableListOf())
    val medicationList: MutableLiveData<MutableList<Medication>> = _medicationList

    private val _onlineCatalog = MutableLiveData<List<MedInfo>>()
    val onlineCatalog: MutableLiveData<List<MedInfo>> = _onlineCatalog

    private val _searchHistory = MutableLiveData<List<String>>()
    val searchHistory: MutableLiveData<List<String>> = _searchHistory
    
    private var fullCatalog: List<MedInfo> = emptyList()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getAllMedications().collect { list ->
                _medicationList.value = list.toMutableList()
            }
        }
        _searchHistory.value = getSearchHistory().toList().sorted()
    }


    // Gemini AI Configuration
    private val generativeModel = GenerativeModel(
        modelName = GEMINI_MODEL, 
        apiKey = GEMINI_API_KEY
    )

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

    fun extractWithGemini(bitmap: Bitmap, onResult: (Medication?) -> Unit) {
        viewModelScope.launch {
            try {
                // STAGE 1: EXTRACTION
                val extractPrompt = "ACT AS AN EXPERT COMPUTER VISION ENGINEER. " +
                        "TASK: Extract medical data from this handwritten prescription. " +
                        "\n\nINSTRUCTIONS: " +
                        "\n1. Perform CHARACTER-LEVEL analysis on cursive text. " +
                        "\n2. Distinguish between '0' vs 'O' and '1' vs 'I' strictly by visual shape. " +
                        "\n3. Provide a CONFIDENCE SCORE (0.0 to 1.0) for every field. " +
                        "\n\nOUTPUT FORMAT: Return ONLY a valid JSON object: " +
                        "\n{ " +
                        "\"name\": { \"value\": \"string\", \"confidence\": float }, " +
                        "\"dosage\": { \"value\": \"string\", \"confidence\": float }, " +
                        "\"quantity\": { \"value\": \"string\", \"confidence\": float }, " +
                        "\"frequency\": { \"value\": \"string\", \"confidence\": float }, " +
                        "\"time\": { \"value\": \"string\", \"confidence\": float } " +
                        "}"

                val extractResponse = withContext(Dispatchers.IO) {
                    generativeModel.generateContent(
                        content {
                            image(bitmap)
                            text(extractPrompt)
                        }
                    ).text ?: "{}"
                }

                val jsonStr = extractResponse.replace("```json", "").replace("```", "").trim()
                val json = JSONObject(jsonStr)

                val nameObj = json.optJSONObject("name")
                val dosageObj = json.optJSONObject("dosage")
                val quantityObj = json.optJSONObject("quantity")
                val frequencyObj = json.optJSONObject("frequency")
                val timeObj = json.optJSONObject("time")

                val rawMed = Medication(
                    name = nameObj?.optString("value", "Unknown") ?: "Unknown",
                    dosage = dosageObj?.optString("value", "As indicated") ?: "As indicated",
                    quantity = quantityObj?.optString("value", "1") ?: "1",
                    frequency = frequencyObj?.optString("value", "Once daily") ?: "Once daily",
                    time = timeObj?.optString("value", "08:00 AM") ?: "08:00 AM",
                    confidenceScores = mapOf(
                        "name" to (nameObj?.optDouble("confidence", 0.0) ?: 0.0),
                        "dosage" to (dosageObj?.optDouble("confidence", 0.0) ?: 0.0),
                        "quantity" to (quantityObj?.optDouble("confidence", 0.0) ?: 0.0),
                        "frequency" to (frequencyObj?.optDouble("confidence", 0.0) ?: 0.0),
                        "time" to (timeObj?.optDouble("confidence", 0.0) ?: 0.0)
                    )
                )

                if (rawMed.name == "Unknown") {
                    onResult(null)
                    return@launch
                }

                // STAGE 2: VERIFICATION (Background Process)
                verifyAndRefineMedication(rawMed, onResult)

            } catch (e: Exception) {
                Log.e("GeminiOCR", "AI Extraction Error: ${e.message}", e)
                onResult(null)
            }
        }
    }

    private suspend fun verifyAndRefineMedication(rawMed: Medication, onResult: (Medication) -> Unit) {
        try {
            val verifyPrompt = "ACT AS A WORLD-CLASS CLINICAL PHARMACIST. " +
                    "TASK: Validate this extracted medication data against official drug monographs. " +
                    "\n\nDATA TO VALIDATE: " +
                    "\nName: ${rawMed.name}" +
                    "\nDosage: ${rawMed.dosage}" +
                    "\nFrequency: ${rawMed.frequency}" +
                    "\n\nLOGIC: " +
                    "\n1. If the name is misspelled (e.g. 'Amoxcilin'), provide the CORRECTED name. " +
                    "\n2. Compare extraction to standard strengths (e.g. 500mg is standard for Amoxicillin, but 5000mg is not). " +
                    "\n3. Check if frequency is medically common. " +
                    "\n\nOUTPUT FORMAT: Return ONLY a valid JSON object: " +
                    "\n{ " +
                    "\"is_perfect_match\": boolean, " +
                    "\"corrected_name\": \"string\", " +
                    "\"standard_dosage\": \"string\", " +
                    "\"standard_frequency\": \"string\", " +
                    "\"discrepancies\": [\"list of fields that deviate from standard\"], " +
                    "\"message\": \"string summary of validation\" " +
                    "}"

            val verifyResponse = withContext(Dispatchers.IO) {
                generativeModel.generateContent(verifyPrompt).text ?: "{}"
            }

            val vJsonStr = verifyResponse.replace("```json", "").replace("```", "").trim()
            val vJson = JSONObject(vJsonStr)

            val isPerfect = vJson.optBoolean("is_perfect_match", false)
            val correctedName = vJson.optString("corrected_name", rawMed.name)
            val stdDosage = vJson.optString("standard_dosage", rawMed.dosage)
            val stdFreq = vJson.optString("standard_frequency", rawMed.frequency)
            val discrepancies = mutableListOf<String>()
            val discArray = vJson.optJSONArray("discrepancies")
            if (discArray != null) {
                for (i in 0 until discArray.length()) discrepancies.add(discArray.getString(i))
            }

            // LOGIC: Prioritize verified data if confidence is low (< 0.7)
            val nameConfidence = rawMed.confidenceScores["name"] ?: 0.0
            val dosageConfidence = rawMed.confidenceScores["dosage"] ?: 0.0
            val freqConfidence = rawMed.confidenceScores["frequency"] ?: 0.0

            val refinedMed = rawMed.copy(
                name = if (nameConfidence < 0.7) correctedName else rawMed.name,
                dosage = if (dosageConfidence < 0.7) stdDosage else rawMed.dosage,
                frequency = if (freqConfidence < 0.7) stdFreq else rawMed.frequency,
                isVerified = isPerfect || (discrepancies.isEmpty() && nameConfidence > 0.9),
                verificationMessage = vJson.optString("message"),
                flaggedFields = discrepancies
            )

            onResult(refinedMed)

        } catch (e: Exception) {
            Log.e("Verification", "Error: ${e.message}")
            onResult(rawMed) // Return raw if verification fails
        }
    }

    fun addMedication(medication: Medication) {
        viewModelScope.launch {
            repository.addMedication(medication)
            saveToSearchHistory(medication.name)
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
        }
    }

    fun toggleMedicationStatus(index: Int) {
        val currentList = _medicationList.value ?: return
        if (index in currentList.indices) {
            val med = currentList[index]
            val updated = med.copy(isTaken = !med.isTaken)
            updateMedication(updated)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val list = repository.getMedications()
            list.forEach { 
                repository.updateMedication(it.copy(isTaken = false))
            }
        }
    }

    private fun saveToSearchHistory(query: String) {
        val history = getSearchHistory().toMutableSet()
        history.add(query)
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
                val prompt = "Generate a JSON array of 10 currently trending or common medications. " +
                        (if (recentMeds.isNotEmpty()) "Context: User recently scanned $recentMeds. " else "") +
                        "Include diverse categories like Pain Relief, Antibiotics, and Supplements. " +
                        "Return ONLY raw JSON with keys 'name' and 'imageUrl' (use high-quality Unsplash medication links)."
                
                val response = withContext(Dispatchers.IO) { generativeModel.generateContent(prompt) }
                val jsonStr = response.text?.replace("```json", "")?.replace("```", "")?.trim() ?: "[]"
                val jsonArray = JSONArray(jsonStr)
                val newList = mutableListOf<MedInfo>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    newList.add(MedInfo(obj.getString("name"), "", "", "", obj.optString("imageUrl")))
                }
                fullCatalog = (fullCatalog + newList).distinctBy { it.name }
                _onlineCatalog.postValue(fullCatalog)
            } catch (e: Exception) {
                Log.e("CatalogFetch", "Error: ${e.message}")
                val simulated = getSimulatedCatalog().filter { it.name !in excludeNames }.shuffled()
                fullCatalog = (fullCatalog + simulated).distinctBy { it.name }
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
                // Perform local fuzzy search for < 100ms response
                val suggestion = findLocalFuzzyMatch(query)
                didYouMeanSuggestion.postValue(suggestion)
                
                if (query.length > 2 && suggestion == null) {
                    performFuzzySearch(query)
                }
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
                    "This might have a typo. Return the correct generic or brand name as a single string. " +
                    "If no medicine matches, return 'NONE'. No conversational text."
            val response = withContext(Dispatchers.IO) { generativeModel.generateContent(prompt) }
            val correctedName = response.text?.trim() ?: "NONE"
            
            if (correctedName != "NONE" && correctedName.length < 50) {
                fetchMedicationInfo(correctedName)
            }
        } catch (e: Exception) { 
            Log.e("FuzzySearch", "Error: ${e.message}")
        }
    }

    fun fetchMedicationInfo(query: String) {
        viewModelScope.launch {
            try {
                val prompt = "ACT AS A CLINICAL PHARMACIST AND MEDICAL DATA ANALYST. " +
                        "TASK: Retrieve and VERIFY medical information for '$query' based on official clinical records equivalent to Drugs.com. " +
                        "\n\nDATA REQUIREMENTS: " +
                        "\n1. Description: One concise paragraph overview. " +
                        "\n2. Adult Dosage: Standard therapeutic ranges. " +
                        "\n3. Pediatric Dosage: Detailed age-based or weight-based brackets. " +
                        "\n4. Frequency: Common dosing intervals. " +
                        "\n5. Warnings: Black box warnings or critical safety alerts. " +
                        "\n6. Interactions: Major drug-drug or food-drug interactions. " +
                        "\n7. Storage: Proper environmental conditions. " +
                        "\n\nVERIFICATION STEP: Confirm the accuracy of this data against current clinical guidelines before returning. " +
                        "\n\nOUTPUT FORMAT: Return ONLY a valid JSON object with these exact keys: " +
                        "'name', 'description', 'form', 'indication', 'adultDosage', 'pediatricDosage', " +
                        "'frequency', 'route', 'timing', 'sideEffects', 'contraindications', " +
                        "'warnings', 'interactions', 'storage', 'imageUrl'. " +
                        "\n\nIf '$query' is not a valid medication, return 'NULL'. Raw JSON only."

                val response = withContext(Dispatchers.IO) { generativeModel.generateContent(prompt) }
                val resultText = response.text?.trim() ?: "NULL"
                if (resultText == "NULL" || resultText.contains("null", ignoreCase = true)) {
                    selectedMedDetails.postValue(null)
                } else {
                    val jsonStr = resultText.replace("```json", "").replace("```", "").trim()
                    val json = JSONObject(jsonStr)
                    selectedMedDetails.postValue(MedInfo(
                        name = json.optString("name", query),
                        description = json.optString("description", "No description available."),
                        form = json.optString("form", "N/A"),
                        indication = json.optString("indication", "N/A"),
                        adultDosage = json.optString("adultDosage", "N/A"),
                        pediatricDosage = json.optString("pediatricDosage", "N/A"),
                        frequency = json.optString("frequency", "As prescribed"),
                        route = json.optString("route", "N/A"),
                        timing = json.optString("timing", "N/A"),
                        sideEffects = json.optString("sideEffects", "N/A"),
                        contraindications = json.optString("contraindications", "N/A"),
                        warnings = json.optString("warnings", "N/A"),
                        interactions = json.optString("interactions", "N/A"),
                        storage = json.optString("storage", "N/A"),
                        imageUrl = json.optString("imageUrl", "https://images.unsplash.com/photo-1471864190281-a93a3070b6de?q=80&w=500")
                    ))
                    saveToSearchHistory(query)
                }
            } catch (e: Exception) {
                Log.e("MedInfoFetch", "Error: ${e.message}")
                selectedMedDetails.postValue(null)
            }
        }
    }

    private fun getSimulatedCatalog() = listOf(
        MedInfo("Metformin", "", "", "", "https://images.unsplash.com/photo-1585435557343-3b092031a831?q=80&w=400"),
        MedInfo("Lisinopril", "", "", "", "https://images.unsplash.com/photo-1584017911766-d451b3d0e843?q=80&w=400"),
        MedInfo("Atorvastatin", "", "", "", "https://images.unsplash.com/photo-1626450914482-2617137e92e8?q=80&w=400"),
        MedInfo("Omeprazole", "", "", "", "https://images.unsplash.com/photo-1631549916768-4119b295f756?q=80&w=400"),
        MedInfo("Albuterol", "", "", "", "https://images.unsplash.com/photo-1614264629631-017a4a98404a?q=80&w=400")
    )

    val selectedMedDetails = MutableLiveData<MedInfo?>()
    val didYouMeanSuggestion = MutableLiveData<String?>()
    
    fun clearSelectedMedDetails() { selectedMedDetails.value = null }
    fun clearSuggestion() { didYouMeanSuggestion.value = null }
}

data class MedInfo(
    val name: String,
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
    val storage: String = ""
)
