package com.tech.spendwise.splitwise

import android.util.Log
import com.tech.spendwise.SupabaseInstance
import com.tech.spendwise.models.Settlement
import com.tech.spendwise.models.SplitExpense
import com.tech.spendwise.models.SplitGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.jan.supabase.postgrest.*
import io.github.jan.supabase.postgrest.query.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double

/**
 * Supabase-backed repository for Splitwise features.
 * Replaces the static SplitRepository object.
 */
class SupabaseSplitRepository {

    private val postgrest = SupabaseInstance.client.postgrest
    private val TAG = "SupabaseSplitRepo"

    // ── Groups ─────────────────────────────────────────────────────────────

    suspend fun saveGroup(group: SplitGroup): String? = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext null
        try {
            val json = buildJsonObject {
                put("uid", uid)
                put("name", group.name)
                put("members", buildJsonArray { group.members.forEach { add(it) } })
                
                // If participants specified in model, use them; otherwise use creator
                val participantsToSave = if (group.participants.isNotEmpty()) {
                    group.participants
                } else {
                    listOf(uid)
                }
                put("participants", buildJsonArray { participantsToSave.forEach { add(it) } })

                // Map the creator if not already mapped
                val currentMappings = group.memberMappings.toMutableMap()
                if (!currentMappings.containsKey(uid)) {
                    val firstMember = group.members.firstOrNull() ?: "You"
                    currentMappings[uid] = firstMember
                }
                
                put("member_mappings", buildJsonObject {
                    currentMappings.forEach { (k, v) -> put(k, v) }
                })
                put("created_at", group.createdAt)
            }
            
            val response = if (group.id.isNotEmpty()) {
                postgrest.from("split_groups").update(json) {
                    filter { eq("id", group.id) }
                    select()
                }.decodeSingle<JsonObject>()
            } else {
                postgrest.from("split_groups").insert(json) {
                    select()
                }.decodeSingle<JsonObject>()
            }
            response["id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "Error saving group", e)
            null
        }
    }

