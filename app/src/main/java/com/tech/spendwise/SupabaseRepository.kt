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

    suspend fun saveTransaction(jsonStr: String, lendId: String? = null): String? = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext null
        try {
            val encryptedMap = TransactionCrypto.encryptTransaction(jsonStr, uid)
            val allowedColumns = setOf("amount", "type", "merchant", "category", "payment_mode", "currency", "saved_at", "lend_id")
            val json = buildJsonObject {
                put("uid", uid)
                if (lendId != null) put("lend_id", lendId)
                encryptedMap.forEach { (k, v) ->
                    if (allowedColumns.contains(k)) {
                        put(k, v?.toString() ?: "")
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
                val map = obj.mapValues { it.value.jsonPrimitive.content }
                TransactionCrypto.decryptTransaction(map, uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching transactions", e)
            emptyList()
        }
    }

    // ── Lends ─────────────────────────────────────────────────────────────

    suspend fun saveLend(lend: LendTransaction) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            // 1. First, Save as a regular transaction to maintain history
            val lendJson = buildJsonObject {
                put("amount", lend.amount.toString())
                put("type", "Expense") // Lend is an outflow
                put("merchant", "Lend: ${lend.name}")
                put("category", "Lend")
                put("payment_mode", lend.paymentMode)
                put("currency", "INR")
                put("saved_at", System.currentTimeMillis())
            }.toString()
            
            val transactionId = saveTransaction(lendJson)
            
            // 2. Now save the Lend record linked to that transaction
            val linkedLend = lend.copy(transactionId = transactionId)
            val encryptedMap = TransactionCrypto.encryptLend(linkedLend, uid)
            val json = buildJsonObject {
                put("uid", uid)
                put("name", encryptedMap["name"]?.toString() ?: "")
                put("amount", encryptedMap["amount"]?.toString() ?: "")
                put("payment_mode", encryptedMap["payment_mode"]?.toString() ?: "")
                put("phone_number", encryptedMap["phone_number"]?.toString() ?: "")
                put("note", encryptedMap["note"]?.toString() ?: "")
                put("return_date", (encryptedMap["return_date"] as? Long) ?: 0L)
                put("is_returned", (encryptedMap["is_returned"] as? Boolean) ?: false)
                put("created_at", (encryptedMap["created_at"] as? Long) ?: 0L)
                put("transaction_id", linkedLend.transactionId ?: "")
            }
            
            val response = postgrest.from("lends").insert(json) {
                select()
            }.decodeSingle<JsonObject>()
            
            val lendId = response["id"]?.jsonPrimitive?.content ?: ""
            
            // 3. Update the transaction with the lend_id for bidirectional link
            if (transactionId != null && lendId.isNotEmpty()) {
                postgrest.from("transactions").update(buildJsonObject { put("lend_id", lendId) }) {
                    filter { eq("id", transactionId) }
                }
            }
            
            // 4. Also save to public_lends for sharing
            savePublicLend(lend.copy(id = lendId))
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
            val result = postgrest.from("lends")
                .select() {
                    filter { eq("uid", uid) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<JsonObject>()
            
            result.mapNotNull { obj ->
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val map = obj.mapValues { it.value.jsonPrimitive.content }
                TransactionCrypto.decryptLend(id, map, uid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching lends", e)
            emptyList()
        }
    }
    
    suspend fun updateLendStatus(lendId: String, isReturned: Boolean) = withContext(Dispatchers.IO) {
        try {
            postgrest.from("lends").update(buildJsonObject { put("is_returned", isReturned) }) {
                filter { eq("id", lendId) }
            }
            postgrest.from("public_lends").update(buildJsonObject { put("is_returned", isReturned) }) {
                filter { eq("id", lendId) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status", e)
        }
    }

    suspend fun updateLendReturnDate(lendId: String, date: Long) = withContext(Dispatchers.IO) {
        try {
            postgrest.from("lends").update(buildJsonObject { put("return_date", date) }) {
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
            // 1. Fetch lend to find linked transaction
            val lend = getLendById(lendId)
            
            // 2. Delete linked transaction if exists
            lend?.transactionId?.let { txId ->
                postgrest.from("transactions").delete { filter { eq("id", txId) } }
            }
            
            // 3. Delete lend records
            postgrest.from("lends").delete { filter { eq("id", lendId) } }
            postgrest.from("public_lends").delete { filter { eq("id", lendId) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting lend", e)
        }
    }

    suspend fun getLendById(lendId: String): LendTransaction? = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext null
        try {
            val obj = postgrest.from("lends").select {
                filter { eq("id", lendId) }
            }.decodeSingle<JsonObject>()
            val map = obj.mapValues { it.value.jsonPrimitive.content }
            TransactionCrypto.decryptLend(lendId, map, uid)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting lend", e)
            null
        }
    }

    suspend fun deleteTransaction(id: String) = withContext(Dispatchers.IO) {
        try {
            // 1. Check if this transaction is linked to a lend
            val response = postgrest.from("transactions").select {
                filter { eq("id", id) }
            }.decodeSingleOrNull<JsonObject>()
            
            val lendId = response?.get("lend_id")?.jsonPrimitive?.contentOrNull
            
            // 2. If it's a lend, delete the lend records too
            if (lendId != null && lendId.isNotEmpty()) {
                postgrest.from("lends").delete { filter { eq("id", lendId) } }
                postgrest.from("public_lends").delete { filter { eq("id", lendId) } }
            }
            
            // 3. Delete the transaction itself
            postgrest.from("transactions").delete { filter { eq("id", id) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting transaction", e)
        }
    }

    suspend fun deleteTransactionsBatch(ids: List<String>) = withContext(Dispatchers.IO) {
        try {
            postgrest.from("transactions").delete {
                filter {
                    isIn("id", ids)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error batch deleting transactions", e)
            // Alternatively, loop if 'in' is tricky
            ids.forEach { deleteTransaction(it) }
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
                        put(k, v?.toString() ?: "")
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
            postgrest.from("lends").delete { filter { eq("uid", uid) } }
            // public_lends are harder to delete by UID if we don't have uid column there.
            // But we can join or just assume they are tied to lends.
            // If we added uid to public_lends in schema, it would be easier.
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing user data", e)
        }
    }
}
