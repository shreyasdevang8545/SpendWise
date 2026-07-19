package com.tech.spendwise.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class Transaction(
    val id: String? = null,
    val amount: Double,
    val type: String, // "CREDIT" or "DEBIT"
    val merchant: String,
    val category: String,
    val paymentMode: String? = null,
    val currency: String = "INR",
    val savedAt: String, // ISO timestamp
    val createdAt: Long = System.currentTimeMillis(),
    val isLend: Boolean = false,
    val note: String? = null,
    val creditCardId: String? = null,
    val groupId: String? = null
) : Parcelable
