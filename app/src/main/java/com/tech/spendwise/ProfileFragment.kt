package com.tech.spendwise

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText
import com.tech.spendwise.SupabaseInstance
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
        if (SupabaseInstance.isLoggedIn()) {
            val name = SupabaseInstance.currentUserDisplayName()
            if (name.isNullOrEmpty()) {
                binding.userName.text = getString(R.string.error_name_not_provided)
                binding.userHandle.text = getString(R.string.label_add_name_for_handle)
                binding.profileInitials.text = "?"
            } else {
                binding.userName.text = name
                
                // Generate handle: lowercase name without spaces
                val handle = "@${name.lowercase(Locale.ROOT).replace(" ", "")}"
                binding.userHandle.text = getString(R.string.label_handle_format, handle, getString(R.string.app_name))

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
            optionTitle.text = getString(R.string.title_transaction_history)
        }

        // Lend History
        binding.itemLendHistory.apply {
            optionIcon.setImageResource(R.drawable.ic_lend)
            optionTitle.text = getString(R.string.title_lend_history)
        }
    }

    private fun setupClickListeners() {
        binding.itemHistory.root.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_transactionHistory)
        }

        binding.itemLendHistory.root.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_lendHistory)
        }

        binding.logoutButton.setOnClickListener {
            (activity as? MainActivity)?.signOut()
        }

        binding.editNameBtn.setOnClickListener {
            showEditNameDialog()
        }
        binding.userName.setOnClickListener {
            showEditNameDialog()
        }
    }

    private fun showEditNameDialog() {
        val name = SupabaseInstance.currentUserDisplayName() ?: ""
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_name, null)
        val editNameField = dialogView.findViewById<TextInputEditText>(R.id.editName)
        
        editNameField.setText(name)

        AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
            .setTitle(getString(R.string.title_update_name))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                val newName = editNameField.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            SupabaseInstance.auth.updateUser {
                                data = buildJsonObject {
                                    put("display_name", newName)
                                    put("full_name", newName)
                                }
                            }
                            setupUserDetails()
                            Toast.makeText(requireContext(), getString(R.string.msg_name_updated), Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), getString(R.string.error_updating_name, e.localizedMessage ?: getString(R.string.label_unknown)), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.error_name_empty), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
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
