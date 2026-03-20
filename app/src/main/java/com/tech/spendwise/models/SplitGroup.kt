package com.tech.spendwise.models

/**
 * A Splitwise-style group for splitting expenses among members.
 */
data class SplitGroup(
    val id: String = "",
    val name: String = "",
    val members: List<String> = emptyList(), // includes "You"
    val createdAt: Long = System.currentTimeMillis()
)
