package com.tech.spendwise

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tech.spendwise.databinding.BottomSheetVoiceBinding
import java.util.Locale

/**
 * Minimal voice UI for quick expense entry within the Add screen.
 */
class VoiceBottomSheetFragment(
    private val onResult: (ParsedExpense, String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetVoiceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransactionViewModel by activityViewModels()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var pulseAnimator: ObjectAnimator? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else binding.tvVoiceStatus.text = "Permission denied"
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetVoiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fabVoiceMic.setOnClickListener {
            if (isListening) stopListening() else checkPermissionAndListen()
        }

        binding.fabVoiceMic.setOnClickListener {
            if (isListening) stopListening() else checkPermissionAndListen()
        }

        checkPermissionAndListen()
    }

    private fun checkPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            binding.tvVoiceStatus.text = "Error: Not Available"
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).apply {
            setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)

        isListening = true
        binding.tvVoiceStatus.text = "Listening…"
        startPulseAnimation()
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        stopPulseAnimation()
    }

    private fun startPulseAnimation() {
        if (pulseAnimator == null) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f, 1f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f, 1f)
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.fabVoiceMic, scaleX, scaleY).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        pulseAnimator?.start()
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        binding.fabVoiceMic.scaleX = 1f
        binding.fabVoiceMic.scaleY = 1f
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListening = false
            binding.tvVoiceStatus.text = "Processing…"
        }

        override fun onError(error: Int) {
            isListening = false
            binding.tvVoiceStatus.text = "Error ($error). Try again?"
            stopPulseAnimation()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            val parsed = VoiceExpenseParser.parse(text)
            
            onResult(parsed, text)
            dismiss()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrBlank()) {
                binding.tvVoiceTranscript.text = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechRecognizer?.destroy()
        _binding = null
    }
}
