package com.tech.spendwise.splitwise

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.tech.spendwise.SupabaseInstance
import kotlinx.coroutines.launch
import com.tech.spendwise.R
import com.tech.spendwise.databinding.FragmentGroupDetailBinding
import com.tech.spendwise.models.Settlement
import com.tech.spendwise.models.SplitExpense
import com.tech.spendwise.utils.UIUtils
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

class GroupDetailFragment : Fragment() {

    private var _binding: FragmentGroupDetailBinding? = null
    private val binding get() = _binding!!
    private val splitRepository = SupabaseSplitRepository()

    private lateinit var expenseAdapter: SplitExpenseAdapter
    private var groupId = ""
    private var groupName = ""
    private var members = listOf<String>()
    private val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    // Cached data for share/settle
    private var currentDebts = listOf<BalanceCalculator.Debt>()
    private var memberMappings = mapOf<String, String>()
    private var currentUserMappedName: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGroupDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupId = arguments?.getString("groupId") ?: ""
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val uid = SupabaseInstance.currentUserId() ?: ""
        expenseAdapter = SplitExpenseAdapter(
            currentUserId = uid,
            memberMappings = emptyMap(),
            onEdit = { expense ->
                val bundle = Bundle().apply {
                    putString("groupId", groupId)
                    putString("expenseId", expense.id)
                }
                findNavController().navigate(R.id.action_groupDetail_to_addSplitExpense, bundle)
            },
            onDelete = { expense ->
                showDeleteExpenseDialog(expense)
            }
        )
        binding.rvExpenses.layoutManager = LinearLayoutManager(requireContext())
        binding.rvExpenses.adapter = expenseAdapter

        // Move invite to toolbar menu, so we hide the button
        binding.btnInviteGroup.visibility = View.GONE

        binding.fabAddExpense.setOnClickListener {
            val bundle = Bundle().apply { putString("groupId", groupId) }
            findNavController().navigate(R.id.action_groupDetail_to_addSplitExpense, bundle)
        }

        binding.btnSettleUp.setOnClickListener { showSettleDialog() }
        binding.btnShareSummary.setOnClickListener { shareSummary() }

