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
import com.google.firebase.auth.FirebaseAuth
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

    private val expenseAdapter = SplitExpenseAdapter()
    private var groupId = ""
    private var groupName = ""
    private var members = listOf<String>()
    private val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    // Cached data for share/settle
    private var currentDebts = listOf<BalanceCalculator.Debt>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGroupDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupId = arguments?.getString("groupId") ?: ""
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.rvExpenses.layoutManager = LinearLayoutManager(requireContext())
        binding.rvExpenses.adapter = expenseAdapter

        binding.fabAddExpense.setOnClickListener {
            val bundle = Bundle().apply { putString("groupId", groupId) }
            findNavController().navigate(R.id.action_groupDetail_to_addSplitExpense, bundle)
        }

        binding.btnSettleUp.setOnClickListener { showSettleDialog() }
        binding.btnShareSummary.setOnClickListener { shareSummary() }

        loadGroupAndData()
    }

    override fun onResume() {
        super.onResume()
        loadGroupAndData()
    }

    private fun loadGroupAndData() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Load group info first
        SplitRepository.fetchGroups(uid) { result ->
            result.onSuccess { groups ->
                val group = groups.find { it.id == groupId } ?: return@onSuccess
                groupName = group.name
                members = group.members
                activity?.runOnUiThread {
                    binding.toolbar.title = groupName
                }
                // Now load expenses + settlements
                loadExpensesAndBalances(uid)
            }
        }
    }

    private fun loadExpensesAndBalances(uid: String) {
        SplitRepository.fetchExpenses(uid, groupId) { expResult ->
            expResult.onSuccess { expenses ->
                SplitRepository.fetchSettlements(uid, groupId) { setResult ->
                    setResult.onSuccess { settlements ->
                        activity?.runOnUiThread {
                            updateUI(expenses, settlements)
                        }
                    }
                }
            }
            expResult.onFailure {
                activity?.runOnUiThread {
                    UIUtils.showErrorSnackbar(binding.root, "Failed to load expenses")
                }
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

                itemView.findViewById<TextView>(R.id.tvBalanceText).text =
                    "${debt.from} owes ${debt.to} ${fmt.format(debt.amount)}"

                itemView.findViewById<MaterialButton>(R.id.btnSettle).setOnClickListener {
                    settleDebt(debt)
                }

                binding.balancesContainer.addView(itemView)
            }
        }
    }

    private fun settleDebt(debt: BalanceCalculator.Debt) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Settle Debt")
            .setMessage("Mark ${debt.from} → ${debt.to} (${fmt.format(debt.amount)}) as settled?")
            .setPositiveButton("Settle") { _, _ ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@setPositiveButton
                val settlement = Settlement(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    from = debt.from,
                    to = debt.to,
                    amount = debt.amount
                )
                SplitRepository.saveSettlement(uid, settlement) { result ->
                    activity?.runOnUiThread {
                        if (result.isSuccess) {
                            UIUtils.showSuccessSnackbar(binding.root, "Settled!")
                            loadGroupAndData()
                        } else {
                            UIUtils.showErrorSnackbar(binding.root, "Failed to settle")
                        }
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
            "${it.from}  →  ${it.to}  ${fmt.format(it.amount)}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Settle Up")
            .setItems(items) { _, which -> settleDebt(currentDebts[which]) }
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
                sb.appendLine("  • ${debt.from} owes ${debt.to}: ₹${"%.0f".format(debt.amount)}")
            }
        }
        sb.appendLine("\n— Sent via SpendWise 💚")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "Share Summary"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
