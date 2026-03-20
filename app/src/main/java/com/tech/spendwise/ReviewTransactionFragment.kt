package com.tech.spendwise

import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentReviewTransactionBinding

/**
 * Dedicated screen for reviewing and saving transaction details.
 */
class ReviewTransactionFragment : Fragment() {

    private var _binding: FragmentReviewTransactionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReviewTransactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.firstTransaction.observe(viewLifecycleOwner) { json ->
            if (json != null) {
                setupUI(json)
            } else {
                // If queue is empty, return to home
                if (findNavController().currentDestination?.id == R.id.reviewTransactionFragment) {
                    findNavController().popBackStack()
                }
            }
        }

        viewModel.pendingTransactions.observe(viewLifecycleOwner) { list ->
            val count = list.size
            if (count > 1) {
                binding.btnSave.text = "Save & Next (${count - 1} left)"
            } else {
                binding.btnSave.text = "Save & Finish"
            }
        }

        binding.btnDiscard.setOnClickListener {
            viewModel.popTransaction()
        }

        binding.toolbar.setNavigationOnClickListener {
            // Act like discard/close for now to handle it gracefully
            viewModel.popTransaction()
        }
    }

    private fun setupUI(jsonString: String) {
        // Reset previous selections and errors
        binding.actvCategory.setText("", false)
        binding.paymentGroup.clearCheck()
        binding.otherModeLayout.visibility = View.GONE
        binding.typeContainer.visibility = View.GONE
        binding.typeBadge.visibility = View.GONE

        val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
        val data = mutableMapOf<String, String>()
        for (match in pattern.findAll(jsonString)) {
            val key = match.groupValues[1]
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            data[key] = value
        }

        val amount = data["amount"] ?: "0.0"
        val merchant = data["entity"] ?: ""
        val type = data["type"] ?: "UNKNOWN"
        val currency = data["currency"] ?: "INR"

        binding.etAmount.setText(amount)
        binding.amountLayout.hint = "Amount ($currency)"
        binding.etMerchant.setText(if (merchant == "UNKNOWN" || merchant.isBlank()) "" else merchant)

        // Categories
        val categories = arrayOf("Food", "Entertainment", "Shopping", "Transport", "Bills", "Health", "Investment", "Others")
        val categoryAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        binding.actvCategory.setAdapter(categoryAdapter)

        // Payment Modes
        val modes = listOf("UPI", "Credit Card", "Debit Card", "Cash", "Other")
        binding.paymentGroup.removeAllViews()
        modes.forEach { mode ->
            val chip = Chip(requireContext()).apply {
                text = mode
                isCheckable = true
            }
            binding.paymentGroup.addView(chip)
        }

        binding.paymentGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val selectedChip = group.findViewById<Chip>(checkedIds[0])
                if (selectedChip.text == "Other") {
                    binding.otherModeLayout.visibility = View.VISIBLE
                } else {
                    binding.otherModeLayout.visibility = View.GONE
                }
            }
            validate()
        }

        // Transaction Type
        if (type == "UNKNOWN") {
            binding.typeContainer.visibility = View.VISIBLE
            binding.typeGroup.removeAllViews()
            listOf("CREDIT", "DEBIT").forEach { t ->
                val chip = Chip(requireContext()).apply {
                    text = t
                    isCheckable = true
                }
                binding.typeGroup.addView(chip)
            }
            binding.typeGroup.setOnCheckedStateChangeListener { _, _ -> validate() }
        } else {
            binding.typeBadge.visibility = View.VISIBLE
            binding.typeBadge.text = type
            binding.typeBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            
            // Set background with rounded corners for the badge
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(ContextCompat.getColor(requireContext(), if (type == "CREDIT") R.color.primary_green else R.color.error_red))
            }
            binding.typeBadge.background = drawable
        }

        binding.switchLend.setOnCheckedChangeListener { _, isChecked ->
            binding.lendDetailContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            validate()
        }

        binding.etLendName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = validate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnSave.setOnClickListener {
            saveTransaction(type)
        }

        validate()
    }

    private fun validate() {
        val categories = listOf("Food", "Entertainment", "Shopping", "Transport", "Bills", "Health", "Investment", "Others")
        val isCategorySelected = binding.actvCategory.text.isNotEmpty() && categories.contains(binding.actvCategory.text.toString())
        val isPaymentSelected = binding.paymentGroup.checkedChipId != View.NO_ID
        val isOtherValid = if (binding.paymentGroup.checkedChipId != View.NO_ID) {
            val selectedChip = binding.paymentGroup.findViewById<Chip>(binding.paymentGroup.checkedChipId)
            if (selectedChip.text == "Other") binding.etOtherMode.text?.isNotEmpty() == true else true
        } else false
        
        val isTypeSelected = if (binding.typeContainer.visibility == View.VISIBLE) {
            binding.typeGroup.checkedChipId != View.NO_ID
        } else true

        val isLendValid = if (binding.switchLend.isChecked) {
            binding.etLendName.text?.isNotEmpty() == true
        } else true

        binding.btnSave.isEnabled = isCategorySelected && isPaymentSelected && isOtherValid && isTypeSelected && isLendValid
    }

    private fun saveTransaction(initialType: String) {
        val finalAmount = binding.etAmount.text.toString()
        val finalMerchant = binding.etMerchant.text.toString()
        val finalCategory = binding.actvCategory.text.toString()

        val selectedModeId = binding.paymentGroup.checkedChipId
        val selectedMode = binding.paymentGroup.findViewById<Chip>(selectedModeId).text.toString()
        val finalPaymentMode = if (selectedMode == "Other") binding.etOtherMode.text.toString() else selectedMode

        val finalType = if (initialType == "UNKNOWN") {
            val selectedTypeId = binding.typeGroup.checkedChipId
            if (selectedTypeId != View.NO_ID) binding.typeGroup.findViewById<Chip>(selectedTypeId).text.toString() else "UNKNOWN"
        } else initialType

        // Determine currency from the current pending transaction
        val firstJson = viewModel.firstTransaction.value ?: ""
        val currencyMatch = Regex(""""currency"\s*:\s*"([^"]+)"""").find(firstJson)
        val currency = currencyMatch?.groupValues?.get(1) ?: "INR"

        val isLend = binding.switchLend.isChecked
        val lendName = if (isLend) binding.etLendName.text.toString() else null

        // Build and persist the confirmed transaction JSON
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val confirmedJson = buildConfirmedJson(
            amount = finalAmount.toDoubleOrNull() ?: 0.0,
            type = finalType,
            merchant = finalMerchant,
            category = finalCategory,
            paymentMode = finalPaymentMode,
            currency = currency,
            savedAt = timestamp,
            isLend = isLend,
            lendName = lendName
        )
        viewModel.addConfirmedTransaction(confirmedJson)

        if (isLend) {
            // Also create a separate lend record for the Lend History screen
            val lend = com.tech.spendwise.models.LendTransaction(
                id = java.util.UUID.randomUUID().toString(),
                name = lendName!!,
                amount = finalAmount.toDoubleOrNull() ?: 0.0,
                paymentMode = finalPaymentMode,
                returnDate = System.currentTimeMillis() + (86400000 * 7), // Default 1 week
                isReturned = false
            )
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                FirestoreRepository().saveLend(uid, lend) { result ->
                    result.onFailure { e ->
                        Log.e("ReviewTransaction", "Failed to save lend record: ${e.message}")
                    }
                }
            }
        }

        UIUtils.showSuccessSnackbar(
            binding.root,
            if (isLend) "Lend saved for $lendName" else "Saved: ₹$finalAmount as $finalType at $finalMerchant"
        )

        viewModel.popTransaction()
    }

    private fun buildConfirmedJson(
        amount: Double,
        type: String,
        merchant: String,
        category: String,
        paymentMode: String,
        currency: String,
        savedAt: String,
        isLend: Boolean,
        lendName: String?
    ): String {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val lendStr = if (isLend) ""","is_lend":true,"lend_name":"${lendName?.esc() ?: ""}" """ else ""
        return """{"amount":"$amount","type":"${type.esc()}","merchant":"${merchant.esc()}","category":"${category.esc()}","payment_mode":"${paymentMode.esc()}","currency":"${currency.esc()}","saved_at":"${savedAt.esc()}"$lendStr}"""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
