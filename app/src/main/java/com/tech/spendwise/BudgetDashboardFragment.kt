package com.tech.spendwise

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.tech.spendwise.databinding.FragmentBudgetDashboardBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class BudgetDashboardFragment : Fragment() {

    private var _binding: FragmentBudgetDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager
    private lateinit var repo: PreferenceRepository

    // Standard hardcoded categories for missing defaults based on mockup
    private val defaultCategories = listOf(
        "Food", "Travel", "Shopping", "Entertainment", "Health", "Education", "Bills", "Gifts", "Others", "Utilities", "Transport"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBudgetDashboardBinding.inflate(inflater, container, false)
        settingsManager = SettingsManager(requireContext())
        repo = PreferenceRepository(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Wait for data calculation
        viewLifecycleOwner.lifecycleScope.launch {
            calculateAndDisplayBudgets()
        }

        binding.btnAddCategory.setOnClickListener {
            showAddCategoryDialog()
        }

        binding.overallBudgetCard.setOnClickListener {
            showOverallBudgetDialog()
        }

        binding.backBtn.setOnClickListener {
            findNavController().navigateUp()
        }

        setupDateDisplay()
    }

    private fun setupDateDisplay() {
        val calendar = Calendar.getInstance()
        val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        binding.tvMonthYear.text = monthYearFormat.format(calendar.time)
    }

    private suspend fun calculateAndDisplayBudgets() {
        try {
            // Fetch budget limits
            val overallLimit = settingsManager.overallBudgetLimit.first()
            val categoryLimitsStr = settingsManager.categoryBudgetLimits.first().ifEmpty { "{}" }
            
            val categoryBudgetsLimitMap = mutableMapOf<String, Double>()
            try {
                val json = JSONObject(categoryLimitsStr)
                json.keys().forEach { key ->
                    categoryBudgetsLimitMap[key] = json.getDouble(key)
                }
            } catch (e: Exception) { e.printStackTrace() }

            // Fetch transaction amounts
            val txList = try { repo.confirmedTransactionsFlow.first() } catch (e: Exception) { emptyList<String>() }
            var totalSpend = 0.0
            val categorySpendMap = mutableMapOf<String, Double>()

            val calendar = Calendar.getInstance()
            val currentMonth = calendar.get(Calendar.MONTH) + 1 // 1-based (Jan=1, Mar=3)
            val currentYear = calendar.get(Calendar.YEAR)
            
            for (txStr in txList) {
                if (txStr.isBlank()) continue
                try {
                    val tx = JSONObject(txStr)
                    val type = tx.optString("type", "").uppercase()
                    
                    // Filter out Income/Credits
                    if (type == "INCOME" || type == "CREDIT") continue

                    val amt = tx.optDouble("amount", 0.0)
                    val cat = tx.optString("category", "Others")
                    val savedAt = tx.optString("saved_at", "") // yyyy-MM-dd'T'HH:mm:ss
                    val dateTime = tx.optString("date_time", "") // dd-MM-yyyy HH:mm:ss IST

                    var isCurrentMonth = false
                    
                    if (savedAt.isNotEmpty()) {
                        // saved_at: 2026-03-15T18:03:52
                        val datePart = savedAt.split("T").getOrNull(0) ?: ""
                        val parts = datePart.split("-")
                        if (parts.size >= 2) {
                            val txYear = parts[0].toIntOrNull() ?: 0
                            val txMonth = parts[1].toIntOrNull() ?: 0
                            if (txYear == currentYear && txMonth == currentMonth) isCurrentMonth = true
                        }
                    } else if (dateTime.isNotEmpty()) {
                        // date_time: 15-03-2026 18:03:52 IST
                        val datePart = dateTime.split(" ").getOrNull(0) ?: ""
                        val parts = datePart.split("-")
                        if (parts.size >= 3) {
                            val txYear = parts[2].toIntOrNull() ?: 0
                            val txMonth = parts[1].toIntOrNull() ?: 0
                            if (txYear == currentYear && txMonth == currentMonth) isCurrentMonth = true
                        }
                    } else {
                        // Fallback: If no date info, include it (treating as recent)
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
        binding.tvTotalLimit.text = "of \u20B9${overallLimit.toInt()}"
        
        if (overallLimit > 0) {
            val percentage = ((totalSpend / overallLimit) * 100).coerceAtMost(100.0).toInt()
            binding.tvPercentage.text = "$percentage% used"
            binding.tvMaxLimit.text = "\u20B9${overallLimit.toInt()}"

            val left = overallLimit - totalSpend
            if (left >= 0) {
                binding.tvTotalLeft.text = "\u20B9${left.toInt()} left"
                binding.tvTotalLeft.setTextColor(Color.WHITE)
                // Set green background for card, and pink progress
                binding.overallBudgetCard.setBackgroundColor(Color.parseColor("#388E3C"))
                binding.vProgressFill.setBackgroundColor(Color.parseColor("#F48FB1"))
            } else {
                val exceededBy = totalSpend - overallLimit
                binding.tvTotalLeft.text = "Exceeded by \u20B9${exceededBy.toInt()}"
                binding.tvTotalLeft.setTextColor(Color.parseColor("#FFCDD2")) // Red tint
                // Red theme if exceeded
                binding.overallBudgetCard.setBackgroundColor(Color.parseColor("#D32F2F"))
                binding.vProgressFill.setBackgroundColor(Color.WHITE)
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
            binding.tvTotalLeft.text = "Limit not set"
            binding.tvPercentage.text = "Tap to set"
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
                text = "No budget limits tracking yet."
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
                    tvRemaining.text = "${nf.format(remaining)} remaining"
                    tvRemaining.setTextColor(Color.parseColor("#B0BEC5"))
                    tvSpent.setTextColor(Color.WHITE)
                    progressFill.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
                } else {
                    val exceeded = spend - limit
                    tvRemaining.text = "Exceeded by ${nf.format(exceeded)}"
                    tvRemaining.setTextColor(Color.parseColor("#F44336")) // Red text
                    tvSpent.setTextColor(Color.parseColor("#F44336")) // Red spent value
                    progressFill.setBackgroundColor(Color.parseColor("#F44336")) // Red progress bar
                }
                
                updateProgressWidth(progressContainer, progressFill, spend, limit)
            } else {
                tvLimit.text = ""
                tvRemaining.text = "No limit set"
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
            .setTitle("Set Overall Monthly Budget")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val amt = input.text.toString().toFloatOrNull() ?: 0f
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsManager.setFloat(SettingsManager.OVERALL_BUDGET_LIMIT, amt)
                    calculateAndDisplayBudgets() // refresh UI
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddCategoryDialog() {
        // Simple implementation: Ask user to pick a category then set limit
        val types = defaultCategories.toTypedArray()
        var selectedItem = 0
        AlertDialog.Builder(requireContext())
            .setTitle("Select Category")
            .setSingleChoiceItems(types, 0) { _, which ->
                selectedItem = which
            }
            .setPositiveButton("Next") { _, _ ->
                showSetCategoryLimitDialog(types[selectedItem], 0.0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSetCategoryLimitDialog(cat: String, oldLimit: Double) {
        val input = EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (oldLimit > 0) {
            input.setText(oldLimit.toInt().toString())
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Set limit for $cat")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val limit = input.text.toString().toDoubleOrNull() ?: 0.0
                saveCategoryLimit(cat, limit)
            }
            .setNegativeButton("Cancel", null)
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
            calculateAndDisplayBudgets() // Refresh UI
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
