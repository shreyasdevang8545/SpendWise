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
import com.tech.spendwise.SupabaseInstance
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentHomeBinding
import androidx.recyclerview.widget.PagerSnapHelper
import android.view.animation.AnimationUtils

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.tech.spendwise.views.SummaryBarGraph.BarData

/**
 * Main screen showing the monthly spend summary, 10 most recent transactions,
 * and (when present) the pending-review card.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private val adapter = TransactionListAdapter()
    // private lateinit var cardAdapter: CreditCardAdapter // Commented for Release


    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private var isUserTouching = false
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!isUserTouching && _binding != null) {
                val scrollView = binding.badgeScrollView
                val badgeRow = binding.badgeRow
                
                // Infinite Loop Logic: Reset scroll when halfway through the duplicated content
                val resetPoint = badgeRow.width / 2
                if (resetPoint > 0) {
                    var newScrollX = scrollView.scrollX + 2
                    if (newScrollX >= resetPoint) {
                        newScrollX = 0 // Instant reset to start
                        scrollView.scrollTo(0, 0)
                    } else {
                        scrollView.scrollTo(newScrollX, 0)
                    }
                }
            }
            autoScrollHandler.postDelayed(this, 30)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set up the RecyclerView
        binding.recentTransactionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.recentTransactionsList.adapter = adapter

        /* Commented for Release
        // Setup Credit Card RV
        cardAdapter = CreditCardAdapter(emptyList(), emptyMap())
        binding.rvCreditCards.adapter = cardAdapter
        
        // Add snapping behavior for cards
        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(binding.rvCreditCards)
        
        // Entrance animation
        binding.rvCreditCards.layoutAnimation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.item_animation_fall_down)
        */

        setupAdapterCallbacks()



        // Fetch cloud transactions when screen opens (skip if data already present)
        viewModel.fetchFromFirestore(forceRefresh = false)

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchFromFirestore(forceRefresh = true)
        }

        // Observe pending transactions for the bottom card and notification badge
        viewModel.pendingTransactions.observe(viewLifecycleOwner) { list ->
            val count = list.size
            if (count > 0) {
                // Bottom card
                binding.pendingTransactionCard.visibility = View.VISIBLE
                binding.pendingTransactionText.text = if (count == 1) {
                    getString(R.string.home_transaction_found_single)
                } else {
                    getString(R.string.home_transaction_found_plural, count)
                }
                
                // Toolbar badge
                binding.notificationBadge.visibility = View.VISIBLE
                binding.notificationBadge.text = if (count > 9) "9+" else count.toString()
            } else {
                binding.pendingTransactionCard.visibility = View.GONE
                binding.notificationBadge.visibility = View.GONE
            }
        }

        // Observe Firestore transactions — merge with local, deduplicate, show top-10
        viewModel.firestoreTransactions.observe(viewLifecycleOwner) { _ ->
            refreshSummary()
        }

        viewModel.lends.observe(viewLifecycleOwner) { _ ->
            refreshSummary()
        }

        /* Commented for Release
        viewModel.creditCards.observe(viewLifecycleOwner) { _ ->
            refreshSummary()
        }
        */


        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.swipeRefresh.isRefreshing = isLoading
            val hasData = viewModel.firestoreTransactions.value?.isNotEmpty() == true
            
            if (isLoading && !hasData) {
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
            refreshSummary()
        }
        // Navigation
        binding.pendingTransactionCard.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_review)
        }

        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }

        binding.viewAllBadge.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_transactionHistory)
        }

        // Enable marquee for badges
        binding.incomeTotalText.isSelected = true
        binding.spentTotalText.isSelected = true
        binding.lentTotalText.isSelected = true
        binding.incomeTotalText2.isSelected = true
        binding.spentTotalText2.isSelected = true
        binding.lentTotalText2.isSelected = true

        // Initial start of auto-scroll
        autoScrollHandler.postDelayed(autoScrollRunnable, 1000)

        binding.btnEmptyGetStarted.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_addTransaction)
        }

        binding.btnNotifications.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_notificationLog)
        }

        // Pause on touch, resume after delay
        binding.badgeScrollView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isUserTouching = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    autoScrollHandler.postDelayed({ isUserTouching = false }, 2000)
                }
            }
            false // Continue handling touch
        }

        showGreeting()
    }

    private fun showGreeting() {
        val name = SupabaseInstance.currentUserDisplayName()
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        
        val greeting = when (hour) {
            in 0..11 -> getString(R.string.greeting_morning)
            in 12..15 -> getString(R.string.greeting_afternoon)
            in 16..20 -> getString(R.string.greeting_evening)
            else -> getString(R.string.greeting_night)
        }
        
        val message = if (!name.isNullOrBlank()) {
            getString(R.string.label_greeting_name, greeting, name.split(" ")[0])
        } else {
            getString(R.string.label_greeting_simple, greeting)
        }
        
        binding.greetingText.text = message
    }

    private fun refreshSummary() {
        val cloudList = viewModel.firestoreTransactions.value ?: emptyList()
        mergeAndDisplay(cloudList)
        updateAnalyticsGraph()
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

        val currentMonthLends = viewModel.lends.value ?: emptyList()
        val hasData = merged.isNotEmpty() || currentMonthLends.isNotEmpty()

        if (!hasData) {
            binding.headerLayout.visibility       = View.VISIBLE
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.transactionsContainer.visibility = View.GONE
            binding.graphSection.visibility = View.GONE
            // Hide budget details in header when empty
            binding.mainBalanceLabel.visibility = View.GONE
            binding.mainBalanceText.visibility = View.GONE
            binding.headerMonthLabel.visibility = View.GONE
            binding.badgeScrollView.visibility = View.GONE
        } else {
            binding.headerLayout.visibility       = View.VISIBLE
            binding.emptyStateContainer.visibility = View.GONE
            binding.transactionsContainer.visibility = View.VISIBLE
            binding.graphSection.visibility = View.VISIBLE
            // Show budget details in header when data present
            binding.mainBalanceLabel.visibility = View.VISIBLE
            binding.mainBalanceText.visibility = View.VISIBLE
            binding.headerMonthLabel.visibility = View.VISIBLE
            binding.badgeScrollView.visibility = View.VISIBLE
            binding.viewAllBadge.visibility     = if (merged.size > 3) View.VISIBLE else View.GONE

            // Show 5 most recent in the list
            adapter.submitList(merged.take(5))

            // Monthly breakdown
            val cal          = Calendar.getInstance()
            val currentYear  = cal.get(Calendar.YEAR)
            val currentMonth = cal.get(Calendar.MONTH) + 1

            var totalIncome  = 0.0
            var totalSpent   = 0.0
            var totalLent    = 0.0

            merged.forEach { json ->
                val data    = parseSimpleJson(json)
                val type    = data["type"] ?: ""
                val savedAt = data["saved_at"] ?: ""
                val amount  = data["amount"]?.toDoubleOrNull() ?: 0.0

                if (isSameMonth(savedAt, currentYear, currentMonth)) {
                    val isLend = data["is_lend"] == "true" || data["category"] == "Lend"
                    val isRepayment = data["category"] == "Lend Repayment"
                    
                    if (type.equals("CREDIT", ignoreCase = true)) {
                        if (!isRepayment) {
                            totalIncome += amount
                        }
                    } else if (type.equals("DEBIT", ignoreCase = true)) {
                        if (!isLend) {
                            totalSpent += amount
                        }
                    }
                }
            }

            // Calculate total lent from ALL unreturned lends (not just this month)
            val currentLends = viewModel.lends.value ?: emptyList()
            currentLends.forEach { lend ->
                if (!lend.isReturned) {
                    totalLent += lend.amount
                }
            }

            val totalOutflow = totalSpent + totalLent
            val mainBalance  = totalIncome - totalOutflow
            
            binding.mainBalanceLabel.text = getString(R.string.label_total_spending)
            binding.mainBalanceText.text = formatAmount(totalSpent, merged.firstOrNull())
            
            val formattedIncome = getString(R.string.home_income, formatAmount(totalIncome, merged.firstOrNull()))
            val formattedSpent = getString(R.string.home_spent, formatAmount(totalSpent, merged.firstOrNull()))
            val formattedLent = getString(R.string.home_lent, formatAmount(totalLent, merged.firstOrNull()))
            
            // Set first set
            binding.incomeTotalText.text = formattedIncome
            binding.spentTotalText.text  = formattedSpent
            binding.lentTotalText.text   = formattedLent
            
            // Set second set for seamless loop
            binding.incomeTotalText2.text = formattedIncome
            binding.spentTotalText2.text  = formattedSpent
            binding.lentTotalText2.text   = formattedLent
            binding.headerMonthLabel.text = monthLabel(cal).uppercase()
            
            // initials removal - no logic needed here anymore as we use ic_settings
            
            /* Commented for Release
            // ── Credit Cards Section ──────────────────────────────────────────
            val cards = viewModel.creditCards.value ?: emptyList()
            if (cards.isNotEmpty()) {
                binding.layoutCreditCards.visibility = View.VISIBLE
                
                // Calculate spent per card
                val cardSpentMap = mutableMapOf<String, Double>()
                merged.forEach { json ->
                    val data = parseSimpleJson(json)
                    val cardId = data["credit_card_id"]
                    val amount = data["amount"]?.toDoubleOrNull() ?: 0.0
                    val type = data["type"] ?: ""
                    
                    if (!cardId.isNullOrEmpty() && type.equals("DEBIT", ignoreCase = true)) {
                        cardSpentMap[cardId] = (cardSpentMap[cardId] ?: 0.0) + amount
                    }
                }
                
                cardAdapter.updateData(cards, cardSpentMap)
            } else {
                binding.layoutCreditCards.visibility = View.GONE
            }
            */
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

        val options = if (isOffline) arrayOf(getString(R.string.dialog_upload_now), getString(R.string.dialog_delete)) else arrayOf(getString(R.string.dialog_edit), getString(R.string.dialog_delete))
        UIUtils.showListDialog(requireContext(), if (isOffline) getString(R.string.dialog_offline_transaction) else getString(R.string.dialog_transaction_options), options) { which ->
            when (options[which]) {
                getString(R.string.dialog_upload_now) -> viewModel.uploadOfflineTransaction(json)
                getString(R.string.dialog_edit) -> showEditDialog(id!!, json)
                getString(R.string.dialog_delete) -> if (isOffline) {
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
        val catAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        editCategory.setAdapter(catAdapter)

        builder.setView(dialogView)
            .setTitle(getString(R.string.dialog_edit_transaction))
            .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                val newMerchant = editMerchant.text.toString()
                val newAmount = editAmount.text.toString().toDoubleOrNull() ?: 0.0
                val newCategory = editCategory.text.toString()

                val updatedData = data.toMutableMap()
                updatedData["merchant"] = newMerchant
                updatedData["amount"] = newAmount.toString()
                updatedData["category"] = newCategory

                viewModel.updateTransaction(id, buildJsonString(updatedData))
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun confirmSingleDelete(id: String) {
        UIUtils.showAlertDialog(
            requireContext(),
            getString(R.string.dialog_delete_transaction),
            getString(R.string.dialog_delete_confirm),
            getString(R.string.dialog_delete),
            getString(R.string.dialog_cancel)
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
            getString(R.string.dialog_sign_out),
            getString(R.string.dialog_sign_out_confirm),
            getString(R.string.dialog_sign_out),
            getString(R.string.dialog_cancel)
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
        val fmt = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val mainPart  = fmt.format(Math.abs(total))
        return if (total < 0) "-$symbol$mainPart" else "$symbol$mainPart"
    }

    private fun monthLabel(cal: Calendar): String {
        val months = arrayOf(
            getString(R.string.month_january), getString(R.string.month_february), getString(R.string.month_march),
            getString(R.string.month_april), getString(R.string.month_may), getString(R.string.month_june),
            getString(R.string.month_july), getString(R.string.month_august), getString(R.string.month_september),
            getString(R.string.month_october), getString(R.string.month_november), getString(R.string.month_december)
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
    private fun updateAnalyticsGraph() {
        val cloudList = viewModel.firestoreTransactions.value ?: emptyList()
        val localList = viewModel.confirmedTransactions.value ?: emptyList()
        val lends = viewModel.lends.value ?: emptyList()

        val mergedTransactions = (cloudList + localList).distinctBy { parseSimpleJson(it)["saved_at"] ?: it }

        val barDataList = mutableListOf<BarData>()

        // Show last 7 days including today
        val days = arrayOf(
            getString(R.string.day_s), getString(R.string.day_m), getString(R.string.day_t),
            getString(R.string.day_w), getString(R.string.day_t), getString(R.string.day_f),
            getString(R.string.day_s)
        )
        for (i in 6 downTo 0) {
            val targetCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
            val dayName = days[targetCal.get(Calendar.DAY_OF_WEEK) - 1]
            
            var income = 0.0
            var spent = 0.0
            
            mergedTransactions.forEach { json ->
                val data = parseSimpleJson(json)
                val savedAt = data["saved_at"] ?: ""
                val amount = data["amount"]?.toDoubleOrNull() ?: 0.0
                val type = data["type"] ?: ""
                val isLend = data["is_lend"] == "true" || data["category"] == "Lend"
                
                if (isSameDay(savedAt, targetCal)) {
                    val isLend = data["is_lend"] == "true" || data["category"] == "Lend"
                    val isRepayment = data["category"] == "Lend Repayment"
                    
                    if (type.equals("CREDIT", ignoreCase = true)) {
                        if (!isRepayment) {
                            income += amount
                        }
                    } else if (type.equals("DEBIT", ignoreCase = true)) {
                        if (!isLend) {
                            spent += amount
                        }
                    }
                }
            }
            
            // Lends are now handled by the user request to NOT show in graphs as spent
            // We can add logic here if we want to show it in a separate color, but for now we exclude as requested
            /*
            lends.forEach { lend ->
                if (isSameDay(lend.createdAt, targetCal)) {
                    spent += lend.amount 
                }
            }
            */
            
            barDataList.add(BarData(dayName, income, spent))
        }

        binding.summaryGraph.setData(barDataList)
    }

    private fun isSameDay(savedAt: String, target: Calendar): Boolean {
        val date = parseIsoDate(savedAt) ?: return false
        val dCal = Calendar.getInstance().apply { time = date }
        return dCal.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
               dCal.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }

    private fun isSameDay(timestamp: Long, target: Calendar): Boolean {
        val dCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return dCal.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
               dCal.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }

    private fun parseIsoDate(iso: String): java.util.Date? {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(iso)
        } catch (_: Exception) { null }
    }

    override fun onDestroyView() {
        autoScrollHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
        _binding = null
    }
}
