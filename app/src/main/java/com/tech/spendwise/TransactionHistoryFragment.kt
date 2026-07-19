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
import com.tech.spendwise.models.LendTransaction
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class TransactionHistoryFragment : Fragment() {

    private var _binding: FragmentTransactionHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private val adapter = TransactionListAdapter()
    private val lendAdapter = LendListAdapter { lend, position -> 
        showLendActionMenu(lend, position)
    }
    private val supabaseRepository = SupabaseRepository()
    private val groupsAdapter = GroupsAdapter { group ->
        val bundle = Bundle().apply {
            putString("groupId", group.id)
            putString("groupName", group.name)
        }
        findNavController().navigate(R.id.action_history_to_groupDetails, bundle)
    }

    private var selectedMonth: Int = -1
    private var selectedYear: Int = -1
    private var allTransactions: List<String> = emptyList()
    private var currentViewType = HistoryFilterBottomSheet.HistoryType.TRANSACTIONS

    private var selectionActionMode: Boolean = false
 
    enum class FilterType {
        TODAY, THIS_MONTH, LAST_MONTH, THIS_YEAR, ALL, SPECIFIC_MONTH
    }
    private var currentFilterType = FilterType.THIS_MONTH

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

        val startWithLends = arguments?.getBoolean("startWithLends", false) ?: false
        if (startWithLends) {
            currentViewType = HistoryFilterBottomSheet.HistoryType.LENDS
        }

        setupToolbar()
        setupRecyclerView()
        setupGroupsRecyclerView()
        setupLendSwipeActions()
        setupObservers()
        setupFilterListeners()
        setupAdapterCallbacks()
        setupTabLayout()
        
        updateViewType()

        // Fetch a larger set of transactions for history
        viewModel.fetchFromFirestore()
    }

    private fun setupFilterListeners() {
        binding.filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilterType = when (checkedIds.firstOrNull()) {
                R.id.chipToday -> FilterType.TODAY
                R.id.chipThisMonth -> FilterType.THIS_MONTH
                R.id.chipLastMonth -> FilterType.LAST_MONTH
                R.id.chipThisYear -> FilterType.THIS_YEAR
                R.id.chipAll -> FilterType.ALL
                else -> FilterType.THIS_MONTH
            }
            
            // Hide specific month scroll if a relative filter is active (except ALL)
            binding.monthChipScroll.visibility = if (currentFilterType == FilterType.ALL) View.VISIBLE else View.GONE
            
            applyFilter()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_transaction_history)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_filter -> {
                    HistoryFilterBottomSheet { type ->
                        if (currentViewType != type) {
                            currentViewType = type
                            updateViewType()
                        }
                    }.show(childFragmentManager, HistoryFilterBottomSheet.TAG)
                    true
                }
                else -> false
            }
        }
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupGroupsRecyclerView() {
        binding.groupsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.groupsRecyclerView.adapter = groupsAdapter
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (tab.position == 0) {
                    binding.transactionsContainer.visibility = View.VISIBLE
                    binding.groupsContainer.visibility = View.GONE
                } else {
                    binding.transactionsContainer.visibility = View.GONE
                    binding.groupsContainer.visibility = View.VISIBLE
                    fetchGroups()
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun fetchGroups() {
        binding.groupsProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val groups = supabaseRepository.fetchGroups()
            binding.groupsProgress.visibility = View.GONE
            groupsAdapter.submitList(groups)
        }
    }

    private fun updateViewType() {
        // ... (Keep existing implementation for Lends vs Transactions)
        if (currentViewType == HistoryFilterBottomSheet.HistoryType.TRANSACTIONS) {
            binding.toolbar.title = getString(R.string.title_transaction_history)
            binding.transactionHistoryList.adapter = adapter
            binding.filterChipGroup.visibility = View.VISIBLE
            binding.monthChipScroll.visibility = if (currentFilterType == FilterType.ALL) View.VISIBLE else View.GONE
            binding.textEmptyTitle.text = "No Transactions"
            binding.textEmptyDesc.text = "You haven't tracked any transactions for this month. Your history will appear here once you do."
            binding.tabLayout.visibility = View.VISIBLE
            applyFilter()
        } else {
            binding.toolbar.title = getString(R.string.title_lend_history)
            binding.transactionHistoryList.adapter = lendAdapter
            binding.filterChipGroup.visibility = View.GONE
            binding.monthChipScroll.visibility = View.GONE
            binding.textEmptyTitle.text = "No Lend History"
            binding.textEmptyDesc.text = "You haven't recorded any lend transactions. Start tracking money given to others!"
            binding.tabLayout.visibility = View.GONE
            binding.transactionsContainer.visibility = View.VISIBLE
            binding.groupsContainer.visibility = View.GONE
            fetchLends()
        }
    }

    private fun fetchLends() {
        binding.historyShimmer.visibility = View.VISIBLE
        binding.historyShimmer.startShimmer()
        binding.transactionHistoryList.visibility = View.GONE
        binding.emptyHistoryState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val lends = supabaseRepository.fetchLends()
                _binding?.let { binding ->
                    binding.historyShimmer.stopShimmer()
                    binding.historyShimmer.visibility = View.GONE
                    binding.transactionHistoryList.visibility = View.VISIBLE
                    
                    if (lends.isEmpty()) {
                        binding.emptyHistoryState.visibility = View.VISIBLE
                    } else {
                        binding.emptyHistoryState.visibility = View.GONE
                        lendAdapter.submitList(lends)
                    }
                }
            } catch (e: Exception) {
                Log.e("TransactionHistory", "Error fetching lends: ${e.message}")
                _binding?.let { binding ->
                    binding.historyShimmer.stopShimmer()
                    binding.historyShimmer.visibility = View.GONE
                    binding.emptyHistoryState.visibility = View.VISIBLE
                }
            }
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
                currentFilterType = FilterType.SPECIFIC_MONTH
                applyFilter()
            }
        }
        binding.monthChipGroup.addView(chip)
    }

    private fun applyFilter() {
        val now = Calendar.getInstance()
        val filtered = allTransactions.filter { json ->
            val data = parseSimpleJson(json)
            val savedAt = data["saved_at"] ?: ""
            val transactionDate = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(savedAt)
            } catch (_: Exception) { null } ?: return@filter false
            
            val cal = Calendar.getInstance().apply { time = transactionDate }
            
            when (currentFilterType) {
                FilterType.TODAY -> isSameDay(cal, now)
                FilterType.THIS_MONTH -> isSameMonth(cal, now)
                FilterType.LAST_MONTH -> isLastMonth(cal, now)
                FilterType.THIS_YEAR -> cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                FilterType.SPECIFIC_MONTH -> cal.get(Calendar.YEAR) == selectedYear && cal.get(Calendar.MONTH) + 1 == selectedMonth
                FilterType.ALL -> true
            }
        }
 
        adapter.submitList(filtered)
        binding.emptyHistoryState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun isSameDay(c1: Calendar, c2: Calendar): Boolean {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isSameMonth(c1: Calendar, c2: Calendar): Boolean {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.MONTH) == c2.get(Calendar.MONTH)
    }

    private fun isLastMonth(c1: Calendar, c2: Calendar): Boolean {
        val lastMonth = Calendar.getInstance().apply {
            time = c2.time
            add(Calendar.MONTH, -1)
        }
        return c1.get(Calendar.YEAR) == lastMonth.get(Calendar.YEAR) &&
               c1.get(Calendar.MONTH) == lastMonth.get(Calendar.MONTH)
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
        binding.toolbar.title = getString(R.string.title_transaction_history)
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_back)
        binding.toolbar.menu.clear()
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun updateSelectionTitle() {
        binding.toolbar.title = getString(R.string.label_n_selected, adapter.selectedIds.size)
    }

    private fun showEditDeleteDialog(json: String) {
        val data = parseSimpleJson(json)
        val id = data["id"]
        val isOffline = id == null
        
        val options = if (isOffline) arrayOf(getString(R.string.btn_upload_now), getString(R.string.btn_delete)) else arrayOf(getString(R.string.btn_edit), getString(R.string.btn_delete))
        UIUtils.showListDialog(requireContext(), if (isOffline) getString(R.string.title_offline_transaction) else getString(R.string.title_transaction_options), options) { which ->
            when (options[which]) {
                getString(R.string.btn_upload_now) -> viewModel.uploadOfflineTransaction(json)
                getString(R.string.btn_edit) -> showEditDialog(id!!, json)
                getString(R.string.btn_delete) -> if (isOffline) {
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
        val chipIncome = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipIncome)
        val chipExpense = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipExpense)
        
        editMerchant.setText(data["merchant"])
        editAmount.setText(data["amount"])
        editCategory.setText(data["category"])
        
        val currentType = data["type"] ?: "DEBIT"
        if (currentType == "CREDIT") chipIncome.isChecked = true else chipExpense.isChecked = true
        
        // Setup category dropdown (simulated for now)
        val categories = arrayOf(
            getString(R.string.category_food),
            getString(R.string.category_entertainment),
            getString(R.string.category_shopping),
            getString(R.string.category_transport),
            getString(R.string.category_bills),
            getString(R.string.category_health),
            getString(R.string.category_investment),
            getString(R.string.category_others)
        )
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        editCategory.setAdapter(adapter)

        builder.setView(dialogView)
            .setTitle(getString(R.string.title_edit_transaction))
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newMerchant = editMerchant.text.toString()
                val newAmount = editAmount.text.toString().toDoubleOrNull() ?: 0.0
                val newCategory = editCategory.text.toString()
                val newType = if (chipIncome.isChecked) "CREDIT" else "DEBIT"
                
                val updatedData = data.toMutableMap()
                updatedData["merchant"] = newMerchant
                updatedData["amount"] = newAmount.toString()
                updatedData["category"] = newCategory
                updatedData["type"] = newType
                
                viewModel.updateTransaction(id, buildJsonString(updatedData))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun buildJsonString(map: Map<String, String>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"$k\":\"$v\""
        }
        return "{$entries}"
    }

    private fun confirmSingleDelete(id: String) {
        UIUtils.showAlertDialog(
            requireContext(),
            getString(R.string.dialog_delete_transaction_title),
            getString(R.string.dialog_delete_transaction_msg),
            getString(R.string.btn_delete),
            getString(R.string.btn_cancel)
        ) {
            viewModel.deleteTransaction(id)
        }
    }

    private fun confirmBatchDelete() {
        val count = adapter.selectedIds.size
        if (count == 0) return

        UIUtils.showAlertDialog(
            requireContext(),
            getString(R.string.dialog_delete_transactions_batch_title),
            getString(R.string.dialog_delete_transactions_batch_msg, count),
            getString(R.string.btn_delete),
            getString(R.string.btn_cancel)
        ) {
            viewModel.deleteTransactionsBatch(adapter.selectedIds.toList())
            exitSelectionMode()
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

    private fun setupLendSwipeActions() {
        val swipeHandler = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, t: androidx.recyclerview.widget.RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                if (currentViewType != HistoryFilterBottomSheet.HistoryType.LENDS) {
                    adapter.notifyItemChanged(viewHolder.adapterPosition)
                    return
                }

                val position = viewHolder.adapterPosition
                if (position < 0 || position >= lendAdapter.currentList.size) return
                val lend = lendAdapter.currentList[position]

                if (direction == androidx.recyclerview.widget.ItemTouchHelper.LEFT) {
                    // Delete
                    showLendDeleteConfirmation(lend, position)
                } else if (direction == androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
                    // Action Menu
                    showLendActionMenu(lend, position)
                }
                
                // Immediately reset the swipe visually
                lendAdapter.notifyItemChanged(position)
            }
        }
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(swipeHandler)
        itemTouchHelper.attachToRecyclerView(binding.transactionHistoryList)
    }

    private fun showLendActionMenu(lend: LendTransaction, position: Int) {
        val options = if (lend.isReturned) {
            arrayOf(getString(R.string.btn_edit_detail))
        } else {
            arrayOf(getString(R.string.btn_mark_returned), getString(R.string.btn_edit_detail))
        }

        UIUtils.showListDialog(requireContext(), getString(R.string.title_action_for, lend.name), options) { which ->
            when (options[which]) {
                getString(R.string.btn_mark_returned) -> markLendAsReturned(lend)
                getString(R.string.btn_edit_detail) -> {
                    val bundle = Bundle().apply {
                        putString("lendId", lend.id)
                    }
                    findNavController().navigate(R.id.action_history_to_addLend, bundle)
                }
            }
        }
    }

    private fun markLendAsReturned(lend: LendTransaction) {
        val uid = SupabaseInstance.currentUserId() ?: return
        lifecycleScope.launch {
            supabaseRepository.updateLendStatus(lend.id!!, true)
            _binding?.let {
                lend.id?.let { id ->
                    ReminderManager.cancelReminder(requireContext(), id)
                }
                fetchLends() // Refresh
            }
        }
    }

    private fun showLendDeleteConfirmation(lend: LendTransaction, position: Int) {
        UIUtils.showAlertDialog(
            requireContext(),
            getString(R.string.title_delete_record),
            getString(R.string.msg_delete_lend_confirm, lend.name),
            getString(R.string.btn_delete),
            getString(R.string.btn_cancel)
        ) {
            val uid = SupabaseInstance.currentUserId() ?: return@showAlertDialog
            lifecycleScope.launch {
                supabaseRepository.deleteLend(lend.id!!)
                _binding?.let {
                    lend.id?.let { id ->
                        ReminderManager.cancelReminder(requireContext(), id)
                    }
                    fetchLends() // Refresh list
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
