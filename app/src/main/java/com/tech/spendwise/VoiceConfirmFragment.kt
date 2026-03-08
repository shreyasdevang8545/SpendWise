package com.tech.spendwise

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.tech.spendwise.databinding.FragmentVoiceConfirmBinding

/**
 * Pre-filled confirmation / edit screen after voice parsing.
 * Allows the user to review and correct the parsed expense before saving.
 */
class VoiceConfirmFragment : Fragment() {

    private var _binding: FragmentVoiceConfirmBinding? = null
    private val binding get() = _binding!!

    private val args: VoiceConfirmFragmentArgs by navArgs()
    private val viewModel: TransactionViewModel by activityViewModels()

    private val categories = listOf(
        "Food", "Groceries", "Transport", "Bills", "Health",
        "Entertainment", "Shopping", "Income", "Investment", "Others"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoiceConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val parsed = args.parsedExpense
        val transcript = args.transcript

        // Show original transcript
        binding.tvTranscriptPreview.text = "\"$transcript\""

        // Pre-fill amount
        binding.etAmount.setText(
            if (parsed.amount > 0) parsed.amount.toBigDecimal().stripTrailingZeros().toPlainString() else ""
        )

        // Pre-fill merchant
        binding.etMerchant.setText(parsed.merchant.replaceFirstChar { it.uppercase() })

        // Category dropdown
        val categoryAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            categories
        )
        binding.actvCategory.setAdapter(categoryAdapter)
        binding.actvCategory.setText(parsed.category, false)

        // Pre-select type chip
        when (parsed.type) {
            "income" -> binding.chipIncome.isChecked = true
            else     -> binding.chipExpense.isChecked = true
        }

        // Chip styling
        listOf(
            binding.chipExpense, binding.chipIncome,
            binding.chipUpi, binding.chipCredit, binding.chipDebit,
            binding.chipCash, binding.chipOtherMode
        ).forEach { chip ->
            chip.setChipBackgroundColorResource(R.color.chip_background)
        }

        // Validate on any change
        binding.etAmount.addTextChangedListener(simpleWatcher { validate() })
        binding.actvCategory.addTextChangedListener(simpleWatcher { validate() })
        binding.typeChipGroup.setOnCheckedStateChangeListener { _, _ -> validate() }
        binding.paymentChipGroup.setOnCheckedStateChangeListener { _, _ -> validate() }

        validate()

        // Re-record
        binding.btnReRecord.setOnClickListener {
            findNavController().popBackStack()
        }

        // Save
        binding.btnSave.setOnClickListener { saveExpense() }
    }

    private fun validate() {
        val amountOk = binding.etAmount.text?.toString()?.toDoubleOrNull()?.let { it > 0 } == true
        val categoryOk = binding.actvCategory.text?.toString()?.let { it in categories } == true
        val typeOk = binding.typeChipGroup.checkedChipId != View.NO_ID
        val paymentOk = binding.paymentChipGroup.checkedChipId != View.NO_ID
        binding.btnSave.isEnabled = amountOk && categoryOk && typeOk && paymentOk
    }

    private fun saveExpense() {
        val amount = binding.etAmount.text.toString().toDoubleOrNull() ?: return
        val merchant = binding.etMerchant.text.toString().trim()
        val category = binding.actvCategory.text.toString()
        val type = if (binding.chipIncome.isChecked) "CREDIT" else "DEBIT"

        val paymentChip = binding.paymentChipGroup.checkedChipId.let {
            binding.paymentChipGroup.findViewById<com.google.android.material.chip.Chip>(it)
        }
        val paymentMode = paymentChip?.text?.toString() ?: "UPI"

        // Build JSON compatible with ReviewTransactionFragment / ViewModel
        val json = buildString {
            append("{")
            append("\"amount\": \"$amount\", ")
            append("\"entity\": \"${merchant.ifBlank { category }}\", ")
            append("\"type\": \"$type\", ")
            append("\"category\": \"$category\", ")
            append("\"paymentMode\": \"$paymentMode\", ")
            append("\"currency\": \"INR\", ")
            append("\"source\": \"voice\"")
            append("}")
        }

        viewModel.addTransaction(json)

        Toast.makeText(
            requireContext(),
            "Saved: ₹$amount · $category · $type",
            Toast.LENGTH_SHORT
        ).show()

        // Navigate to review so user can see the full detail screen
        findNavController().navigate(R.id.reviewTransactionFragment)
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
