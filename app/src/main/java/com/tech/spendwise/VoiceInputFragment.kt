package com.tech.spendwise

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.tech.spendwise.SettingsManager
import com.tech.spendwise.databinding.FragmentVoiceInputBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Mic screen — starts SpeechRecognizer when the user taps the mic button
 * (or automatically if opened via deep link with autoStart = true).
 */
class VoiceInputFragment : Fragment() {

    private var _binding: FragmentVoiceInputBinding? = null
    private val binding get() = _binding!!

    private val args: VoiceInputFragmentArgs by navArgs()

    private var speechRecognizer: SpeechRecognizer? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var isListening = false

    // ── Permission launcher ─────────────────────────────────────────────────
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showStatus("Microphone permission required")
        }

    // ── Lifecycle ───────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoiceInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.root.visibility = View.INVISIBLE

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.btnCancel.setOnClickListener { findNavController().popBackStack() }

        viewLifecycleOwner.lifecycleScope.launch {
            val settingsManager = SettingsManager(requireContext())
            settingsManager.isProUser.collect { isPro ->
                if (!isPro) {
                    val navOptions = androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.voiceInputFragment, true)
                        .build()
                    findNavController().navigate(R.id.premiumFragment, null, navOptions)
                } else {
                    binding.root.visibility = View.VISIBLE
                    initializeVoiceFeatures()
                }
            }
        }
    }

    private fun initializeVoiceFeatures() {
        binding.btnMic.setOnClickListener {
            if (isListening) stopListening() else checkPermissionAndListen()
        }

        if (args.autoStart) {
            checkPermissionAndListen()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        destroySpeechRecognizer()
        pulseAnimator?.cancel()
        _binding = null
    }

    // ── Permission ──────────────────────────────────────────────────────────
    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── SpeechRecognizer ────────────────────────────────────────────────────
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            showStatus("Speech recognition not available on this device")
            return
        }

        destroySpeechRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).apply {
            setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)

        isListening = true
        showStatus("Listening…")
        startPulse()
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        stopPulse()
        showStatus("Processing…")
    }

    private fun destroySpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ── RecognitionListener ─────────────────────────────────────────────────
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            showStatus("Listening…")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // Scale the inner ring slightly with volume
            val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * 0.3f
            binding.micRingInner.scaleX = scale
            binding.micRingInner.scaleY = scale
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            stopPulse()
            showStatus("Processing…")
        }

        override fun onError(error: Int) {
            isListening = false
            stopPulse()
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Couldn't understand. Tap mic to try again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap mic to try again."
                SpeechRecognizer.ERROR_AUDIO -> "Audio error. Please retry."
                else -> "Error ($error). Tap mic to retry."
            }
            showStatus(msg)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            handleResult(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) {
                binding.tvTranscriptText.text = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Result handling ─────────────────────────────────────────────────────
    private fun handleResult(text: String) {
        binding.tvTranscriptText.text = text
        showStatus("Got it!")
    }

    // ── Animations ──────────────────────────────────────────────────────────
    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.micRingOuter,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0.2f, 0.5f, 0.2f)
        ).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        binding.micRingOuter.scaleX = 1f
        binding.micRingOuter.scaleY = 1f
        binding.micRingOuter.alpha = 0.2f
    }

    private fun showStatus(message: String) {
        binding.tvStatus.text = message
    }
}
