package com.tech.spendwise

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tech.spendwise.utils.UIUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentAddTransactionBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Manual transaction entry screen.
 * Users can type details or use the minimal voice UI to auto-fill.
 */
class AddTransactionFragment : Fragment() {

    private var _binding: FragmentAddTransactionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private val calendar = Calendar.getInstance()
    private val displayFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    private val storageFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    private val categories = listOf(
        "Food", "Groceries", "Transport", "Bills", "Health",
        "Entertainment", "Shopping", "Income", "Investment", "Lend Repayment", "Others"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddTransactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Camera Launcher for Bill Capture
        val takePictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val imageBitmap = result.data?.extras?.get("data") as? Bitmap
                if (imageBitmap != null) {
                    processBillImage(imageBitmap)
                }
            }
        }

        binding.amountLayout.setStartIconOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            takePictureLauncher.launch(intent)
        }

        // Category dropdown
        val categoryAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            categories
        )
        binding.actvCategory.setAdapter(categoryAdapter)

        // Voice Input Button
        binding.btnVoiceStart.setOnClickListener {
            showVoiceBottomSheet()
        }

        // Validate on any change
        binding.etAmount.addTextChangedListener(simpleWatcher { validate() })
        binding.actvCategory.addTextChangedListener(simpleWatcher { validate() })
        binding.typeChipGroup.setOnCheckedStateChangeListener { _, _ -> validate() }
        binding.paymentChipGroup.setOnCheckedStateChangeListener { _, _ -> validate() }

        validate()

        // Date & Time Picker
        updateDateDisplay()
        binding.etDate.setOnClickListener { showDateTimePicker() }

        // Save
        binding.btnSave.setOnClickListener { saveTransaction() }
    }

    private fun updateDateDisplay() {
        binding.etDate.setText(displayFormat.format(calendar.time))
    }

    private fun showDateTimePicker() {
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        updateDateDisplay()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    false
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showVoiceBottomSheet() {
        val bottomSheet = VoiceBottomSheetFragment { parsed, transcript ->
            fillFromVoice(parsed, transcript)
        }
        bottomSheet.show(childFragmentManager, "VoiceBottomSheet")
    }

    private fun fillFromVoice(parsed: ParsedExpense, transcript: String) {
        if (parsed.amount > 0) {
            binding.etAmount.setText(parsed.amount.toBigDecimal().stripTrailingZeros().toPlainString())
        }
        if (parsed.merchant.isNotBlank()) {
            binding.etMerchant.setText(parsed.merchant.replaceFirstChar { it.uppercase() })
        }
        if (parsed.category.isNotBlank() && parsed.category in categories) {
            binding.actvCategory.setText(parsed.category, false)
        }
        
        when (parsed.type) {
            "income" -> binding.chipIncome.isChecked = true
            else     -> binding.chipExpense.isChecked = true
        }

        Toast.makeText(requireContext(), "Filled from: \"$transcript\"", Toast.LENGTH_SHORT).show()
        validate()
    }

    private fun validate() {
        val amountOk = binding.etAmount.text?.toString()?.toDoubleOrNull()?.let { it > 0 } == true
        val categoryOk = binding.actvCategory.text?.toString()?.let { it in categories } == true
        val typeOk = binding.typeChipGroup.checkedChipId != View.NO_ID
        val paymentOk = binding.paymentChipGroup.checkedChipId != View.NO_ID
        binding.btnSave.isEnabled = amountOk && categoryOk && typeOk && paymentOk
    }

    private fun saveTransaction() {
        val amount = binding.etAmount.text.toString().toDoubleOrNull() ?: return
        val merchant = binding.etMerchant.text.toString().trim()
        val category = binding.actvCategory.text.toString()
        val type = if (binding.chipIncome.isChecked) "CREDIT" else "DEBIT"

        val paymentChip = binding.paymentChipGroup.checkedChipId.let {
            binding.paymentChipGroup.findViewById<com.google.android.material.chip.Chip>(it)
        }
        val paymentMode = paymentChip?.text?.toString() ?: "UPI"

        // Build standard JSON for storage and sync
        val timestamp = storageFormat.format(calendar.time)

        val json = buildString {
            append("{")
            append("\"amount\": $amount, ")
            append("\"merchant\": \"${merchant.ifBlank { "Unknown" }}\", ")
            append("\"type\": \"$type\", ")
            append("\"category\": \"$category\", ")
            append("\"payment_mode\": \"$paymentMode\", ")
            append("\"currency\": \"INR\", ")
            append("\"saved_at\": \"$timestamp\", ")
            append("\"source\": \"manual\"")
            append("}")
        }

        // addConfirmedTransaction handles both local save and encrypted Firestore sync
        viewModel.addConfirmedTransaction(json)

        Toast.makeText(
            requireContext(),
            "Saved and synced: ₹$amount · $category",
            Toast.LENGTH_SHORT
        ).show()

        findNavController().popBackStack()
    }

    private fun processBillImage(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        // Show progress indicator
        binding.progressIndicator.visibility = View.VISIBLE
        binding.amountLayout.isEnabled = false
        
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                binding.progressIndicator.visibility = View.GONE
                binding.amountLayout.isEnabled = true
                
                if (visionText.text.isBlank()) {
                    showError("No text found in the image. Please try again with a clearer photo.")
                } else {
                    extractDetailsFromText(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                binding.progressIndicator.visibility = View.GONE
                binding.amountLayout.isEnabled = true
                UIUtils.showErrorSnackbar(binding.root, "Analysis failed: ${e.localizedMessage ?: "Unknown error"}")
            }
    }

    private fun showError(message: String) {
        UIUtils.showErrorSnackbar(binding.root, message)
    }

    private fun extractDetailsFromText(text: String) {
        // ... previous extraction logic ...
        val amountRegex = Regex("""(?i)(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{2})?)""")
        val matches = amountRegex.findAll(text)
        
        val amounts = matches.mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
        val finalAmount = if (amounts.isNotEmpty()) amounts.maxOrNull() else {
            val simpleAmountRegex = Regex("""\b\d+[\.,]\d{2}\b""")
            val fallbackMatches = simpleAmountRegex.findAll(text)
            fallbackMatches.mapNotNull { it.value.replace(",", ".").toDoubleOrNull() }.maxOrNull()
        }

        val lines = text.lines().filter { it.isNotBlank() }
        val merchant = lines.firstOrNull { line ->
            !line.contains(Regex("""\d""")) && line.length > 3
        } ?: lines.firstOrNull()?.take(20)

        if (finalAmount == null && merchant == null) {
            showError("Could not extract any transaction details. Please fill manually or try again.")
            return
        }

        // 3. Update UI
        if (finalAmount != null && finalAmount > 0) {
            binding.etAmount.setText("%.2f".format(finalAmount))
        }
        
        binding.etMerchant.setText(merchant?.trim() ?: "")
        
        val lowerMerchant = (merchant ?: "Others").lowercase()
        val category = when {
            lowerMerchant.contains("coffee") || lowerMerchant.contains("starbucks") -> "Food"
            lowerMerchant.contains("taxi") || lowerMerchant.contains("uber") || lowerMerchant.contains("ola") -> "Transport"
            lowerMerchant.contains("mart") || lowerMerchant.contains("store") -> "Groceries"
            else -> "Others"
        }
        binding.actvCategory.setText(category, false)
        binding.chipExpense.isChecked = true

        validate()
        UIUtils.showSuccessSnackbar(binding.root, "Bill details extracted!")
    }


    private fun simpleWatcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = after()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
