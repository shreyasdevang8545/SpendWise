package com.tech.spendwise

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentAddCreditCardBinding
import com.tech.spendwise.models.CreditCard
import java.util.UUID

class AddCreditCardFragment : Fragment() {

    private var _binding: FragmentAddCreditCardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddCreditCardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Live Preview Logic
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
            }
        }
        binding.etCardName.addTextChangedListener(watcher)
        binding.etLast4.addTextChangedListener(watcher)
        binding.etLimit.addTextChangedListener(watcher)

        binding.btnSaveCard.setOnClickListener {
            saveCard()
        }
    }

    private fun updatePreview() {
        binding.previewCardName.text = binding.etCardName.text.toString().ifEmpty { "CARD NAME" }.uppercase()
        val last4 = binding.etLast4.text.toString()
        binding.previewCardLast4.text = if (last4.isNotEmpty()) "**** **** **** $last4" else "**** **** **** 1234"
        val limit = binding.etLimit.text.toString().toDoubleOrNull() ?: 0.0
        binding.previewCardLimit.text = "₹ %.2f".format(limit)
    }

    private fun saveCard() {
        val name = binding.etCardName.text.toString().trim()
        val last4 = binding.etLast4.text.toString().trim()
        val limit = binding.etLimit.text.toString().toDoubleOrNull() ?: 0.0
        val paybackDay = binding.etPaybackDay.text.toString().toIntOrNull() ?: 1
        val reminderEnabled = binding.switchReminder.isChecked

        if (name.isEmpty() || last4.length != 4 || limit <= 0 || paybackDay !in 1..31) {
            Toast.makeText(requireContext(), "Please fill all fields correctly", Toast.LENGTH_SHORT).show()
            return
        }

        val card = CreditCard(
            id = UUID.randomUUID().toString(),
            name = name,
            last4 = last4,
            limit = limit,
            paybackDay = paybackDay,
            reminderEnabled = reminderEnabled
        )

        viewModel.saveCreditCard(card)

        if (reminderEnabled) {
            ReminderManager.scheduleCreditCardPayback(requireContext(), card.id!!, card.name, card.paybackDay)
        }

        Toast.makeText(requireContext(), "Credit Card saved securely", Toast.LENGTH_SHORT).show()

        findNavController().popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
