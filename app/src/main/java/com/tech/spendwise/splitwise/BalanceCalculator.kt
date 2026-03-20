package com.tech.spendwise.splitwise

import com.tech.spendwise.models.Settlement
import com.tech.spendwise.models.SplitExpense
import kotlin.math.abs
import kotlin.math.min

/**
 * Calculates simplified "who owes whom" from a list of expenses and settlements.
 *
 * Algorithm:
 * 1. For each expense, the payer gets +share for every other member's portion,
 *    and each non-payer gets -theirShare.
 * 2. Settlements are applied (from gets +amount, to gets -amount).
 * 3. Net balances are simplified into minimum transfers.
 */
object BalanceCalculator {

    data class Debt(val from: String, val to: String, val amount: Double)

    /**
     * Returns a list of simplified debts: "A owes B ₹X".
     */
    fun calculateDebts(
        expenses: List<SplitExpense>,
        settlements: List<Settlement>
    ): List<Debt> {
        val netBalance = mutableMapOf<String, Double>() // positive = is owed, negative = owes

        // Process expenses
        for (expense in expenses) {
            for ((member, share) in expense.splitAmong) {
                if (member == expense.paidBy) {
                    // Payer is owed the total minus their own share
                    netBalance[member] = (netBalance[member] ?: 0.0) + (expense.amount - share)
                } else {
                    // Non-payer owes their share
                    netBalance[member] = (netBalance[member] ?: 0.0) - share
                }
            }
        }

        // Process settlements
        for (settlement in settlements) {
            netBalance[settlement.from] = (netBalance[settlement.from] ?: 0.0) + settlement.amount
            netBalance[settlement.to] = (netBalance[settlement.to] ?: 0.0) - settlement.amount
        }

        // Simplify debts using greedy algorithm
        return simplify(netBalance)
    }

    private fun simplify(netBalance: Map<String, Double>): List<Debt> {
        val creditors = mutableListOf<Pair<String, Double>>() // positive balance
        val debtors = mutableListOf<Pair<String, Double>>()   // negative balance

        for ((member, balance) in netBalance) {
            if (balance > 0.01) creditors.add(member to balance)
            else if (balance < -0.01) debtors.add(member to abs(balance))
        }

        // Sort descending so largest debts are settled first
        creditors.sortByDescending { it.second }
        debtors.sortByDescending { it.second }

        val debts = mutableListOf<Debt>()
        var ci = 0
        var di = 0

        val cBalances = creditors.map { it.second }.toMutableList()
        val dBalances = debtors.map { it.second }.toMutableList()

        while (ci < creditors.size && di < debtors.size) {
            val transfer = min(cBalances[ci], dBalances[di])
            if (transfer > 0.01) {
                debts.add(Debt(debtors[di].first, creditors[ci].first, Math.round(transfer * 100.0) / 100.0))
            }
            cBalances[ci] -= transfer
            dBalances[di] -= transfer
            if (cBalances[ci] < 0.01) ci++
            if (dBalances[di] < 0.01) di++
        }

        return debts
    }
}
