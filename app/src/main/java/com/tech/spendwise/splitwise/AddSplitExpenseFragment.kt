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
    private var members = listOf<String>()
    private val customInputs = mutableMapOf<String, EditText>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddSplitExpenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupId = arguments?.getString("groupId") ?: ""
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Load group to get members
        val uid = SupabaseInstance.currentUserId() ?: return
        lifecycleScope.launch {
            try {
                val groups = splitRepository.fetchGroups()
                val group = groups.find { it.id == groupId } ?: return@launch
                members = group.members
                setupPaidByChips()
            } catch (e: Exception) {
                Log.e("AddSplitExpense", "Error loading group", e)
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
            val chip = Chip(requireContext()).apply {
                text = member
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

            val label = TextView(requireContext()).apply {
                text = member
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
        val paidBy = binding.chipGroupPaidBy.findViewById<Chip>(paidByChipId).text.toString()

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
            id = "", // Supabase will generate ID
            groupId = groupId,
            description = description,
            amount = amount,
            paidBy = paidBy,
            splitAmong = splitAmong
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
