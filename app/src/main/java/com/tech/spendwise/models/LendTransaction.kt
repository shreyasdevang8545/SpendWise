package com.tech.spendwise.models

/**
 * Represents a lend transaction where money is given to someone.
 */
data class LendTransaction(
    val id: String? = null,
    val name: String,
    val amount: Double,
    val paymentMode: String,
    val returnDate: Long, // committed return date timestamp
    val phoneNumber: String? = null,
    val note: String? = null,
    val isReturned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val transactionId: String? = null
)
