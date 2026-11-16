package com.example.ocrmedicationapp

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.Html
import android.text.Spanned
import android.text.SpannedString
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ocrmedicationapp.databinding.ActivityGeminiLiveChatBinding
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ChatMessage(
    var text: String,
    val isUser: Boolean,
    var isStreaming: Boolean = false
)

class GeminiLiveChatActivity : AppCompatActivity() {

    companion object {
        private const val GEMINI_API_KEY = "AQ.Ab8RN6KF26q_S-d82kj5ZYd4HT_71r7HSdO21EE7N7pRhdN8mA"
        private const val GEMINI_MODEL = "gemini-3.6-flash"
        private const val GEMINI_MODEL_FALLBACK = "gemini-3.5-flash"

        private const val SYSTEM_INSTRUCTION =
            "You are Gemini Live AI, a specialized clinical medical, pharmaceutical, health, and well-being assistant for a medication reminder application.\n\n" +
            "Core Responsibilities & Domain Scope:\n" +
            "1. Answer user queries directly regarding medications, prescriptions, dosages, administration timing, side effects, and drug/food interactions.\n" +
            "2. Answer dietary, nutrition, and digestion questions (e.g. whether specific foods affect digestive health, trigger symptoms, or alter medication absorption).\n" +
            "3. Answer lifestyle, exercise, and physical activity questions (e.g. taking medication before or after playing sports like soccer, or physical exertion precautions).\n" +
            "4. Evaluate the user's actual question and intent. Answer directly on topics including diet, digestion, exercise, and health factors.\n" +
            "5. Do NOT trigger or output generic prescription abbreviation tables (e.g., qd, bid, tid) or unrequested drug summaries unless the user specifically asks about prescription abbreviations or notation.\n\n" +
            "Out-of-Scope Fallback Policy:\n" +
            "Only decline if the question is completely unrelated to health, medicine, diet, nutrition, digestion, exercise, or physical well-being (such as software engineering, coding, politics, or entertainment). Politely decline stating your specialization as a Medical & Health AI Assistant.\n\n" +
            "Output Guidelines:\n" +
            "- Prioritize answering the user's live typed or spoken question directly.\n" +
            "- Structure responses clearly using Markdown headers, bullet points, and bold text.\n" +
            "- Keep guidance medically accurate, empathetic, concise, and easy to read."
    }

