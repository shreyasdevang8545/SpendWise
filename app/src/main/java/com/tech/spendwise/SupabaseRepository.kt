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
            val originalMap = TransactionCrypto.parseSimpleJson(jsonStr)
            val providedId = originalMap["id"] // Check if ID already exists (e.g. from Lend)
            
            val encryptedMap = TransactionCrypto.encryptTransaction(jsonStr, uid)
            val allowedColumns = setOf(
                "amount", "type", "merchant", "category", "payment_mode", "currency", "saved_at",
                "is_lend", "lend_name", "phone_number", "note", "return_date", "is_returned", "created_at", "credit_card_id", "is_recurring"
            )
            val json = buildJsonObject {
                put("uid", uid)
                if (!providedId.isNullOrEmpty()) {
                    put("id", providedId)
                }
                encryptedMap.forEach { (k, v) ->
                    if (allowedColumns.contains(k)) {
                        val valueToPut = if (k == "saved_at" || k == "created_at" || k == "return_date") {
                            val raw = v?.toString() ?: ""
                            if (raw.all { it.isDigit() } && raw.length >= 10) {
                                formatTimestamp(raw.toLong())
                            } else {
                                v
                            }
                        } else {
                            v
                        }

                        when (valueToPut) {
                            is String -> put(k, valueToPut)
                            is Number -> put(k, valueToPut)
                            is Boolean -> put(k, valueToPut)
                            null -> put(k, JsonNull)
                        }
                    }
                }
            }
            val response = postgrest.from("transactions").upsert(json) {
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
        val uid = SupabaseInstance.currentUserId() ?: throw Exception("Not logged in")
        
        // In unified approach, we save a single transaction record with is_lend = true
        val unifiedJson = buildJsonObject {
            put("id", lend.id ?: "")
            put("amount", lend.amount.toString())
            put("type", "Expense") // Lend is an outflow
            put("merchant", "Lend: ${lend.name}")
            put("category", "Lend")
            put("payment_mode", lend.paymentMode)
            put("currency", "INR")
            put("saved_at", formatTimestamp(System.currentTimeMillis()))
            put("is_lend", true)
            put("lend_name", lend.name)
            put("phone_number", lend.phoneNumber ?: "")
            put("note", lend.note ?: "")
            put("return_date", if (lend.returnDate > 0) formatTimestamp(lend.returnDate) else "null")
            put("is_returned", lend.isReturned)
            put("created_at", formatTimestamp(if (lend.createdAt > 0) lend.createdAt else System.currentTimeMillis()))
        }.toString()
        
        val transactionId = saveTransaction(unifiedJson)
        
        if (transactionId == null) {
            throw Exception("Failed to save transaction to database")
        }
        
        // Still save to public_lends for sharing functionality
        savePublicLend(lend.copy(id = transactionId))
    }

    private suspend fun savePublicLend(lend: LendTransaction) {
        try {
            Log.d(TAG, "savePublicLend: Upserting to public_lends for id=${lend.id}")
            // Public lend is NOT encrypted
            val json = buildJsonObject {
                put("id", lend.id)
                put("name", lend.name)
                put("phone", lend.phoneNumber)
                put("amount", lend.amount)
                put("note", lend.note)
                put("payment_mode", lend.paymentMode)
                put("return_date", if (lend.returnDate > 0) formatTimestamp(lend.returnDate) else null)
                put("is_returned", lend.isReturned)
                put("created_at", formatTimestamp(if (lend.createdAt > 0) lend.createdAt else System.currentTimeMillis()))
                put("updated_at", formatTimestamp(System.currentTimeMillis()))
            }
            postgrest.from("public_lends").upsert(json)
            Log.d(TAG, "savePublicLend: Successfully upserted to public_lends")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving public lend for id=${lend.id}", e)
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
            val isoDate = formatTimestamp(date)
            postgrest.from("transactions").update(buildJsonObject { put("return_date", isoDate) }) {
                filter { eq("id", lendId) }
            }
            postgrest.from("public_lends").update(buildJsonObject { put("return_date", isoDate) }) {
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
        val uid = SupabaseInstance.currentUserId() ?: run {
            Log.e(TAG, "getLendById: No current user ID")
            return@withContext null
        }
        try {
            Log.d(TAG, "getLendById: Fetching from PostgREST for id=$lendId")
            val obj = postgrest.from("transactions").select {
                filter { eq("id", lendId) }
            }.decodeSingleOrNull<JsonObject>()
            
            if (obj == null) {
                Log.w(TAG, "getLendById: No record found for id=$lendId")
                return@withContext null
            }
            
            Log.d(TAG, "getLendById: Record found. Mapping to LendTransaction.")
            val map = obj.mapValues { it.value.jsonPrimitive.contentOrNull }
            val lend = TransactionCrypto.mapToLendTransaction(lendId, map, uid)
            
            if (lend == null) {
                Log.e(TAG, "getLendById: mapToLendTransaction returned null for id=$lendId. Data: $map")
            } else {
                Log.d(TAG, "getLendById: Successfully mapped lend: ${lend.name}")
            }
            lend
        } catch (e: Exception) {
            Log.e(TAG, "Error getting lend with id=$lendId", e)
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
            val allowedColumns = setOf("amount", "type", "merchant", "category", "payment_mode", "currency", "saved_at", "is_recurring")
            val json = buildJsonObject {
                encryptedMap.forEach { (k, v) ->
                    if (allowedColumns.contains(k)) {
                        val valueToPut = if (k == "saved_at") {
                            val raw = v?.toString() ?: ""
                            if (raw.all { it.isDigit() } && raw.length >= 10) {
                                formatTimestamp(raw.toLong())
                            } else {
                                v
                            }
                        } else {
                            v
                        }

                        when (valueToPut) {
                            is String -> put(k, valueToPut)
                            is Number -> put(k, valueToPut)
                            is Boolean -> put(k, valueToPut)
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

    // ── Credit Cards ─────────────────────────────────────────────────────
    
    suspend fun saveCreditCard(card: com.tech.spendwise.models.CreditCard) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            val encryptedFields = TransactionCrypto.encryptCreditCard(card.name, card.last4, card.limit, uid)
            val json = buildJsonObject {
                if (!card.id.isNullOrEmpty()) put("id", card.id)
                put("uid", uid)
                put("name", encryptedFields["name"]!!)
                put("last4", encryptedFields["last4"]!!)
                put("limit", encryptedFields["limit"]!!)
                put("payback_day", card.paybackDay)
                put("reminder_enabled", card.reminderEnabled)
                put("created_at", formatTimestamp(if (card.createdAt > 0) card.createdAt else System.currentTimeMillis()))
            }
            postgrest.from("credit_cards").upsert(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving credit card", e)
        }
    }

    suspend fun fetchCreditCards(): List<com.tech.spendwise.models.CreditCard> = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext emptyList()
        try {
            val result = postgrest.from("credit_cards")
                .select {
                    filter { eq("uid", uid) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()
            
            result.mapNotNull { obj ->
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val map = obj.mapValues { it.value.jsonPrimitive.contentOrNull }
                TransactionCrypto.mapToCreditCard(id, map, uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching credit cards", e)
            emptyList()
        }
    }

    suspend fun deleteCreditCard(cardId: String) = withContext(Dispatchers.IO) {
        try {
            postgrest.from("credit_cards").delete { filter { eq("id", cardId) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting credit card", e)
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
                put("created_at", formatTimestamp(System.currentTimeMillis()))
                put("updated_at", formatTimestamp(System.currentTimeMillis()))
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

    private fun formatTimestamp(ms: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }

    private fun parseIsoDateToLong(raw: String?): Long {
        if (raw == null || raw.isBlank()) return 0L
        return raw.toLongOrNull() ?: try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).parse(raw.take(19))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
