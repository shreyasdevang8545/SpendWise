package com.tech.spendwise

import android.util.Log
import com.tech.spendwise.models.LendTransaction
import com.tech.spendwise.security.TransactionCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.jan.supabase.postgrest.*
import io.github.jan.supabase.postgrest.query.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double

/**
 * Repository for managing transactions and lends in Supabase.
 * Replaces FirestoreRepository.
 */
class SupabaseRepository {

    private val postgrest = SupabaseInstance.client.postgrest
    private val TAG = "SupabaseRepository"

    // ── Transactions ──────────────────────────────────────────────────────

    suspend fun saveTransaction(jsonStr: String): String? = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext null
        try {
            val encryptedMap = TransactionCrypto.encryptTransaction(jsonStr, uid)
            val allowedColumns = setOf(
                "amount", "type", "merchant", "category", "payment_mode", "currency", "saved_at",
                "is_lend", "lend_name", "phone_number", "note", "return_date", "is_returned", "created_at"
            )
            val json = buildJsonObject {
                put("uid", uid)
                encryptedMap.forEach { (k, v) ->
                    if (allowedColumns.contains(k)) {
                        when (v) {
                            is String -> put(k, v)
                            is Number -> put(k, v)
                            is Boolean -> put(k, v)
                            null -> put(k, JsonNull)
                        }
                    }
                }
            }
            val response = postgrest.from("transactions").insert(json) {
                select()
            }.decodeSingle<JsonObject>()
            
            response["id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "Error saving transaction", e)
            null
        }
    }

