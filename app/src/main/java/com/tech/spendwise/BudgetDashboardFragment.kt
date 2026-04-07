package com.tech.spendwise

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.tech.spendwise.databinding.FragmentBudgetDashboardBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

class BudgetDashboardFragment : Fragment() {

    private var _binding: FragmentBudgetDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransactionViewModel by activityViewModels()
    private lateinit var settingsManager: SettingsManager
    private val selectedCalendar = Calendar.getInstance()

    // Standard hardcoded categories for missing defaults based on mockup
    private val defaultCategories by lazy {
        listOf(
            getString(R.string.category_food),
            getString(R.string.category_travel),
            getString(R.string.category_shopping),
            getString(R.string.category_entertainment),
            getString(R.string.category_health),
            getString(R.string.category_education),
            getString(R.string.category_bills),
            getString(R.string.category_gifts),
            getString(R.string.category_others),
            getString(R.string.category_utilities),
            getString(R.string.category_transport)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBudgetDashboardBinding.inflate(inflater, container, false)
        settingsManager = SettingsManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Observe both local and cloud transactions
        viewModel.confirmedTransactions.observe(viewLifecycleOwner) { refreshData() }
        viewModel.firestoreTransactions.observe(viewLifecycleOwner) { refreshData() }
        
        // Also observe settings changes (budget limits)
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.overallBudgetLimit.collect { refreshData() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            settingsManager.categoryBudgetLimits.collect { refreshData() }
        }

        binding.btnAddCategory.setOnClickListener {
            showAddCategoryDialog()
        }

        binding.overallBudgetCard.setOnClickListener {
            showOverallBudgetDialog()
        }

        binding.budgetToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnPrevMonth.setOnClickListener {
            selectedCalendar.add(Calendar.MONTH, -1)
            updateDateAndRefresh()
        }

        binding.btnNextMonth.setOnClickListener {
            selectedCalendar.add(Calendar.MONTH, 1)
            updateDateAndRefresh()
        }

        setupDateDisplay()
    }

    private fun updateDateAndRefresh() {
        setupDateDisplay()
        refreshData()
    }

    private fun setupDateDisplay() {
        val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        binding.tvMonthYear.text = monthYearFormat.format(selectedCalendar.time)
    }

    private fun refreshData() {
        val localList = viewModel.confirmedTransactions.value ?: emptyList()
        val cloudList = viewModel.firestoreTransactions.value ?: emptyList()
        
        // Merge and remove duplicates by ID if possible, or just merge strings
        // Since they are all JSON strings, we can just merge.
        val combined = (localList + cloudList).distinct()
        
        calculateAndDisplayBudgets(combined)
    }

    private fun calculateAndDisplayBudgets(txList: List<String>) {
        try {
            // Fetch budget limits synchronously if possible, or use current values
            viewLifecycleOwner.lifecycleScope.launch {
                val overallLimit = settingsManager.overallBudgetLimit.first()
                val categoryLimitsStr = settingsManager.categoryBudgetLimits.first().ifEmpty { "{}" }
                
                renderUI(txList, overallLimit.toDouble(), categoryLimitsStr)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun renderUI(txList: List<String>, overallLimit: Double, categoryLimitsStr: String) {
        try {
            val categoryBudgetsLimitMap = mutableMapOf<String, Double>()
            try {
                val json = JSONObject(categoryLimitsStr)
                json.keys().forEach { key ->
                    categoryBudgetsLimitMap[key] = json.getDouble(key)
                }
            } catch (e: Exception) { e.printStackTrace() }

            var totalSpend = 0.0
            val categorySpendMap = mutableMapOf<String, Double>()

            val currentMonth = selectedCalendar.get(Calendar.MONTH) + 1 
            val currentYear = selectedCalendar.get(Calendar.YEAR)
            
            for (txStr in txList) {
                if (txStr.isBlank()) continue
                try {
                    val tx = JSONObject(txStr)
                    val type = tx.optString("type", "").uppercase()
                    
                    // Filter out Income/Credits - Case insensitive check via uppercase()
                    if (type == "INCOME" || type == "CREDIT") continue

                    val amt = tx.optDouble("amount", 0.0)
                    val cat = tx.optString("category", "Others")
                    
                    // Filter out Lends - User requested to not consider them in budget
                    val isLend = tx.optBoolean("is_lend", false) || cat == "Lend"
                    if (isLend) continue

                    val savedAt = tx.optString("saved_at", "")
                    val dateTime = tx.optString("date_time", "")

                    var isCurrentMonth = false
                    
                    if (savedAt.isNotEmpty()) {
                        val datePart = savedAt.split("T").getOrNull(0) ?: ""
                        val parts = datePart.split("-")
                        if (parts.size >= 2) {
                            val txYear = parts[0].toIntOrNull() ?: 0
                            val txMonth = parts[1].toIntOrNull() ?: 0
                            if (txYear == currentYear && txMonth == currentMonth) isCurrentMonth = true
                        }
                    } else if (dateTime.isNotEmpty()) {
                        val datePart = dateTime.split(" ").getOrNull(0) ?: ""
                        val parts = datePart.split("-")
                        if (parts.size >= 3) {
                            val txYear = parts[2].toIntOrNull() ?: 0
                            val txMonth = parts[1].toIntOrNull() ?: 0
                            if (txYear == currentYear && txMonth == currentMonth) isCurrentMonth = true
                        }
                    } else {
                        isCurrentMonth = true
                    }

                    if (isCurrentMonth) {
                        totalSpend += amt
                        categorySpendMap[cat] = (categorySpendMap[cat] ?: 0.0) + amt
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

        // --- Render Overall Budget Card ---
        val nf = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        nf.maximumFractionDigits = 0

        binding.tvTotalSpent.text = nf.format(totalSpend)
        binding.tvTotalLimit.text = getString(R.string.label_of_amount, overallLimit.toInt().toString())
        
        if (overallLimit > 0) {
            val percentage = ((totalSpend / overallLimit) * 100).coerceAtMost(100.0).toInt()
            binding.tvPercentage.text = getString(R.string.label_percentage_used, percentage)
            binding.tvMaxLimit.text = "\u20B9${overallLimit.toInt()}"

            val left = overallLimit - totalSpend
            if (left >= 0) {
                binding.tvTotalLeft.text = getString(R.string.label_amount_left, left.toInt().toString())
                binding.tvTotalLeft.setTextColor(Color.WHITE)
                // Respect black background, use progress color for status
                binding.vProgressFill.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
            } else {
                val exceededBy = totalSpend - overallLimit
                binding.tvTotalLeft.text = getString(R.string.label_exceeded_by, exceededBy.toInt().toString())
                binding.tvTotalLeft.setTextColor(Color.parseColor("#FF5252")) // Bright Red
                binding.vProgressFill.setBackgroundColor(Color.parseColor("#FF5252")) // Red
            }

            // Adjust progress width dynamically
            val fillLayoutParams = binding.vProgressFill.layoutParams as FrameLayout.LayoutParams
            fillLayoutParams.width = if (percentage == 100) {
                FrameLayout.LayoutParams.MATCH_PARENT
            } else {
                // Approximate 0dp to parent 
                0 // Need a post calculation or dynamic layout handling but for basic view it's better to set a Weight using LinearLayout. Let's fix that below by changing FrameLayout to LinearLayout if needed, or by setting width mathematically
            }
            binding.vProgressFill.layoutParams = fillLayoutParams

            // Set via layout weight for accurate percentage
            binding.progressContainer.removeAllViews()
            val progressView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    width = 0 // Wait for sizing
                }
                setBackgroundColor(if (left>=0) Color.parseColor("#F48FB1") else Color.WHITE)
            }
            // Better: use LinearLayout for weight
        } else {
            binding.tvTotalLeft.text = getString(R.string.label_limit_not_set)
            binding.tvPercentage.text = getString(R.string.label_tap_to_set)
            binding.tvMaxLimit.visibility = View.GONE
        }
        
        // Wait, fixing the progress bar width issue:
        updateProgressWidth(binding.progressContainer, binding.vProgressFill, totalSpend, overallLimit.toDouble())

        // --- Render Category Items ---
        binding.categoryListContainer.removeAllViews()

        // Combine categories that have either a limit OR a spend
        val allRelevantCats = (categoryBudgetsLimitMap.keys + categorySpendMap.keys).distinct()
        
        if (allRelevantCats.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = getString(R.string.msg_no_budget_tracking)
                setTextColor(Color.GRAY)
                setPadding(16, 16, 16, 16)
            }
            binding.categoryListContainer.addView(emptyView)
            return
        }

        allRelevantCats.forEachIndexed { index, cat ->
            val limit = categoryBudgetsLimitMap[cat] ?: 0.0
            val spend = categorySpendMap[cat] ?: 0.0

            if (limit == 0.0 && spend == 0.0) return@forEachIndexed

            val itemView = layoutInflater.inflate(R.layout.item_budget_category, binding.categoryListContainer, false)
            
            // Hide top divider for first item
            if (index == 0) {
                itemView.findViewById<View>(R.id.vTopDivider).visibility = View.GONE
            }

            val tvCatName = itemView.findViewById<TextView>(R.id.tvCategoryName)
            val tvRemaining = itemView.findViewById<TextView>(R.id.tvRemaining)
            val tvSpent = itemView.findViewById<TextView>(R.id.tvSpentAmount)
            val tvLimit = itemView.findViewById<TextView>(R.id.tvLimitAmount)
            val progressContainer = itemView.findViewById<FrameLayout>(R.id.categoryProgressContainer)
            val progressFill = itemView.findViewById<View>(R.id.vCategoryProgressFill)

            tvCatName.text = cat
            tvSpent.text = nf.format(spend)
            
            if (limit > 0) {
                tvLimit.text = "/ ${nf.format(limit)}"
                
                val remaining = limit - spend
                if (remaining >= 0) {
                    tvRemaining.text = getString(R.string.label_amount_remaining, nf.format(remaining))
                    tvRemaining.setTextColor(Color.parseColor("#B0BEC5"))
                    tvSpent.setTextColor(Color.WHITE)
                    progressFill.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
                } else {
                    val exceeded = spend - limit
                    tvRemaining.text = getString(R.string.label_exceeded_by, nf.format(exceeded))
                    tvRemaining.setTextColor(Color.parseColor("#F44336")) // Red text
                    tvSpent.setTextColor(Color.parseColor("#F44336")) // Red spent value
                    progressFill.setBackgroundColor(Color.parseColor("#F44336")) // Red progress bar
                }
                
            updateProgressWidth(progressContainer, progressFill, spend, limit)
            
            // Set Category Icon and Color
            val tvIcon = itemView.findViewById<TextView>(R.id.ivIcon)
            tvIcon.text = categoryEmoji(cat)
            val iconBgColor = categoryIconColor(cat)
            tvIcon.background?.mutate()?.let {
                if (it is android.graphics.drawable.GradientDrawable) {
                    it.setColor(ContextCompat.getColor(requireContext(), iconBgColor))
                }
            }
        } else {
                tvLimit.text = ""
                tvRemaining.text = getString(R.string.label_no_limit_set)
                progressFill.layoutParams.width = 0
            }

            // Click on category to edit limit
            itemView.setOnClickListener {
                showSetCategoryLimitDialog(cat, limit)
            }

            binding.categoryListContainer.addView(itemView)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

    private fun updateProgressWidth(container: FrameLayout, fill: View, spend: Double, limit: Double) {
        if (limit <= 0) return
        container.post {
            val totalWidth = container.width
            val percentage = (spend / limit).coerceAtMost(1.0)
            val fillWidth = (totalWidth * percentage).toInt()
            
            val lp = fill.layoutParams as FrameLayout.LayoutParams
            lp.width = fillWidth
            fill.layoutParams = lp
        }
    }

    private fun showOverallBudgetDialog() {
        val input = EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_set_overall_budget_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val amt = input.text.toString().toFloatOrNull() ?: 0f
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setFloat(SettingsManager.OVERALL_BUDGET_LIMIT, amt)
                    // No need to call refreshData, collector above will trigger
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showAddCategoryDialog() {
        // Simple implementation: Ask user to pick a category then set limit
        val types = defaultCategories.toTypedArray()
        var selectedItem = 0
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_select_category_title))
            .setSingleChoiceItems(types, 0) { _, which ->
                selectedItem = which
            }
            .setPositiveButton(getString(R.string.btn_next)) { _, _ ->
                showSetCategoryLimitDialog(types[selectedItem], 0.0)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showSetCategoryLimitDialog(cat: String, oldLimit: Double) {
        val input = EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (oldLimit > 0) {
            input.setText(oldLimit.toInt().toString())
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_set_category_limit_title, cat))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val limit = input.text.toString().toDoubleOrNull() ?: 0.0
                saveCategoryLimit(cat, limit)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun saveCategoryLimit(cat: String, limit: Double) {
        viewLifecycleOwner.lifecycleScope.launch {
            val categoryLimitsStr = settingsManager.categoryBudgetLimits.first().let { 
                if (it.isEmpty()) "{}" else it 
            }
            val json = try { JSONObject(categoryLimitsStr) } catch (e: Exception) { JSONObject() }
            
            if (limit > 0) {
                json.put(cat, limit)
            } else {
                json.remove(cat)
            }
            
            settingsManager.setString(SettingsManager.CATEGORY_BUDGET_LIMITS, json.toString())
            // No need to call refreshData, collector above will trigger
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun categoryEmoji(category: String): String = when (category.lowercase()) {
        "food"          -> "🍽"
        "entertainment" -> "🎬"
        "shopping"      -> "🛍"
        "transport"     -> "🚌"
        "bills"         -> "📄"
        "health"        -> "💊"
        "investment"    -> "📈"
        else            -> "💳"
    }

    private fun categoryIconColor(category: String): Int = when (category.lowercase()) {
        "food"          -> R.color.cat_food
        "entertainment" -> R.color.cat_entertainment
        "shopping"      -> R.color.cat_shopping
        "transport"     -> R.color.cat_transport
        "bills"         -> R.color.cat_bills
        "health"        -> R.color.cat_health
        "investment"    -> R.color.cat_investment
        else            -> R.color.cat_others
    }
}
