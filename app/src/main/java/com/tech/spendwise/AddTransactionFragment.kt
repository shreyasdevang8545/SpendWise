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
import com.tech.spendwise.utils.UIUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentAddTransactionBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val categories by lazy {
        listOf(
            getString(R.string.category_food),
            getString(R.string.category_groceries),
            getString(R.string.category_transport),
            getString(R.string.category_bills),
            getString(R.string.category_health),
            getString(R.string.category_entertainment),
            getString(R.string.category_shopping),
            getString(R.string.category_income),
            getString(R.string.category_investment),
            getString(R.string.category_lend_repayment),
            getString(R.string.category_others)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddTransactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Category dropdown
        val categoryAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            categories
        )
        binding.actvCategory.setAdapter(categoryAdapter)

        // Voice Input Button
        binding.btnVoiceStart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val settingsManager = SettingsManager(requireContext())
                if (settingsManager.isProUser.first()) {
                    showVoiceBottomSheet()
                } else {
                    findNavController().navigate(R.id.premiumFragment)
                    Toast.makeText(requireContext(), "Voice Input is a Pro feature", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Validate on any change
        binding.etAmount.addTextChangedListener(simpleWatcher { validate() })
        binding.actvCategory.addTextChangedListener(simpleWatcher { validate() })
        binding.typeChipGroup.setOnCheckedStateChangeListener { _, _ -> validate() }
        binding.paymentChipGroup.setOnCheckedStateChangeListener { _, checkedIds -> 
            val isCreditSelected = checkedIds.contains(binding.chipCredit.id)
            binding.layoutCreditCardSelection.visibility = if (isCreditSelected) View.VISIBLE else View.GONE
            validate() 
        }

        // Observe Credit Cards
        viewModel.creditCards.observe(viewLifecycleOwner) { cards ->
            binding.creditCardChipGroup.removeAllViews()
            cards.forEach { card ->
                val chip = com.google.android.material.chip.Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                    id = View.generateViewId()
                    text = getString(R.string.label_card_chip, card.name, card.last4)
                    isCheckable = true
                    tag = card.id // Store the actual card ID in the tag
                }

                binding.creditCardChipGroup.addView(chip)
            }
        }

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

        Toast.makeText(requireContext(), getString(R.string.msg_filled_from_voice, transcript), Toast.LENGTH_SHORT).show()
        validate()
    }

    private fun validate() {
        val amountOk = binding.etAmount.text?.toString()?.toDoubleOrNull()?.let { it > 0 } == true
        val categoryOk = binding.actvCategory.text?.toString()?.let { it in categories } == true
        val typeOk = binding.typeChipGroup.checkedChipId != View.NO_ID
        val paymentOk = binding.paymentChipGroup.checkedChipId != View.NO_ID
        val creditCardOk = if (binding.chipCredit.isChecked) {
            binding.creditCardChipGroup.checkedChipId != View.NO_ID
        } else true
        
        binding.btnSave.isEnabled = amountOk && categoryOk && typeOk && paymentOk && creditCardOk
    }


    private fun saveTransaction() {
        val amount = binding.etAmount.text.toString().toDoubleOrNull() ?: return
        val merchant = binding.etMerchant.text.toString().trim()
        val category = binding.actvCategory.text.toString()
        val type = if (binding.chipIncome.isChecked) "CREDIT" else "DEBIT"

        val paymentChip = binding.paymentChipGroup.checkedChipId.let {
            binding.paymentChipGroup.findViewById<com.google.android.material.chip.Chip>(it)
        }
        val paymentMode = paymentChip?.text?.toString() ?: getString(R.string.label_upi)
        
        // Handle Credit Card ID
        var creditCardIdStr = ""
        if (binding.chipCredit.isChecked) {
            val selectedChipId = binding.creditCardChipGroup.checkedChipId
            val selectedChip = binding.creditCardChipGroup.findViewById<com.google.android.material.chip.Chip>(selectedChipId)
            creditCardIdStr = selectedChip?.tag?.toString() ?: ""
        }

        // Recurring transaction status
        val isRecurring = binding.switchRecurring.isChecked

        // Build standard JSON for storage and sync
        val timestamp = storageFormat.format(calendar.time)

        val json = buildString {
            append("{")
            append("\"amount\": $amount, ")
            append("\"merchant\": \"${merchant.ifBlank { getString(R.string.label_unknown) }}\", ")
            append("\"type\": \"$type\", ")
            append("\"category\": \"$category\", ")
            append("\"payment_mode\": \"$paymentMode\", ")
            if (creditCardIdStr.isNotEmpty()) {
                append("\"credit_card_id\": \"$creditCardIdStr\", ")
            }
            append("\"currency\": \"INR\", ")
            append("\"saved_at\": \"$timestamp\", ")
            append("\"source\": \"manual\", ")
            append("\"is_recurring\": $isRecurring")
            append("}")
        }


        // addConfirmedTransaction handles both local save and encrypted Firestore sync
        viewModel.addConfirmedTransaction(json)

        if (isRecurring) {
            ReminderManager.scheduleRecurringTransaction(requireContext(), json)
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.msg_saved_and_synced, amount.toString(), category),
            Toast.LENGTH_SHORT
        ).show()

        findNavController().popBackStack()
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
