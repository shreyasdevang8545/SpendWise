package com.tech.spendwise.models

import kotlinx.serialization.Serializable

@Serializable
data class Feedback(
    val id: String? = null,
    val uid: String,
    val message: String,
    val reply: String? = null,
    val status: String = "pending", // pending, replied
    val created_at: Long = System.currentTimeMillis(),
    val updated_at: Long = System.currentTimeMillis()
)