    suspend fun fetchRecentTransactions(limit: Int = 100): List<String> = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext emptyList()
        try {
            val result = postgrest.from("transactions")
                .select() {
                    filter {
                        eq("uid", uid)
                    }
                    order("saved_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
            
            // PostgREST returns a list of JsonObjects
            val list = result.decodeList<JsonObject>()
            list.mapNotNull { obj ->
                val map = obj.mapValues { it.value.jsonPrimitive.contentOrNull }
                TransactionCrypto.decryptTransaction(map, uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching transactions", e)
            emptyList()
        }
    }

    // ── Lends (Unified) ───────────────────────────────────────────────────

    suspend fun saveLend(lend: LendTransaction) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            // In unified approach, we save a single transaction record with is_lend = true
            val unifiedJson = buildJsonObject {
                put("amount", lend.amount.toString())
                put("type", "Expense") // Lend is an outflow
                put("merchant", "Lend: ${lend.name}")
                put("category", "Lend")
                put("payment_mode", lend.paymentMode)
                put("currency", "INR")
                put("saved_at", System.currentTimeMillis())
                put("is_lend", true)
                put("lend_name", lend.name)
                put("phone_number", lend.phoneNumber ?: "")
                put("note", lend.note ?: "")
                put("return_date", lend.returnDate)
                put("is_returned", lend.isReturned)
                put("created_at", lend.createdAt)
            }.toString()
            
            val transactionId = saveTransaction(unifiedJson)
            
            // Still save to public_lends for sharing functionality
            if (transactionId != null) {
                savePublicLend(lend.copy(id = transactionId))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving lend", e)
        }
    }

    private suspend fun savePublicLend(lend: LendTransaction) {
        try {
            // Public lend is NOT encrypted
            val json = buildJsonObject {
                put("id", lend.id)
                put("name", lend.name)
                put("phone", lend.phoneNumber)
                put("amount", lend.amount)
                put("note", lend.note)
                put("payment_mode", lend.paymentMode)
                put("return_date", lend.returnDate)
                put("is_returned", lend.isReturned)
                put("created_at", lend.createdAt)
                put("updated_at", System.currentTimeMillis())
            }
            postgrest.from("public_lends").upsert(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving public lend", e)
        }
    }

    suspend fun fetchLends(): List<LendTransaction> = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext emptyList()
        try {
            val result = postgrest.from("transactions")
                .select() {
                    filter { 
                        eq("uid", uid)
                        eq("is_lend", true)
                    }
                    order("saved_at", Order.DESCENDING)
                }.decodeList<JsonObject>()
            
            result.mapNotNull { obj ->
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val map = obj.mapValues { it.value.jsonPrimitive.contentOrNull }
                TransactionCrypto.mapToLendTransaction(id, map, uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching lends", e)
            emptyList()
        }
    }
    
    suspend fun updateLendStatus(lendId: String, isReturned: Boolean) = withContext(Dispatchers.IO) {
        try {
            // Update the combined table
            postgrest.from("transactions").update(buildJsonObject { put("is_returned", isReturned) }) {
                filter { eq("id", lendId) }
            }
            // Update public record
            postgrest.from("public_lends").update(buildJsonObject { put("is_returned", isReturned) }) {
                filter { eq("id", lendId) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status", e)
        }
    }

    suspend fun updateLendReturnDate(lendId: String, date: Long) = withContext(Dispatchers.IO) {
        try {
            postgrest.from("transactions").update(buildJsonObject { put("return_date", date) }) {
                filter { eq("id", lendId) }
            }
            postgrest.from("public_lends").update(buildJsonObject { put("return_date", date) }) {
                filter { eq("id", lendId) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating return date", e)
        }
    }

    suspend fun deleteLend(lendId: String) = withContext(Dispatchers.IO) {
        try {
            // Delete from combined table
            postgrest.from("transactions").delete { filter { eq("id", lendId) } }
            // Delete from public table
            postgrest.from("public_lends").delete { filter { eq("id", lendId) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting lend", e)
        }
    }

    suspend fun getLendById(lendId: String): LendTransaction? = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext null
        try {
            val obj = postgrest.from("transactions").select {
                filter { eq("id", lendId) }
            }.decodeSingle<JsonObject>()
            val map = obj.mapValues { it.value.jsonPrimitive.contentOrNull }
            TransactionCrypto.mapToLendTransaction(lendId, map, uid)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting lend", e)
            null
        }
    }

    suspend fun deleteTransaction(id: String) = withContext(Dispatchers.IO) {
        try {
            // 1. Check if it's a lend to clean up public_lends
            val response = postgrest.from("transactions").select {
                filter { eq("id", id) }
            }.decodeSingleOrNull<JsonObject>()
            
            val isLend = response?.get("is_lend")?.jsonPrimitive?.booleanOrNull ?: false
            
            if (isLend) {
                postgrest.from("public_lends").delete { filter { eq("id", id) } }
            }
            
            // 2. Delete the transaction itself
            postgrest.from("transactions").delete { filter { eq("id", id) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting transaction", e)
        }
    }

    suspend fun deleteTransactionsBatch(ids: List<String>) = withContext(Dispatchers.IO) {
        try {
            // Clean up public lends for any in the batch
            ids.forEach { deleteTransaction(it) } 
        } catch (e: Exception) {
            Log.e(TAG, "Error batch deleting transactions", e)
        }
    }

    suspend fun updateTransaction(id: String, jsonStr: String) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            val encryptedMap = TransactionCrypto.encryptTransaction(jsonStr, uid)
            val allowedColumns = setOf("amount", "type", "merchant", "category", "payment_mode", "currency", "saved_at")
            val json = buildJsonObject {
                encryptedMap.forEach { (k, v) ->
                    if (allowedColumns.contains(k)) {
                        when (v) {
                            is String -> put(k, v)
                            is Number -> put(k, v)
                            is Boolean -> put(k, v)
                            null -> put(k, JsonNull)
                        }
                    }
                }
            }
            postgrest.from("transactions").update(json) {
                filter { eq("id", id) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating transaction", e)
        }
    }

    suspend fun clearAllUserData() = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            postgrest.from("transactions").delete { filter { eq("uid", uid) } }
            postgrest.from("feedbacks").delete { filter { eq("uid", uid) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing user data", e)
        }
    }

    // ── Feedbacks ─────────────────────────────────────────────────────────

    suspend fun saveFeedback(message: String) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            val feedback = buildJsonObject {
                put("uid", uid)
                put("message", message)
                put("status", "pending")
                put("created_at", System.currentTimeMillis())
                put("updated_at", System.currentTimeMillis())
            }
            postgrest.from("feedbacks").insert(feedback)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving feedback", e)
        }
    }

    suspend fun fetchFeedbacks(): List<JsonObject> = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext emptyList()
        try {
            postgrest.from("feedbacks").select {
                filter { eq("uid", uid) }
                order("created_at", Order.DESCENDING)
            }.decodeList<JsonObject>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching feedbacks", e)
            emptyList()
        }
    }
}
