package com.tech.spendwise

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentBudgetAlertsBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

class BudgetAlertsFragment : Fragment() {

    private var _binding: FragmentBudgetAlertsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager

    // Predefined categories for budgets (matching transaction categories)
    private val defaultCategories = listOf(
        "Food", "Travel", "Shopping", "Entertainment", "Health", "Education", "Bills", "Gifts", "Others"
    )

    // Map to keep track of the dynamically added category EditTexts
    private val categoryInputMap = mutableMapOf<String, TextInputEditText>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBudgetAlertsBinding.inflate(inflater, container, false)
        settingsManager = SettingsManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnSave.setOnClickListener {
            saveBudgets()
        }

        createCategoryInputs()
        loadBudgets()
    }

    private fun createCategoryInputs() {
        binding.categoryContainer.removeAllViews()
        categoryInputMap.clear()

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (16 * resources.displayMetrics.density).toInt() // 16dp
        }

        for (category in defaultCategories) {
            val titleView = TextView(requireContext()).apply {
                text = category
                setTextColor(resources.getColor(R.color.black, null))
                textSize = 14f
                setPadding(0, 0, 0, 8)
            }

            val inputLayout = TextInputLayout(requireContext(), null, com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox).apply {
                layoutParams = params
                prefixText = "₹"
            }

            val editText = TextInputEditText(inputLayout.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = getString(R.string.amount)
            }

            inputLayout.addView(editText)
            
            binding.categoryContainer.addView(titleView)
            binding.categoryContainer.addView(inputLayout)

            categoryInputMap[category] = editText
        }
    }

    private fun loadBudgets() {
        lifecycleScope.launch {
            // Load overall budget
            val overallBudget = settingsManager.overallBudgetLimit.first()
            if (overallBudget > 0f) {
                binding.etOverallBudget.setText(overallBudget.toString())
            }

            // Load category budgets
            val categoryJsonStr = settingsManager.categoryBudgetLimits.first()
            if (categoryJsonStr.isNotEmpty() && categoryJsonStr != "{}") {
                try {
                    val json = JSONObject(categoryJsonStr)
                    for (category in defaultCategories) {
                        if (json.has(category)) {
                            val limit = json.getDouble(category)
                            if (limit > 0) {
                                categoryInputMap[category]?.setText(limit.toString())
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun saveBudgets() {
        // Hide keyboard
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)

        lifecycleScope.launch {
            // Save overall budget
            val overallText = binding.etOverallBudget.text.toString()
            val overallBudget = overallText.toFloatOrNull() ?: 0f
            settingsManager.setFloat(SettingsManager.OVERALL_BUDGET_LIMIT, overallBudget)

            // Save category budgets
            val categoryJson = JSONObject()
            for ((category, editText) in categoryInputMap) {
                val limitText = editText.text.toString()
                val limit = limitText.toDoubleOrNull() ?: 0.0
                if (limit > 0) {
                    categoryJson.put(category, limit)
                }
            }
            settingsManager.setString(SettingsManager.CATEGORY_BUDGET_LIMITS, categoryJson.toString())

            Snackbar.make(binding.root, R.string.budgets_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
