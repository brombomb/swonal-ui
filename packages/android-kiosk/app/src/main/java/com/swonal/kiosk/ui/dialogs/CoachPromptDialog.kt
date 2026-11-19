package com.swonal.kiosk.ui.dialogs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Native Voice & Text AI Coach Dialog:
 * - Uses `ActivityResultContracts.RequestPermission()` so voice capture starts immediately after grant
 * - Uses `repeatOnLifecycle(STARTED)` so TTS never speaks while backgrounded
 * - Queues TTS until `OnInitListener(SUCCESS)` completes (and avoids replaying stale replies on reopen)
 */
class CoachPromptDialog : BaseSwonalDialog() {

    override val maxDialogWidthDp: Int = 500

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var lastSpokenReply: String? = null
    private var listening = false
    private var promptInputRef: EditText? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val input = promptInputRef ?: return@registerForActivityResult
        if (granted) {
            startVoiceListening(input)
        } else {
            input.hint = "Microphone permission denied"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val p = palette
        val d = resources.displayMetrics.density
        val root = createCardRoot(20)
        val initialState = repo.uiStateFlow.value
        val providerName = (initialState.coachConfig?.provider ?: "LOCAL").uppercase()
        root.addView(createDialogHeader("AI Voice Coach ($providerName)"))

        // Don't replay an old reply automatically when reopening the dialog
        lastSpokenReply = initialState.coachReply

        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
                tts?.setSpeechRate(1.04f)
                val queued = pendingSpeech
                pendingSpeech = null
                if (!queued.isNullOrBlank() && isAdded) {
                    tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "swonal_coach")
                }
            }
        }

        val replyBox = TextView(requireContext()).apply {
            text = initialState.coachReply
                ?: "Ask about your PRs, form cues, fatigue drop, or say 'Add 5 lbs'."
            textSize = 14f
            setTextColor(p.textPrimary)
            background = p.elevatedPillDrawable(d, 14f, interactive = false)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(replyBox)

        val actionBtn = makeAccentButton("") {
            val act = repo.uiStateFlow.value.coachAction ?: return@makeAccentButton
            repo.executeCoachAction(act)
            dismiss()
        }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(actionBtn)

        val quickRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        listOf("Add 5 lbs", "Form cue?", "My PR?").forEachIndexed { idx, promptText ->
            quickRow.addView(makePillButton(promptText) {
                repo.askCoach(promptText)
            }.apply {
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (idx < 2) marginEnd = dp(6)
                }
            })
        }
        root.addView(quickRow)

        val inputRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val promptInput = makeInput("Ask Coach...").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
        }
        promptInputRef = promptInput

        val micBtn = makePillButton("Voice") {
            toggleVoiceInput(promptInput)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                marginEnd = dp(8)
            }
        }
        val sendBtn = makeAccentButton("Send") {
            val txt = promptInput.text.toString().trim()
            if (txt.isNotEmpty()) {
                repo.askCoach(txt)
                promptInput.setText("")
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        }
        inputRow.addView(promptInput)
        inputRow.addView(micBtn)
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.uiStateFlow.collect { st ->
                    if (st.coachLoading) {
                        replyBox.text = "Thinking..."
                    } else if (!st.coachReply.isNullOrBlank()) {
                        replyBox.text = st.coachReply
                        if (st.coachReply != lastSpokenReply && !st.soundMuted) {
                            lastSpokenReply = st.coachReply
                            if (ttsReady) {
                                tts?.speak(st.coachReply, TextToSpeech.QUEUE_FLUSH, null, "swonal_coach")
                            } else {
                                pendingSpeech = st.coachReply
                            }
                        }
                    }
                    // Display ONLY repository-sanitized action label (never raw untrusted LLM label)
                    val act = st.coachAction
                    if (act != null && act.label.isNotBlank()) {
                        actionBtn.text = "Apply: ${act.label}"
                        actionBtn.visibility = View.VISIBLE
                    } else {
                        actionBtn.visibility = View.GONE
                    }
                }
            }
        }

        return root
    }

    private fun toggleVoiceInput(targetInput: EditText) {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            targetInput.hint = "Voice recognition unavailable on device"
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (listening) {
            speechRecognizer?.stopListening()
            listening = false
            return
        }
        startVoiceListening(targetInput)
    }

    private fun startVoiceListening(targetInput: EditText) {
        val sr = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(requireContext()).also {
            speechRecognizer = it
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                targetInput.hint = "Listening..."
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                targetInput.hint = "Ask Coach..."
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    targetInput.setText(spoken)
                    repo.askCoach(spoken)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        sr.startListening(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        promptInputRef = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