        binding.toolbar.inflateMenu(R.menu.menu_group_detail)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_invite_group -> {
                    inviteOthers()
                    true
                }
                R.id.action_exit_group -> {
                    showExitGroupDialog()
                    true
                }
                else -> false
            }
        }

        loadGroupAndData()
    }

    override fun onResume() {
        super.onResume()
        loadGroupAndData()
    }

    private fun loadGroupAndData() {
        val uid = SupabaseInstance.currentUserId() ?: return
        lifecycleScope.launch {
            try {
                val groups = splitRepository.fetchGroups()
                val group = groups.find { it.id == groupId } ?: return@launch
                groupName = group.name
                members = group.members
                memberMappings = group.memberMappings
                val uid = SupabaseInstance.currentUserId() ?: ""
                currentUserMappedName = memberMappings[uid]?.split("|")?.firstOrNull()
                
                binding.toolbar.title = groupName
                expenseAdapter.updateMappings(group.memberMappings)
                // Now load expenses + settlements
                loadExpensesAndBalances()
            } catch (e: Exception) {
                Log.e("GroupDetail", "Error loading group", e)
            }
        }
    }

    private fun loadExpensesAndBalances() {
        lifecycleScope.launch {
            try {
                val expenses = splitRepository.fetchExpenses(groupId)
                val settlements = splitRepository.fetchSettlements(groupId)
                updateUI(expenses, settlements)
            } catch (e: Exception) {
                UIUtils.showErrorSnackbar(binding.root, "Failed to load expenses")
            }
        }
    }

    private fun updateUI(expenses: List<SplitExpense>, settlements: List<Settlement>) {
        // Update expenses list
        expenseAdapter.submitList(expenses)
        binding.tvNoExpenses.visibility = if (expenses.isEmpty()) View.VISIBLE else View.GONE

        // Calculate and display balances
        currentDebts = BalanceCalculator.calculateDebts(expenses, settlements)
        binding.balancesContainer.removeAllViews()

        if (currentDebts.isEmpty()) {
            binding.tvAllSettled.visibility = View.VISIBLE
        } else {
            binding.tvAllSettled.visibility = View.GONE
            for (debt in currentDebts) {
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_balance, binding.balancesContainer, false)

                val fromName = getRelativeName(debt.from)
                val toName = getRelativeName(debt.to)

                itemView.findViewById<TextView>(R.id.tvBalanceText).text =
                    "$fromName owes $toName ${fmt.format(debt.amount)}"

                itemView.findViewById<MaterialButton>(R.id.btnSettle).setOnClickListener {
                    settleDebt(debt)
                }

                binding.balancesContainer.addView(itemView)
            }
        }
    }

    private fun getRelativeName(originalName: String): String {
        val cleanName = originalName.split("|").firstOrNull() ?: originalName
        return if (cleanName == currentUserMappedName) "You" else cleanName
    }

    private fun settleDebt(debt: BalanceCalculator.Debt) {
        val fromName = getRelativeName(debt.from)
        val toName = getRelativeName(debt.to)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Settle Debt")
            .setMessage("Mark $fromName → $toName (${fmt.format(debt.amount)}) as settled?")
            .setPositiveButton("Settle") { _, _ ->
                val settlement = Settlement(
                    id = "", // Supabase will generate ID
                    groupId = groupId,
                    from = debt.from,
                    to = debt.to,
                    amount = debt.amount
                )
                lifecycleScope.launch {
                    val resultId = splitRepository.saveSettlement(settlement)
                    if (resultId != null) {
                        UIUtils.showSuccessSnackbar(binding.root, "Settled!")
                        loadGroupAndData()
                    } else {
                        UIUtils.showErrorSnackbar(binding.root, "Failed to settle")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettleDialog() {
        if (currentDebts.isEmpty()) {
            UIUtils.showSuccessSnackbar(binding.root, "All settled up! 🎉")
            return
        }
        // Show all debts for quick settle
        val items = currentDebts.map {
            val fromName = getRelativeName(it.from)
            val toName = getRelativeName(it.to)
            "$fromName  →  $toName  ${fmt.format(it.amount)}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Settle Up")
            .setItems(items) { _, which -> settleDebt(currentDebts[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteExpenseDialog(expense: SplitExpense) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Expense")
            .setMessage("Are you sure you want to delete \"${expense.description}\"?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    splitRepository.deleteExpense(expense.id)
                    loadGroupAndData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareSummary() {
        val sb = StringBuilder()
        sb.appendLine("📊 *$groupName — SpendWise Split*\n")

        if (currentDebts.isEmpty()) {
            sb.appendLine("✅ All settled up!")
        } else {
            sb.appendLine("*Outstanding:*")
            for (debt in currentDebts) {
                val fromName = getRelativeName(debt.from)
                val toName = getRelativeName(debt.to)
                sb.appendLine("  • $fromName owes $toName: ₹${"%.0f".format(debt.amount)}")
            }
        }
        sb.appendLine("\n— Sent via SpendWise 💚")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "Share Summary"))
    }

    private fun showExitGroupDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Exit Group")
            .setMessage("Are you sure you want to leave this group? You will no longer be able to see its expenses or balances.")
            .setPositiveButton("Exit") { _, _ ->
                performExitGroup()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performExitGroup() {
        lifecycleScope.launch {
            try {
                splitRepository.exitGroup(groupId)
                UIUtils.showSuccessSnackbar(binding.root, "You have left the group")
                findNavController().popBackStack()
            } catch (e: Exception) {
                UIUtils.showErrorSnackbar(binding.root, "Failed to exit group")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun inviteOthers() {
        val inviteLink = "https://shreyasdevang8545.github.io/SpendWise/join.html?id=$groupId"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Join my Splitwise group: $groupName")
            putExtra(Intent.EXTRA_TEXT, "Hey! Join my Splitwise group '$groupName' on SpendWise to track our expenses together: $inviteLink")
        }
        startActivity(Intent.createChooser(shareIntent, "Invite via"))
    }
}
