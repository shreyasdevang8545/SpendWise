package com.tech.spendwise.models

/**
 * A Splitwise-style group for splitting expenses among members.
 */
data class SplitGroup(
    val id: String = "",
    val name: String = "",
    val uid: String = "",
    val members: List<String> = emptyList(),
    val memberMappings: Map<String, String> = emptyMap(), // UID -> Name
    val participants: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
