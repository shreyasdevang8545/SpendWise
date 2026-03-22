package com.tech.spendwise.splitwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import androidx.lifecycle.lifecycleScope
import com.tech.spendwise.SupabaseInstance
import kotlinx.coroutines.launch
import com.tech.spendwise.R
import com.tech.spendwise.databinding.FragmentAddSplitExpenseBinding
import com.tech.spendwise.models.SplitExpense
import com.tech.spendwise.utils.UIUtils
import java.util.UUID

class AddSplitExpenseFragment : Fragment() {

    private var _binding: FragmentAddSplitExpenseBinding? = null
    private val binding get() = _binding!!
    private val splitRepository = SupabaseSplitRepository()

    private var groupId = ""
    private var expenseId: String? = null
    private var existingExpense: SplitExpense? = null
    private var members = listOf<String>()
    private val customInputs = mutableMapOf<String, EditText>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddSplitExpenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupId = arguments?.getString("groupId") ?: ""
        expenseId = arguments?.getString("expenseId")
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        if (expenseId != null) {
            binding.toolbar.title = "Edit Expense"
            binding.btnSaveExpense.text = "Update Expense"
        }

        // Load group to get members
        val uid = SupabaseInstance.currentUserId() ?: return
        lifecycleScope.launch {
            try {
                val groups = splitRepository.fetchGroups()
                val group = groups.find { it.id == groupId } ?: return@launch
                members = group.members
                
                if (expenseId != null) {
                    val expenses = splitRepository.fetchExpenses(groupId)
                    existingExpense = expenses.find { it.id == expenseId }
                }
                
                setupPaidByChips()
                existingExpense?.let { preFillUI(it) }
            } catch (e: Exception) {
                Log.e("AddSplitExpense", "Error loading group/expense", e)
            }
        }

        // Split type toggle
        binding.chipGroupSplitType.setOnCheckedStateChangeListener { _, checkedIds ->
            val isCustom = checkedIds.contains(R.id.chipCustom)
            binding.customSplitContainer.visibility = if (isCustom) View.VISIBLE else View.GONE
        }

        binding.btnSaveExpense.setOnClickListener { saveExpense() }
    }

    private fun setupPaidByChips() {
        binding.chipGroupPaidBy.removeAllViews()
        for ((index, member) in members.withIndex()) {
            val displayName = member.split("|").firstOrNull() ?: member
            val chip = Chip(requireContext()).apply {
                text = displayName
                tag = member // Store full string in tag
                isCheckable = true
                isChecked = index == 0
                setChipBackgroundColorResource(R.color.chip_background)
                setTextColor(resources.getColor(R.color.text_primary, null))
            }
            binding.chipGroupPaidBy.addView(chip)
        }

        // Build custom split inputs
        binding.customSplitContainer.removeAllViews()
        customInputs.clear()
        for (member in members) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
            }

            val displayName = member.split("|").firstOrNull() ?: member
            val label = TextView(requireContext()).apply {
                text = displayName
                setTextColor(resources.getColor(R.color.text_primary, null))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val et = EditText(requireContext()).apply {
                hint = "₹"
                setHintTextColor(resources.getColor(R.color.text_secondary, null))
                setTextColor(resources.getColor(R.color.text_primary, null))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                textSize = 14f
                setPadding(24, 16, 24, 16)
                setBackgroundResource(R.drawable.bg_edit_text_outlined)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(label)
            row.addView(et)
            binding.customSplitContainer.addView(row)
            customInputs[member] = et
        }
    }

    private fun preFillUI(expense: SplitExpense) {
        binding.etDescription.setText(expense.description)
        binding.etAmount.setText(expense.amount.toString())

        // Set paid by chip
        for (i in 0 until binding.chipGroupPaidBy.childCount) {
            val chip = binding.chipGroupPaidBy.getChildAt(i) as Chip
            if (chip.tag == expense.paidBy) {
                chip.isChecked = true
                break
            }
        }

        // Set split values if custom
        if (expense.splitAmong.size == members.size) {
            // Check if equal split (optional optimization)
        }
        
        // Populate custom inputs anyway
        for ((member, share) in expense.splitAmong) {
            customInputs[member]?.setText(share.toString())
        }
        
        // Show custom container if needed
        // Assuming user might want to see custom split if it was saved that way
        // But for now let's just leave it to the user to toggle if they want to edit specific values
    }

    private fun saveExpense() {
        val description = binding.etDescription.text.toString().trim()
        if (description.isEmpty()) {
            binding.descriptionLayout.error = "Enter a description"
            return
        }
        binding.descriptionLayout.error = null

        val amount = binding.etAmount.text.toString().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            binding.amountLayout.error = "Enter a valid amount"
            return
        }
        binding.amountLayout.error = null

        // Find who paid
        val paidByChipId = binding.chipGroupPaidBy.checkedChipId
        if (paidByChipId == View.NO_ID) {
            UIUtils.showErrorSnackbar(binding.root, "Select who paid")
            return
        }
        val paidByChip = binding.chipGroupPaidBy.findViewById<Chip>(paidByChipId)
        val paidBy = paidByChip.tag as String // Use full string from tag

        // Calculate split
        val splitAmong: Map<String, Double>
        val isCustom = binding.chipGroupSplitType.checkedChipIds.contains(R.id.chipCustom)

        if (isCustom) {
            val custom = mutableMapOf<String, Double>()
            for ((member, et) in customInputs) {
                val share = et.text.toString().toDoubleOrNull() ?: 0.0
                if (share > 0) custom[member] = share
            }
            if (custom.isEmpty()) {
                UIUtils.showErrorSnackbar(binding.root, "Enter at least one custom share")
                return
            }
            splitAmong = custom
        } else {
            // Equal split among all members
            val perPerson = Math.round(amount / members.size * 100.0) / 100.0
            splitAmong = members.associateWith { perPerson }
        }

        val uid = SupabaseInstance.currentUserId()
        if (uid == null) {
            UIUtils.showErrorSnackbar(binding.root, "Please login first")
            return
        }

        val expense = SplitExpense(
            id = expenseId ?: "", // Use existing ID if editing
            groupId = groupId,
            description = description,
            amount = amount,
            paidBy = paidBy,
            splitAmong = splitAmong,
            createdAt = existingExpense?.createdAt ?: System.currentTimeMillis(),
            uid = existingExpense?.uid ?: (SupabaseInstance.currentUserId() ?: "")
        )

        lifecycleScope.launch {
            val resultId = splitRepository.saveExpense(expense)
            if (resultId != null) {
                UIUtils.showSuccessSnackbar(binding.root, "Expense added!")
                findNavController().popBackStack()
            } else {
                UIUtils.showErrorSnackbar(binding.root, "Failed to save expense")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