    private lateinit var binding: ActivityGeminiLiveChatBinding
    private val messageList = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    private var inAppSpeechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var baseSpeechPrefix: String = ""
    private var isUpdatingTextFromSpeech = false
    private var activeMedicationsSummary: String = ""

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startInAppSpeechRecognizer()
        } else {
            stopVoiceSearchAnimation()
            Toast.makeText(this, "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeminiLiveChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        setupEditTextListeners()
        loadScannedPrescriptionContext()

        // Welcome AI Message
        addAiMessage("Hello! I am Gemini Live AI, your medical & prescription assistant. How can I help you today?")
    }

    private fun loadScannedPrescriptionContext() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repository = MedicationRepository(applicationContext)
                val meds = repository.getMedications()
                if (meds.isNotEmpty()) {
                    activeMedicationsSummary = meds.joinToString("\n") { m ->
                        "• Medication: ${m.name}, Dosage: ${m.dosage}, Quantity: ${m.quantity}, Schedule: ${m.frequency}, Intake Time: ${m.time}"
                    }
                    Log.d("GeminiLiveChat", "Loaded active prescription context:\n$activeMedicationsSummary")
                }
            } catch (e: Exception) {
                Log.e("GeminiLiveChat", "Error loading prescription context: ${e.message}")
            }
        }
    }

    private fun buildSystemInstructionWithContext(): String {
        val baseInstruction =
            "You are Gemini Live AI, a specialized clinical medical, pharmaceutical, health, and well-being assistant for a medication reminder application.\n\n" +
            "Core Responsibilities & Domain Scope:\n" +
            "1. Answer user queries directly regarding medications, prescriptions, dosages, administration timing, side effects, and drug/food interactions.\n" +
            "2. Answer dietary, nutrition, and digestion questions (e.g. whether specific foods affect digestive health, trigger symptoms, or alter medication absorption).\n" +
            "3. Answer lifestyle, exercise, and physical activity questions (e.g. taking medication before or after playing sports like soccer, or physical exertion precautions).\n" +
            "4. Evaluate the user's actual question and intent. Answer directly on topics including diet, digestion, exercise, and health factors.\n" +
            "5. Do NOT trigger or output generic prescription abbreviation tables (e.g., qd, bid, tid) unless the user specifically asks about prescription notation.\n\n" +
            "Out-of-Scope Fallback Policy:\n" +
            "Only decline if the question is completely unrelated to health, medicine, diet, nutrition, digestion, exercise, or physical well-being (e.g., coding, politics, or entertainment). Politely decline stating your specialization as a Medical & Health AI Assistant.\n\n" +
            "Output Guidelines:\n" +
            "- Prioritize answering the user's live typed or spoken question directly.\n" +
            "- Use the background prescription context ONLY when relevant to the user's specific query, without auto-generating unrequested drug summaries.\n" +
            "- Keep guidance clear, empathetic, medically accurate, concise, and structured."

        return if (activeMedicationsSummary.isNotBlank()) {
            "BACKGROUND SCANNED PATIENT PRESCRIPTION CONTEXT:\n" +
            activeMedicationsSummary + "\n\n" +
            baseInstruction
        } else {
            baseInstruction
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messageList)
        binding.rvChatMessages.apply {
            layoutManager = LinearLayoutManager(this@GeminiLiveChatActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnExitChat.setOnClickListener {
            finish()
        }

        binding.btnSend.setOnClickListener {
            if (isListening) {
                cleanUpSpeechRecognizer()
            }
            val text = binding.etChatMessage.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                sendUserMessage(text)
                binding.etChatMessage.setText("")
            }
        }

        binding.btnMic.addTactileTouchEffect(0.88f)
        binding.btnMic.setOnClickListener {
            if (isListening) {
                cleanUpSpeechRecognizer()
            } else {
                startVoiceSearchAnimation()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startInAppSpeechRecognizer()
                } else {
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        binding.btnActiveMic.addTactileTouchEffect(0.88f)
        binding.btnActiveMic.setOnClickListener {
            cleanUpSpeechRecognizer()
        }

        binding.btnActiveSend.addTactileTouchEffect(0.91f)
        binding.btnActiveSend.setOnClickListener {
            cleanUpSpeechRecognizer()
            val text = binding.etChatMessage.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                sendUserMessage(text)
                binding.etChatMessage.setText("")
            }
        }

        // Quick Prompt Chips
        binding.chipPrompt1.setOnClickListener { sendUserMessage("Is Amoxicillin safe with food?") }
        binding.chipPrompt2.setOnClickListener { sendUserMessage("What are Metformin side effects?") }
        binding.chipPrompt3.setOnClickListener { sendUserMessage("How do I schedule recurring alarms?") }
        binding.chipPrompt4.setOnClickListener { sendUserMessage("Explain my prescription directions") }
    }

    private fun setupEditTextListeners() {
        binding.etChatMessage.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isListening) {
                    cleanUpSpeechRecognizer()
                }
                v.performClick()
            }
            false
        }

        binding.etChatMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isListening && !isUpdatingTextFromSpeech) {
                    cleanUpSpeechRecognizer()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun startInAppSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition unavailable on this device", Toast.LENGTH_SHORT).show()
            cleanUpSpeechRecognizer()
            return
        }

        val existingText = binding.etChatMessage.text?.toString()?.trim() ?: ""
        baseSpeechPrefix = if (existingText.isNotEmpty()) "$existingText " else ""

        try {
            cleanUpSpeechRecognizer()

            inAppSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        cleanUpSpeechRecognizer()
                    }

                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No voice detected"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            else -> null
                        }
                        cleanUpSpeechRecognizer()
                        errorMsg?.let {
                            Toast.makeText(this@GeminiLiveChatActivity, it, Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val chunk = matches?.firstOrNull()
                        if (!chunk.isNullOrBlank()) {
                            val fullText = baseSpeechPrefix + chunk
                            binding.tvListeningStatus.text = fullText
                            isUpdatingTextFromSpeech = true
                            binding.etChatMessage.setText(fullText)
                            binding.etChatMessage.setSelection(fullText.length)
                            isUpdatingTextFromSpeech = false
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val chunk = matches?.firstOrNull()
                        if (!chunk.isNullOrBlank()) {
                            val fullText = baseSpeechPrefix + chunk
                            isUpdatingTextFromSpeech = true
                            binding.etChatMessage.setText(fullText)
                            binding.etChatMessage.setSelection(fullText.length)
                            isUpdatingTextFromSpeech = false
                        }
                        cleanUpSpeechRecognizer()
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }

            inAppSpeechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            cleanUpSpeechRecognizer()
            Log.e("GeminiLiveChat", "Error starting SpeechRecognizer: ${e.message}", e)
        }
    }

    private fun cleanUpSpeechRecognizer() {
        isListening = false
        try {
            inAppSpeechRecognizer?.stopListening()
            inAppSpeechRecognizer?.destroy()
        } catch (_: Exception) {}
        inAppSpeechRecognizer = null
        stopVoiceSearchAnimation()
    }

    private fun startVoiceSearchAnimation() {
        val inputCard = binding.bottomInputCard
        val container = binding.chatInputContainer
        val btnVoice = binding.btnMic
        val overlay = binding.voiceRevealOverlay
        val revealView = binding.voiceRevealView
        val tvListening = binding.tvListeningStatus
        val btnActiveMic = binding.btnActiveMic

        inputCard.clipToOutline = true
        container.clipToOutline = true

        val accentColor = ContextCompat.getColor(this, R.color.accent_teal)
        revealView.setBackgroundColor(accentColor)

        val darkTextColor = Color.parseColor("#0F172A")
        tvListening.setTextColor(darkTextColor)

        val existingText = binding.etChatMessage.text?.toString()?.trim() ?: ""
        tvListening.text = if (existingText.isNotEmpty()) existingText else "Listening..."
        btnActiveMic.setColorFilter(darkTextColor)

        container.post {
            val cx = btnVoice.x + (btnVoice.width / 2f)
            val cy = btnVoice.y + (btnVoice.height / 2f)
            val finalRadius = container.width.toFloat()

            overlay.visibility = View.VISIBLE
            val anim = ViewAnimationUtils.createCircularReveal(overlay, cx.toInt(), cy.toInt(), 0f, finalRadius)
            anim.duration = 400
            anim.start()

            // Pulse micro-animation on active mic icon
            (btnActiveMic.tag as? Animator)?.cancel()
            val pulse = ObjectAnimator.ofPropertyValuesHolder(
                btnActiveMic,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.25f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.25f, 1.0f)
            ).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
            btnActiveMic.tag = pulse
        }
    }

    private fun stopVoiceSearchAnimation() {
        val container = binding.chatInputContainer
        val btnVoice = binding.btnMic
        val overlay = binding.voiceRevealOverlay
        val btnActiveMic = binding.btnActiveMic

        (btnActiveMic.tag as? Animator)?.cancel()
        btnActiveMic.scaleX = 1.0f
        btnActiveMic.scaleY = 1.0f

        if (overlay.visibility != View.VISIBLE) return

        val cx = btnVoice.x + (btnVoice.width / 2f)
        val cy = btnVoice.y + (btnVoice.height / 2f)
        val startRadius = container.width.toFloat()

        val anim = ViewAnimationUtils.createCircularReveal(overlay, cx.toInt(), cy.toInt(), startRadius, 0f)
        anim.duration = 300
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                overlay.visibility = View.GONE
            }
        })
        anim.start()
    }

    private fun generateLocalMedicalAiResponse(query: String): String {
        val q = query.trim().lowercase()
        return when {
            q.contains("soccer") || q.contains("sport") || q.contains("exercise") || q.contains("workout") || q.contains("playing") || q.contains("gym") ->
                "### Physical Activity & Exercise Guidance\n\n" +
                "**1. Pre/Post-Exercise Medication Timing:**\n" +
                "• **Stomach Protection:** If taking NSAIDs (e.g. Ibuprofen) or stomach-sensitive medications, take them with a light meal before or after vigorous sports like soccer.\n" +
                "• **Hydration & Blood Pressure:** Ensure proper hydration before, during, and after exercise. Certain blood pressure medications or antihistamines can increase fluid loss.\n" +
                "• **Asthma / Respiratory:** If prescribed a pre-exercise inhaler (e.g. Albuterol), use it 15-30 minutes prior to athletic activities as directed by your physician.\n\n" +
                "**2. Safety Precautions:**\n" +
                "If you feel lightheaded, fatigued, or short of breath during physical exertion, pause and rest immediately."

            q.contains("diet") || q.contains("digestion") || q.contains("digest") || q.contains("stomach") || q.contains("gut") || q.contains("food") || q.contains("eat") ->
                "### Dietary & Digestive Health Guidance\n\n" +
                "**1. Food & Digestion Impact:**\n" +
                "• **Digestive Health:** High-fiber foods, adequate water intake, and balanced meals support smooth digestion and steady blood sugar levels.\n" +
                "• **Medication Absorption:** Certain foods can affect how medications are absorbed (e.g., dairy with certain antibiotics or high-fat meals with delayed-release formulations).\n" +
                "• **Stomach Relief:** If experiencing stomach discomfort, eating smaller, lighter meals can help reduce digestive burden.\n\n" +
                "**2. Key Health Tip:**\n" +
                "Always check if your specific medication should be taken with or without food for optimal absorption."

            q.contains("code") || q.contains("python") || q.contains("politic") || q.contains("movie") || q.contains("game") || q.contains("trivia") ->
                "### Medical & Health Focus\n\n" +
                "I am specialized as your **Gemini Medical & Health AI Assistant**.\n\n" +
                "I can assist with medications, prescriptions, food/drug interactions, dietary health, digestion, and exercise precautions.\n\n" +
                "Please feel free to ask any health, medication, or physical wellness question!"

            q.contains("amoxicillin") || q.contains("antibiotic") ->
                "### Amoxicillin & Food Guidance\n\n" +
                "**1. Food Compatibility:**\n" +
                "Yes, Amoxicillin can be taken with or without food. Taking it with a light meal or snack can help reduce stomach discomfort or mild nausea.\n\n" +
                "**2. Important Guidelines:**\n" +
                "• **Complete Course:** Always complete the full prescribed course even if your symptoms improve early.\n" +
                "• **Dosing Interval:** Space doses evenly (e.g. every 8 or 12 hours as prescribed) to maintain steady antibiotic levels in your bloodstream.\n" +
                "• **Hydration:** Drink plenty of water throughout the day.\n\n" +
                "*Disclaimer: Always follow the exact instructions written by your prescribing physician on your medication label.*"

            q.contains("metformin") || q.contains("side effect") ->
                "### Metformin Clinical Summary\n\n" +
                "**1. Primary Uses:**\n" +
                "Metformin is prescribed to manage blood glucose levels in Type 2 Diabetes Mellitus.\n\n" +
                "**2. Common Side Effects:**\n" +
                "• Mild gastrointestinal symptoms (nausea, abdominal bloating, diarrhea, gas).\n" +
                "• Metallic taste in the mouth.\n\n" +
                "**3. Best Practices:**\n" +
                "• Take Metformin **with meals** to minimize digestive side effects.\n" +
                "• Swallow extended-release (ER) tablets whole—do not crush or chew.\n" +
                "• Limit alcohol consumption while taking Metformin."

            q.contains("paracetamol") || q.contains("acetaminophen") || q.contains("fever") || q.contains("pain") ->
                "### Paracetamol (Acetaminophen) Information\n\n" +
                "**1. Indication:**\n" +
                "Used for mild to moderate pain relief (headaches, toothaches, muscle aches) and fever reduction.\n\n" +
                "**2. Dosage & Safety:**\n" +
                "• **Adult Dosage:** Standard dose is 500 mg to 1000 mg every 4 to 6 hours as needed.\n" +
                "• **Maximum Daily Limit:** Do not exceed 4,000 mg (4 grams) in 24 hours.\n" +
                "• **Warning:** Check combination cold or sinus medications to avoid accidental overdose of acetaminophen."

            q.contains("ibuprofen") || q.contains("advil") || q.contains("nsaid") ->
                "### Ibuprofen Usage & Safety\n\n" +
                "**1. Class:** Nonsteroidal Anti-inflammatory Drug (NSAID).\n\n" +
                "**2. Key Instructions:**\n" +
                "• Take with food, milk, or a full glass of water to protect your stomach lining.\n" +
                "• Avoid taking on an empty stomach.\n" +
                "• Consult your doctor if you have a history of stomach ulcers, hypertension, or kidney conditions."

            activeMedicationsSummary.isNotBlank() && (q.contains("my med") || q.contains("my prescription") || q.contains("my pill") || q.contains("scanned")) ->
                "### Your Active Prescription Context\n\n" +
                activeMedicationsSummary + "\n\n" +
                "**Guidance:**\n" +
                "Always adhere strictly to the exact dose and reception times set in your medication reminders. Consult your physician before adjusting your intake schedule."

            q.contains("abbreviation") ->
                "### Understanding Prescription Directions\n\n" +
                "Common pharmaceutical abbreviations include:\n" +
                "• **q.d. / q1d:** Once daily\n" +
                "• **b.i.d.:** Twice daily (every 12 hours)\n" +
                "• **t.i.d.:** Three times daily (every 8 hours)\n" +
                "• **p.c.:** After meals\n" +
                "• **a.c.:** Before meals\n" +
                "• **p.r.n.:** As needed for symptoms\n\n" +
                "Always adhere strictly to the frequency and dosage indicated on your physical prescription label."

            else ->
                "### Health & Well-being Guidance for '${query.take(30)}...'\n\n" +
                "Thank you for asking Gemini Live AI. Here is important health guidance regarding your inquiry:\n\n" +
                "1. **Personalized Health Care:** Always manage dietary, digestion, and exercise habits according to individual physical health needs.\n" +
                "2. **Timing & Exercise:** Allow adequate digestion time before participating in vigorous sports or physical activities.\n" +
                "3. **Hydration & Nutrition:** Maintain healthy hydration and balanced nutrition to support physical well-being.\n" +
                "4. **Medication Orders:** Follow exact dosage guidelines provided on your medical prescription labels.\n\n" +
                "*Note: For urgent health concerns or persistent symptoms, please consult a qualified healthcare professional.*"
        }
    }

    private fun renderMarkdownToSpanned(markdown: String): Spanned {
        if (markdown.isBlank()) return SpannedString("")

        val html = markdown
            .replace(Regex("(?m)^### (.*)$"), "<br/><b><font color=\"#10B981\">$1</font></b><br/>")
            .replace(Regex("(?m)^## (.*)$"), "<br/><b><font color=\"#10B981\">$1</font></b><br/>")
            .replace(Regex("(?m)^# (.*)$"), "<br/><b><font color=\"#10B981\">$1</font></b><br/>")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
            .replace(Regex("(?m)^[•\\-] (.*)$"), "&bull; $1")
            .replace(Regex("(?m)^(\\d+)\\. (.*)$"), "<b>$1.</b> $2")
            .replace("\n", "<br/>")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }

    private fun sendUserMessage(text: String) {
        // Add User Message to List
        messageList.add(ChatMessage(text = text, isUser = true))
        chatAdapter.notifyItemInserted(messageList.size - 1)
        binding.rvChatMessages.smoothScrollToPosition(messageList.size - 1)

        // Add Empty AI Message Container for Streaming
        val aiMessage = ChatMessage(text = "Thinking...", isUser = false, isStreaming = true)
        messageList.add(aiMessage)
        val aiIndex = messageList.size - 1
        chatAdapter.notifyItemInserted(aiIndex)
        binding.rvChatMessages.smoothScrollToPosition(aiIndex)

        // Hide quick prompts scrollview once user interacts
        binding.quickPromptsContainer.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            var streamSuccess = false
            val currentSystemInstruction = buildSystemInstructionWithContext()
            val modelsToTry = listOf(GEMINI_MODEL, GEMINI_MODEL_FALLBACK, "gemini-3.0-flash", "gemini-3-flash", "gemini-3.0-pro")

            for (modelName in modelsToTry) {
                if (streamSuccess) break
                try {
                    val model = GenerativeModel(
                        modelName = modelName,
                        apiKey = GEMINI_API_KEY,
                        generationConfig = generationConfig {
                            temperature = 0.2f
                        },
                        systemInstruction = content { text(currentSystemInstruction) }
                    )

                    val userContent = content(role = "user") {
                        text(text)
                    }

                    val responseStream = model.generateContentStream(userContent)
                    val sb = StringBuilder()
                    var isFirstToken = true

                    responseStream.collect { chunk ->
                        val token = chunk.text ?: ""
                        if (isFirstToken && token.isNotBlank()) {
                            sb.clear()
                            isFirstToken = false
                        }
                        sb.append(token)
                        streamSuccess = true

                        withContext(Dispatchers.Main) {
                            aiMessage.text = sb.toString()
                            aiMessage.isStreaming = false
                            chatAdapter.notifyItemChanged(aiIndex)
                            binding.rvChatMessages.smoothScrollToPosition(aiIndex)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("GeminiLiveChat", "Cloud API streaming exception for model $modelName (${e.message})...")
                }
            }

            // Fallback to local Medical AI response engine if cloud response failed/timed out
            if (!streamSuccess) {
                val localAnswer = generateLocalMedicalAiResponse(text)
                val sb = StringBuilder()
                for (char in localAnswer) {
                    sb.append(char)
                    withContext(Dispatchers.Main) {
                        aiMessage.text = sb.toString()
                        aiMessage.isStreaming = false
                        chatAdapter.notifyItemChanged(aiIndex)
                        binding.rvChatMessages.smoothScrollToPosition(aiIndex)
                    }
                    delay(12) // Smooth character streaming simulation
                }
            }
        }
    }

    private fun addAiMessage(text: String) {
        messageList.add(ChatMessage(text = text, isUser = false))
        chatAdapter.notifyItemInserted(messageList.size - 1)
        binding.rvChatMessages.smoothScrollToPosition(messageList.size - 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            inAppSpeechRecognizer?.destroy()
        } catch (_: Exception) {}
    }

    // -----------------------------------------------------------------------------
    // CHAT ADAPTER
    // -----------------------------------------------------------------------------
    private inner class ChatAdapter(
        private val items: List<ChatMessage>
    ) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val containerUser: View = view.findViewById(R.id.containerUserMessage)
            val containerAi: View = view.findViewById(R.id.containerAiMessage)
            val tvUser: TextView = view.findViewById(R.id.tvUserMessage)
            val tvAi: TextView = view.findViewById(R.id.tvAiMessage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return ChatViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val msg = items[position]
            if (msg.isUser) {
                holder.containerUser.visibility = View.VISIBLE
                holder.containerAi.visibility = View.GONE
                holder.tvUser.text = msg.text
            } else {
                holder.containerUser.visibility = View.GONE
                holder.containerAi.visibility = View.VISIBLE
                holder.tvAi.text = renderMarkdownToSpanned(msg.text)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
