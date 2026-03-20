package com.tech.spendwise

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.tech.spendwise.utils.UIUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.firebase.auth.FirebaseAuth
import com.tech.spendwise.databinding.FragmentAddLendBinding
import com.tech.spendwise.models.LendTransaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class AddLendFragment : Fragment() {

    private var _binding: FragmentAddLendBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private val firestoreRepository = FirestoreRepository()
    
    private var selectedReturnDate: Long = 0
    private val dateFormatter = SimpleDateFormat("dd MMM, yyyy", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddLendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val lendId = arguments?.getString("lendId")
        if (lendId != null) {
            binding.toolbar.title = "Edit Lend Detail"
            binding.btnSave.text = "Update Lend Detail"
            loadLend(lendId)
        } else {
            // Initialize with tomorrow's date as default for new lends
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            updateDate(calendar.timeInMillis)
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.etReturnDate.setOnClickListener {
            showDatePicker()
        }

        binding.btnSave.setOnClickListener {
            saveLend(lendId)
        }
    }

    private fun loadLend(lendId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        firestoreRepository.getLendById(uid, lendId) { lend ->
            lend ?: return@getLendById
            binding.etName.setText(lend.name)
            binding.etAmount.setText(lend.amount.toString())
            updateDate(lend.returnDate)
            
            val chipId = when (lend.paymentMode.uppercase()) {
                "UPI" -> R.id.chipUpi
                "CASH" -> R.id.chipCash
                "CARD" -> R.id.chipCard
                else -> R.id.chipOther
            }
            binding.paymentChipGroup.check(chipId)
        }
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        if (selectedReturnDate > 0) calendar.timeInMillis = selectedReturnDate

        val picker = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(year, month, dayOfMonth)
                updateDate(selectedCal.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        // Can't commit to past dates
        picker.datePicker.minDate = System.currentTimeMillis() - 1000
        picker.show()
    }

    private fun updateDate(timestamp: Long) {
        selectedReturnDate = timestamp
        binding.etReturnDate.setText(dateFormatter.format(timestamp))
    }

    private fun saveLend(existingId: String? = null) {
        val name = binding.etName.text.toString().trim()
        val amountStr = binding.etAmount.text.toString().trim()
        val amount = amountStr.toDoubleOrNull() ?: 0.0

        if (name.isEmpty()) {
            binding.nameLayout.error = "Enter person name"
            return
        }
        if (amount <= 0) {
            binding.amountLayout.error = "Enter valid amount"
            return
        }

        val checkedChipId = binding.paymentChipGroup.checkedChipId
        val paymentMode = if (checkedChipId != View.NO_ID) {
            binding.paymentChipGroup.findViewById<Chip>(checkedChipId).text.toString()
        } else "Other"

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            UIUtils.showErrorSnackbar(binding.root, "Please login to save")
            return
        }

        val lendId = existingId ?: UUID.randomUUID().toString()
        val lend = LendTransaction(
            id = lendId,
            name = name,
            amount = amount,
            paymentMode = paymentMode,
            returnDate = selectedReturnDate
        )

        firestoreRepository.saveLend(
            uid, lend,
            onResult = TODO()
        )
        
        // --- ADDED FIX: Also create a transaction record so it shows on Home Screen ---
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        val lendJson = """{"amount":$amount,"type":"DEBIT","merchant":"${name.esc()}","category":"Lend","payment_mode":"${paymentMode.esc()}","currency":"INR","saved_at":"$timestamp","is_lend":true,"lend_name":"${name.esc()}"}"""
        viewModel.addConfirmedTransaction(lendJson)
        // -------------------------------------------------------------------------

        // Schedule reminder notification for the committed date
        ReminderManager.scheduleReminder(
            requireContext(),
            lendId,
            name,
            amount,
            selectedReturnDate
        )
        
        val message = if (existingId != null) "Lend detail updated" else "Lend detail saved for $name"
        UIUtils.showSuccessSnackbar(binding.root, message)
        findNavController().popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
