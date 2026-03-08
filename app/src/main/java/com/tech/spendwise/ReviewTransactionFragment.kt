package com.tech.spendwise

import android.os.Bundle
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
                binding.saveButton.text = "Save & Next (${count - 1} left)"
            } else {
                binding.saveButton.text = "Save & Finish"
            }
        }

        binding.discardButton.setOnClickListener {
            viewModel.popTransaction()
        }
    }

    private fun setupUI(jsonString: String) {
        // Reset previous selections and errors
        binding.categoryInput.setText("", false)
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

        binding.amountInput.setText(amount)
        binding.amountLayout.hint = "Amount ($currency)"
        binding.merchantInput.setText(if (merchant == "UNKNOWN" || merchant.isBlank()) "" else merchant)

        // Categories
        val categories = arrayOf("Food", "Entertainment", "Shopping", "Transport", "Bills", "Health", "Investment", "Others")
        val categoryAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        binding.categoryInput.setAdapter(categoryAdapter)

        // Payment Modes
        val modes = listOf("UPI", "Credit Card", "Debit Card", "By Cash", "Other")
        binding.paymentGroup.removeAllViews()
        modes.forEach { mode ->
            val chip = Chip(requireContext()).apply {
                text = mode
                isCheckable = true
                setChipBackgroundColorResource(R.color.chip_background)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(24f).build()
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
                    setChipBackgroundColorResource(R.color.chip_background)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(24f).build()
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

        binding.categoryInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = validate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.otherModeInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = validate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.saveButton.setOnClickListener {
            saveTransaction(type)
        }

        validate()
    }

    private fun validate() {
        val categories = listOf("Food", "Entertainment", "Shopping", "Transport", "Bills", "Health", "Investment", "Others")
        val isCategorySelected = binding.categoryInput.text.isNotEmpty() && categories.contains(binding.categoryInput.text.toString())
        val isPaymentSelected = binding.paymentGroup.checkedChipId != View.NO_ID
        val isOtherValid = if (binding.paymentGroup.checkedChipId != View.NO_ID) {
            val selectedChip = binding.paymentGroup.findViewById<Chip>(binding.paymentGroup.checkedChipId)
            if (selectedChip.text == "Other") binding.otherModeInput.text?.isNotEmpty() == true else true
        } else false
        
        val isTypeSelected = if (binding.typeContainer.visibility == View.VISIBLE) {
            binding.typeGroup.checkedChipId != View.NO_ID
        } else true

        binding.saveButton.isEnabled = isCategorySelected && isPaymentSelected && isOtherValid && isTypeSelected
    }

    private fun saveTransaction(initialType: String) {
        val finalAmount = binding.amountInput.text.toString()
        val finalMerchant = binding.merchantInput.text.toString()
        val finalCategory = binding.categoryInput.text.toString()
        
        val selectedModeId = binding.paymentGroup.checkedChipId
        val selectedMode = binding.paymentGroup.findViewById<Chip>(selectedModeId).text.toString()
        val finalPaymentMode = if (selectedMode == "Other") binding.otherModeInput.text.toString() else selectedMode
        
        val finalType = if (initialType == "UNKNOWN") {
            val selectedTypeId = binding.typeGroup.checkedChipId
            if (selectedTypeId != View.NO_ID) binding.typeGroup.findViewById<Chip>(selectedTypeId).text.toString() else "UNKNOWN"
        } else initialType
        
        Toast.makeText(requireContext(), "Saved: $finalAmount as $finalType at $finalMerchant ($finalCategory via $finalPaymentMode)", Toast.LENGTH_LONG).show()
        
        viewModel.popTransaction()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
