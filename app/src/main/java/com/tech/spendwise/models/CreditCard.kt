package com.tech.spendwise.models

import kotlinx.serialization.Serializable

@Serializable
data class CreditCard(
    val id: String? = null,
    val name: String,         // Encrypted
    val last4: String,       // Encrypted
    val limit: Double,       // Encrypted
    val paybackDay: Int,      // 1-31
    val reminderEnabled: Boolean = true,
    val uid: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
