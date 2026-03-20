package com.tech.spendwise.models

/**
 * Records that [from] has settled a debt of [amount] with [to] inside a [SplitGroup].
 */
data class Settlement(
    val id: String = "",
    val groupId: String = "",
    val from: String = "",
    val to: String = "",
    val amount: Double = 0.0,
    val settledAt: Long = System.currentTimeMillis()
)
