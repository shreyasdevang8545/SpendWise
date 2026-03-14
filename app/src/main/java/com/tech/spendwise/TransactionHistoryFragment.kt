package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentTransactionHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class TransactionHistoryFragment : Fragment() {

    private var _binding: FragmentTransactionHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private val adapter = TransactionListAdapter()

    private var selectedMonth: Int = -1
    private var selectedYear: Int = -1
    private var allTransactions: List<String> = emptyList()

    private var selectionActionMode: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupObservers()
        setupAdapterCallbacks()

        // Fetch a larger set of transactions for history
        viewModel.fetchFromFirestore(limit = 100)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        binding.transactionHistoryList.layoutManager = LinearLayoutManager(requireContext())
        binding.transactionHistoryList.adapter = adapter
    }

    private fun setupObservers() {
        viewModel.firestoreTransactions.observe(viewLifecycleOwner) { cloudList ->
            val localList = viewModel.confirmedTransactions.value ?: emptyList()
            mergeAndPrepareHistory(cloudList, localList)
        }

        viewModel.confirmedTransactions.observe(viewLifecycleOwner) { localList ->
            val cloudList = viewModel.firestoreTransactions.value ?: emptyList()
            mergeAndPrepareHistory(cloudList, localList)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                binding.historyShimmer.visibility = View.VISIBLE
                binding.historyShimmer.startShimmer()
                binding.transactionHistoryList.visibility = View.GONE
            } else {
                binding.historyShimmer.stopShimmer()
                binding.historyShimmer.visibility = View.GONE
                binding.transactionHistoryList.visibility = View.VISIBLE
            }
        }
    }

    private fun mergeAndPrepareHistory(cloudList: List<String>, localList: List<String>) {
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

        allTransactions = merged
        setupMonthChips(merged)
        applyFilter()
    }

    private fun setupMonthChips(transactions: List<String>) {
        val monthsSet = mutableSetOf<Pair<Int, Int>>() // Month (0-11), Year
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

        transactions.forEach { json ->
            val data = parseSimpleJson(json)
            val savedAt = data["saved_at"] ?: ""
            try {
                val date = sdf.parse(savedAt)
                if (date != null) {
                    val cal = Calendar.getInstance().apply { time = date }
                    monthsSet.add(cal.get(Calendar.MONTH) to cal.get(Calendar.YEAR))
                }
            } catch (_: Exception) {}
        }

        // Sort months descending
        val sortedMonths = monthsSet.toList().sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })

        binding.monthChipGroup.removeAllViews()
        
        // If no transactions, add current month as default
        if (sortedMonths.isEmpty()) {
            val cal = Calendar.getInstance()
            addMonthChip(cal.get(Calendar.MONTH), cal.get(Calendar.YEAR), true)
        } else {
            sortedMonths.forEachIndexed { index, pair ->
                addMonthChip(pair.first, pair.second, index == 0)
            }
        }
    }

    private fun addMonthChip(month: Int, year: Int, isChecked: Boolean) {
        val chip = Chip(requireContext())
        val monthName = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(
            Calendar.getInstance().apply {
                set(Calendar.MONTH, month)
                set(Calendar.YEAR, year)
            }.time
        )
        chip.text = monthName
        chip.isCheckable = true
        chip.isChecked = isChecked
        
        if (isChecked) {
            selectedMonth = month + 1
            selectedYear = year
        }

        chip.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selectedMonth = month + 1
                selectedYear = year
                applyFilter()
            }
        }
        binding.monthChipGroup.addView(chip)
    }

    private fun applyFilter() {
        if (selectedMonth == -1) return

        val filtered = allTransactions.filter { json ->
            val data = parseSimpleJson(json)
            val savedAt = data["saved_at"] ?: ""
            isSameMonth(savedAt, selectedYear, selectedMonth)
        }

        adapter.submitList(filtered)
        binding.emptyHistoryState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupAdapterCallbacks() {
        adapter.onTransactionLongClick = { _ ->
            if (!selectionActionMode) {
                enterSelectionMode()
            }
        }

        adapter.onTransactionClick = { json ->
            showEditDeleteDialog(json)
        }

        adapter.onSelectionChanged = {
            updateSelectionTitle()
        }
    }

    private fun enterSelectionMode() {
        selectionActionMode = true
        adapter.isSelectionMode = true
        updateSelectionTitle()
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_close)
        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_selection)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete_selected) {
                confirmBatchDelete()
                true
            } else false
        }
        binding.toolbar.setNavigationOnClickListener {
            exitSelectionMode()
        }
    }

    private fun exitSelectionMode() {
        selectionActionMode = false
        adapter.isSelectionMode = false
        binding.toolbar.title = "Transaction History"
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_back)
        binding.toolbar.menu.clear()
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun updateSelectionTitle() {
        binding.toolbar.title = "${adapter.selectedIds.size} selected"
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
                    viewModel.deleteLocalTransaction(json)
                } else {
                    confirmSingleDelete(id!!)
                }
            }
        }
    }

    private fun showEditDialog(id: String, json: String) {
        val data = parseSimpleJson(json)
        val builder = android.app.AlertDialog.Builder(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_transaction, null)
        
        val editMerchant = dialogView.findViewById<android.widget.EditText>(R.id.editMerchant)
        val editAmount = dialogView.findViewById<android.widget.EditText>(R.id.editAmount)
        val editCategory = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.editCategory)
        
        editMerchant.setText(data["merchant"])
        editAmount.setText(data["amount"])
        editCategory.setText(data["category"])
        
        // Setup category dropdown (simulated for now)
        val categories = arrayOf("Food", "Entertainment", "Shopping", "Transport", "Bills", "Health", "Investment", "Others")
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        editCategory.setAdapter(adapter)

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

    private fun buildJsonString(map: Map<String, String>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            if (k == "amount") "\"$k\":$v" else "\"$k\":\"$v\""
        }
        return "{$entries}"
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

    private fun confirmBatchDelete() {
        val count = adapter.selectedIds.size
        if (count == 0) return

        UIUtils.showAlertDialog(
            requireContext(),
            "Delete Transactions",
            "Are you sure you want to delete these $count transactions?",
            "Delete",
            "Cancel"
        ) {
            viewModel.deleteTransactionsBatch(adapter.selectedIds.toList())
            exitSelectionMode()
        }
    }

    private fun isSameMonth(savedAt: String, year: Int, month: Int): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = sdf.parse(savedAt) ?: return false
            val cal = Calendar.getInstance().apply { time = date }
            cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) + 1 == month
        } catch (_: Exception) {
            false
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
