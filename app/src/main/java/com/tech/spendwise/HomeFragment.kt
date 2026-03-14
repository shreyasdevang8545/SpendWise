package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentHomeBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Main screen showing the monthly spend summary, 10 most recent transactions,
 * and (when present) the pending-review card.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private val adapter = TransactionListAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set up the RecyclerView
        binding.recentTransactionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.recentTransactionsList.adapter = adapter

        setupAdapterCallbacks()

        // Fetch cloud transactions when screen opens
        viewModel.fetchFromFirestore()

        // Observe pending transactions for the bottom card
        viewModel.pendingTransactions.observe(viewLifecycleOwner) { list ->
            if (list.isNotEmpty()) {
                binding.pendingTransactionCard.visibility = View.VISIBLE
                val count = list.size
                binding.pendingTransactionText.text = if (count == 1) {
                    "1 Transaction found. Tap to review."
                } else {
                    "$count Transactions found. Tap to review."
                }
            } else {
                binding.pendingTransactionCard.visibility = View.GONE
            }
        }

        // Observe Firestore transactions — merge with local, deduplicate, show top-10
        viewModel.firestoreTransactions.observe(viewLifecycleOwner) { list ->
            mergeAndDisplay(list)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                binding.shimmerViewContainer.visibility = View.VISIBLE
                binding.shimmerViewContainer.startShimmer()
                binding.recentTransactionsList.visibility = View.GONE
            } else {
                binding.shimmerViewContainer.stopShimmer()
                binding.shimmerViewContainer.visibility = View.GONE
                binding.recentTransactionsList.visibility = View.VISIBLE
            }
        }
        // Observe local confirmed transactions — merge with cloud
        viewModel.confirmedTransactions.observe(viewLifecycleOwner) { _ ->
            val cloudList = viewModel.firestoreTransactions.value ?: emptyList()
            mergeAndDisplay(cloudList)
        }

        // Navigation
        binding.pendingTransactionCard.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_review)
        }

        binding.pendingTransactionCard.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_review)
        }

        // Long-press on the summary card → sign out (UX: accessible but not accidental)
        binding.summaryCard.setOnLongClickListener {
            showSignOutDialog()
            true
        }

        binding.viewAllButton.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_transactionHistory)
        }
    }

    // ── Merge & Display ─────────────────────────────────────────────────────

    /**
     * Merges [cloudList] (Firestore) with local DataStore confirmed transactions.
     * Deduplicates by `saved_at` timestamp, shows top-10 newest, and updates monthly total.
     */
    private fun mergeAndDisplay(cloudList: List<String>) {
        val localList = viewModel.confirmedTransactions.value ?: emptyList()

        // Build a deduplicated merged list, keyed by saved_at (or full JSON as fallback)
        val seen    = mutableSetOf<String>()
        val merged  = mutableListOf<String>()

        // Cloud first (already newest-first from Firestore query)
        for (json in cloudList) {
            val savedAt = parseSimpleJson(json)["saved_at"] ?: json
            if (seen.add(savedAt)) merged.add(json)
        }
        // Then local
        for (json in localList) {
            val savedAt = parseSimpleJson(json)["saved_at"] ?: json
            if (seen.add(savedAt)) merged.add(json)
        }

        if (merged.isEmpty()) {
            binding.summaryCard.visibility       = View.GONE
            binding.emptyStateContainer.visibility = View.VISIBLE
        } else {
            binding.summaryCard.visibility       = View.VISIBLE
            binding.emptyStateContainer.visibility = View.GONE
            binding.viewAllButton.visibility     = if (merged.size > 3) View.VISIBLE else View.GONE

            // Show 5 most recent in the list
            adapter.submitList(merged.take(5))

            // Monthly breakdown
            val cal          = Calendar.getInstance()
            val currentYear  = cal.get(Calendar.YEAR)
            val currentMonth = cal.get(Calendar.MONTH) + 1

            var monthlyTotal = 0.0
            var expensesOnly = 0.0
            var lendsOnly    = 0.0

            merged.forEach { json ->
                val data    = parseSimpleJson(json)
                val type    = data["type"] ?: ""
                val savedAt = data["saved_at"] ?: ""
                val amount  = data["amount"]?.toDoubleOrNull() ?: 0.0
                val isLend  = data["is_lend"]?.toBoolean() ?: false

                if (type.equals("DEBIT", ignoreCase = true) && isSameMonth(savedAt, currentYear, currentMonth)) {
                    monthlyTotal += amount
                    if (isLend) {
                        lendsOnly += amount
                    } else {
                        expensesOnly += amount
                    }
                }
            }

            binding.monthlyTotalText.text = formatAmount(monthlyTotal, merged.firstOrNull())
            binding.expensesOnlyText.text = formatAmount(expensesOnly, merged.firstOrNull())
            binding.lendsOnlyText.text    = formatAmount(lendsOnly, merged.firstOrNull())
            binding.monthLabelText.text   = monthLabel(cal)
        }
    }

    private fun setupAdapterCallbacks() {
        adapter.onTransactionClick = { json ->
            showEditDeleteDialog(json)
        }
        adapter.onTransactionLongClick = { json ->
            showEditDeleteDialog(json)
        }
    }

    private fun showEditDeleteDialog(json: String) {
        val data = parseSimpleJson(json)
        val id = data["id"]
        val isOffline = id == null

        val options = if (isOffline) arrayOf("Upload Now", "Delete") else arrayOf("Edit", "Delete")
        UIUtils.showListDialog(requireContext(), if (isOffline) "Offline Transaction" else "Transaction Options", options) { which ->
            when (options[which]) {
                "Upload Now" -> viewModel.uploadOfflineTransaction(json)
                "Edit" -> showEditDialog(id!!, json)
                "Delete" -> if (isOffline) {
                    // Local delete
                    lifecycleScope.launchWhenStarted {
                        viewModel.deleteLocalTransaction(json)
                    }
                } else {
                    confirmSingleDelete(id!!)
                }
            }
        }
    }

    private fun showEditDialog(id: String, json: String) {
        val data = parseSimpleJson(json)
        val builder = AlertDialog.Builder(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_transaction, null)

        val editMerchant = dialogView.findViewById<android.widget.EditText>(R.id.editMerchant)
        val editAmount = dialogView.findViewById<android.widget.EditText>(R.id.editAmount)
        val editCategory = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.editCategory)

        editMerchant.setText(data["merchant"])
        editAmount.setText(data["amount"])
        editCategory.setText(data["category"])

        val categories = arrayOf("Food", "Entertainment", "Shopping", "Transport", "Bills", "Health", "Investment", "Others")
        val catAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        editCategory.setAdapter(catAdapter)

        builder.setView(dialogView)
            .setTitle("Edit Transaction")
            .setPositiveButton("Save") { _, _ ->
                val newMerchant = editMerchant.text.toString()
                val newAmount = editAmount.text.toString().toDoubleOrNull() ?: 0.0
                val newCategory = editCategory.text.toString()

                val updatedData = data.toMutableMap()
                updatedData["merchant"] = newMerchant
                updatedData["amount"] = newAmount.toString()
                updatedData["category"] = newCategory

                viewModel.updateTransaction(id, buildJsonString(updatedData))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmSingleDelete(id: String) {
        UIUtils.showAlertDialog(
            requireContext(),
            "Delete Transaction",
            "Are you sure you want to delete this transaction?",
            "Delete",
            "Cancel"
        ) {
            viewModel.deleteTransaction(id)
        }
    }

    private fun buildJsonString(map: Map<String, String>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            if (k == "amount") "\"$k\":$v" else "\"$k\":\"$v\""
        }
        return "{$entries}"
    }

    // ── Sign-out ────────────────────────────────────────────────────────────

    private fun showSignOutDialog() {
        UIUtils.showAlertDialog(
            requireContext(),
            "Sign Out",
            "Are you sure you want to sign out?\nYou'll need to verify your phone number again to access your data.",
            "Sign Out",
            "Cancel"
        ) {
            (activity as? MainActivity)?.signOut()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun isSameMonth(savedAt: String, year: Int, month: Int): Boolean {
        return try {
            val sdf  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = sdf.parse(savedAt) ?: return false
            val cal  = Calendar.getInstance().apply { time = date }
            cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) + 1 == month
        } catch (_: Exception) { false }
    }

    private fun formatAmount(total: Double, firstJson: String?): String {
        val currency = if (firstJson != null) parseSimpleJson(firstJson)["currency"] ?: "INR" else "INR"
        val symbol   = if (currency == "INR") "₹" else "$currency "
        val fmt      = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "$symbol${fmt.format(total)}"
    }

    private fun monthLabel(cal: Calendar): String {
        val months = arrayOf(
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
        )
        return "${months[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)}"
    }

    private fun parseSimpleJson(json: String): Map<String, String> {
        val result  = mutableMapOf<String, String>()
        val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
        for (match in pattern.findAll(json)) {
            val key   = match.groupValues[1]
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            result[key] = value
        }
        return result
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
