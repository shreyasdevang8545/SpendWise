package com.tech.spendwise.models

/**
 * A shared expense inside a [SplitGroup].
 * [splitAmong] maps each member name to their share amount.
 */
data class SplitExpense(
    val id: String = "",
    val groupId: String = "",
    val description: String = "",
    val amount: Double = 0.0,
    val paidBy: String = "",
    val splitAmong: Map<String, Double> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val uid: String = ""
)