    suspend fun fetchGroups(): List<SplitGroup> = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext emptyList()
        try {
            val result = postgrest.from("split_groups")
                .select {
                    filter {
                        or {
                            eq("uid", uid)
                            cs("participants", buildJsonArray { add(uid) })
                        }
                    }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()
            
            result.map { obj ->
                val members = obj["members"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val participants = obj["participants"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val mappingObj = obj["member_mappings"]?.jsonObject
                val mappings = mappingObj?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

                val createdAt = parseIsoDateToLong(obj["created_at"]?.jsonPrimitive?.content)
                
                SplitGroup(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    uid = obj["uid"]?.jsonPrimitive?.content ?: "",
                    members = members,
                    memberMappings = mappings,
                    participants = participants,
                    createdAt = createdAt
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching groups", e)
            emptyList()
        }
    }

    suspend fun deleteGroup(groupId: String) = withContext(Dispatchers.IO) {
        try {
            postgrest.from("split_groups").delete { filter { eq("id", groupId) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting group", e)
        }
    }

    suspend fun joinGroup(groupId: String, selectedMemberName: String) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            val group = fetchGroupById(groupId) ?: return@withContext
            
            val participants = group.participants.toMutableList()
            val currentMappings = group.memberMappings.toMutableMap()

            if (!participants.contains(uid)) {
                participants.add(uid)
                currentMappings[uid] = selectedMemberName

                val updateData = buildJsonObject {
                    put("participants", buildJsonArray { participants.forEach { add(it) } })
                    put("member_mappings", buildJsonObject {
                        currentMappings.forEach { (k, v) -> put(k, v) }
                    })
                }

                postgrest.from("split_groups").update(updateData) {
                    filter { eq("id", groupId) }
                    select()
                }.decodeSingle<JsonObject>()
                
                Log.d(TAG, "Successfully joined group $groupId as $selectedMemberName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in joinGroup: ${e.message}", e)
            throw e
        }
    }

    suspend fun fetchGroupById(groupId: String): SplitGroup? = withContext(Dispatchers.IO) {
        try {
            val obj = postgrest.from("split_groups").select {
                filter { eq("id", groupId) }
            }.decodeSingle<JsonObject>()
            
            val members = obj["members"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val participants = obj["participants"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val mappingObj = obj["member_mappings"]?.jsonObject
            val mappings = mappingObj?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

            val createdAt = parseIsoDateToLong(obj["created_at"]?.jsonPrimitive?.content)
            SplitGroup(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                uid = obj["uid"]?.jsonPrimitive?.content ?: "",
                members = members,
                memberMappings = mappings,
                participants = participants,
                createdAt = createdAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching group by id ($groupId): ${e.message}", e)
            null
        }
    }

    suspend fun exitGroup(groupId: String) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            val response = postgrest.from("split_groups").select {
                filter { eq("id", groupId) }
            }.decodeSingle<JsonObject>()
            
            val participants = response["participants"]?.jsonArray?.map { it.jsonPrimitive.content }?.toMutableList() ?: mutableListOf()
            val mappingObj = response["member_mappings"]?.jsonObject
            val currentMappings = mappingObj?.toMutableMap() ?: mutableMapOf()
            
            participants.remove(uid)
            currentMappings.remove(uid)
            
            val updateData = buildJsonObject {
                put("participants", buildJsonArray { participants.forEach { add(it) } })
                put("member_mappings", buildJsonObject {
                    currentMappings.forEach { (k, v) -> put(k, v) }
                })
            }

            postgrest.from("split_groups").update(updateData) {
                filter { eq("id", groupId) }
                select()
            }.decodeSingleOrNull<JsonObject>()
        } catch (e: Exception) {
            Log.e(TAG, "Error exiting group", e)
            throw e
        }
    }

    // ── Expenses ────────────────────────────────────────────────────────────

    suspend fun saveExpense(expense: SplitExpense): String? = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext null
        try {
            val json = buildJsonObject {
                put("uid", if (expense.uid.isNotEmpty()) expense.uid else uid)
                put("group_id", expense.groupId)
                put("description", expense.description)
                put("amount", expense.amount)
                put("paid_by", expense.paidBy)
                put("split_among", buildJsonObject {
                    expense.splitAmong.forEach { (member, amount) ->
                        put(member, amount)
                    }
                })
                put("created_at", expense.createdAt)
            }
            
            val response = if (expense.id.isNotEmpty()) {
                postgrest.from("split_expenses").update(json) {
                    filter { eq("id", expense.id) }
                    select()
                }.decodeSingle<JsonObject>()
            } else {
                postgrest.from("split_expenses").insert(json) {
                    select()
                }.decodeSingle<JsonObject>()
            }
            response["id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "Error saving expense", e)
            null
        }
    }

    suspend fun fetchExpenses(groupId: String): List<SplitExpense> = withContext(Dispatchers.IO) {
        try {
            val result = postgrest.from("split_expenses")
                .select {
                    filter { eq("group_id", groupId) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()
            
            result.map { obj ->
                val createdAtRaw = obj["created_at"]?.jsonPrimitive?.content
                SplitExpense(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    groupId = obj["group_id"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    amount = obj["amount"]?.jsonPrimitive?.double ?: 0.0,
                    paidBy = obj["paid_by"]?.jsonPrimitive?.content ?: "",
                    splitAmong = obj["split_among"]?.jsonObject?.mapValues { it.value.jsonPrimitive.double } ?: emptyMap(),
                    createdAt = parseIsoDateToLong(createdAtRaw),
                    uid = obj["uid"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching expenses", e)
            emptyList()
        }
    }

    suspend fun deleteExpense(expenseId: String) = withContext(Dispatchers.IO) {
        try {
            postgrest.from("split_expenses").delete { filter { eq("id", expenseId) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting expense", e)
        }
    }

    // ── Settlements ─────────────────────────────────────────────────────────

    suspend fun saveSettlement(settlement: Settlement): String? = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext null
        try {
            val json = buildJsonObject {
                put("uid", uid)
                put("group_id", settlement.groupId)
                put("from_member", settlement.from)
                put("to_member", settlement.to)
                put("amount", settlement.amount)
                put("settled_at", settlement.settledAt)
            }
            val response = postgrest.from("settlements").insert(json) {
                select()
            }.decodeSingle<JsonObject>()
            response["id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settlement", e)
            null
        }
    }

    suspend fun fetchSettlements(groupId: String): List<Settlement> = withContext(Dispatchers.IO) {
        try {
            val result = postgrest.from("settlements")
                .select {
                    filter { eq("group_id", groupId) }
                    order("settled_at", Order.DESCENDING)
                }.decodeList<JsonObject>()
            
            result.map { obj ->
                val settledAtRaw = obj["settled_at"]?.jsonPrimitive?.content
                Settlement(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    groupId = obj["group_id"]?.jsonPrimitive?.content ?: "",
                    from = obj["from_member"]?.jsonPrimitive?.content ?: "",
                    to = obj["to_member"]?.jsonPrimitive?.content ?: "",
                    amount = obj["amount"]?.jsonPrimitive?.double ?: 0.0,
                    settledAt = parseIsoDateToLong(settledAtRaw)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching settlements", e)
            emptyList()
        }
    }

    private fun parseIsoDateToLong(raw: String?): Long {
        if (raw == null || raw.isBlank()) return 0L
        return raw.toLongOrNull() ?: try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(raw.take(19))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
