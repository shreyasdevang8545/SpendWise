package com.tech.spendwise

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.tech.spendwise.databinding.FragmentProfileBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()

    private val autoScrollHandler = Handler(Looper.getMainLooper())


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUserDetails()
        setupMenuOptions()
        setupClickListeners()
        setupSummaryObservers()
    }

    private fun setupUserDetails() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            val name = user.displayName
            if (name.isNullOrEmpty()) {
                binding.userName.text = "NAME NOT PROVIDED"
                binding.userHandle.text = "Add a name to see your handle"
                binding.profileInitials.text = "?"
            } else {
                binding.userName.text = name
                
                // Generate handle: lowercase name without spaces
                val handle = "@${name.lowercase(Locale.ROOT).replace(" ", "")}"
                binding.userHandle.text = "$handle · SpendWise"

                // Set initials
                binding.profileInitials.text = name.trim().split(" ").let {
                    if (it.size >= 2 && it[0].isNotEmpty() && it[1].isNotEmpty()) {
                        "${it[0][0]}${it[1][0]}".uppercase()
                    } else if (it.isNotEmpty() && it[0].isNotEmpty()) {
                        it[0].take(2).uppercase()
                    } else "?"
                }
            }
        }
    }


    private fun setupMenuOptions() {
        // Transaction History
        binding.itemHistory.apply {
            optionIcon.setImageResource(R.drawable.ic_history)
            optionTitle.text = "Transaction History"
        }

        // Lend History
        binding.itemLendHistory.apply {
            optionIcon.setImageResource(R.drawable.ic_lend)
            optionTitle.text = "Lend History"
        }

        // Sync Settings
        binding.itemSync.apply {
            optionIcon.setImageResource(R.drawable.ic_sync)
            optionTitle.text = "Cloud Synchronization"
        }

        // Support
        binding.itemSupport.apply {
            optionIcon.setImageResource(R.drawable.ic_help)
            optionTitle.text = "Help & Support"
        }
    }

    private fun setupClickListeners() {
        binding.itemHistory.root.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_transactionHistory)
        }

        binding.itemLendHistory.root.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_lendHistory)
        }

        binding.itemSync.root.setOnClickListener {
            Toast.makeText(requireContext(), "Cloud Sync is active", Toast.LENGTH_SHORT).show()
        }

        binding.itemSupport.root.setOnClickListener {
            Toast.makeText(requireContext(), "Support coming soon!", Toast.LENGTH_SHORT).show()
        }

        binding.logoutButton.setOnClickListener {
            (activity as? MainActivity)?.signOut()
        }

        binding.editNameBtn.setOnClickListener {
            showEditNameDialog()
        }
    }

    private fun showEditNameDialog() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_name, null)
        val editNameField = dialogView.findViewById<TextInputEditText>(R.id.editName)
        
        editNameField.setText(user.displayName)

        AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
            .setTitle("Update Name")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newName = editNameField.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(newName)
                        .build()

                    user.updateProfile(profileUpdates)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                setupUserDetails()
                                Toast.makeText(requireContext(), "Name updated successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Failed to update name", Toast.LENGTH_SHORT).show()
                            }
                        }
                } else {
                    Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSummaryObservers() {
        viewModel.firestoreTransactions.observe(viewLifecycleOwner) { refreshSummary() }
        viewModel.confirmedTransactions.observe(viewLifecycleOwner) { refreshSummary() }
    }

    private fun refreshSummary() {
        val cloudList = viewModel.firestoreTransactions.value ?: emptyList()
        val localList = viewModel.confirmedTransactions.value ?: emptyList()
        
        // Deduplicate and calculate totals
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<String>()
        for (json in cloudList) {
            val savedAt = parseSimpleJson(json)["saved_at"] ?: json
            if (seen.add(savedAt)) merged.add(json)
        }
        for (json in localList) {
            val savedAt = parseSimpleJson(json)["saved_at"] ?: json
            if (seen.add(savedAt)) merged.add(json)
        }

        val cal = Calendar.getInstance()
        val currentYear = cal.get(Calendar.YEAR)
        val currentMonth = cal.get(Calendar.MONTH) + 1

        var totalIncome = 0.0
        var totalSpent = 0.0

        merged.forEach { json ->
            val data = parseSimpleJson(json)
            val type = data["type"] ?: ""
            val savedAt = data["saved_at"] ?: ""
            val amount = data["amount"]?.toDoubleOrNull() ?: 0.0

            if (isSameMonth(savedAt, currentYear, currentMonth)) {
                if (type.equals("CREDIT", ignoreCase = true)) totalIncome += amount
                else if (type.equals("DEBIT", ignoreCase = true)) totalSpent += amount
            }
        }
    }

    private fun parseSimpleJson(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
        for (match in pattern.findAll(json)) {
            val key = match.groupValues[1]
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            result[key] = value
        }
        return result
    }

    private fun isSameMonth(savedAt: String, year: Int, month: Int): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = sdf.parse(savedAt) ?: return false
            val cal = Calendar.getInstance().apply { time = date }
            cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) + 1 == month
        } catch (_: Exception) { false }
    }

    private fun formatAmount(total: Double, firstJson: String?): String {
        val currency = if (firstJson != null) parseSimpleJson(firstJson)["currency"] ?: "INR" else "INR"
        val symbol = if (currency == "INR") "₹" else "$currency "
        val fmt = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "$symbol${fmt.format(total)}"
    }

    override fun onDestroyView() {
        autoScrollHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
        _binding = null
    }
}
