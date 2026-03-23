package com.tech.spendwise.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tech.spendwise.PreferenceRepository
import com.tech.spendwise.SupabaseInstance
import com.tech.spendwise.security.EncryptionManager
import io.github.jan.supabase.postgrest.*
import io.github.jan.supabase.postgrest.query.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.*

/**
 * Background worker that encrypts and uploads transaction data to Supabase.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val encryptionManager = EncryptionManager()
    private val repository = PreferenceRepository(context)

    override suspend fun doWork(): Result {
        val amount = inputData.getString("amount") ?: return Result.failure()
        val category = inputData.getString("category") ?: "Others"
        
        try {
            // 1. Prepare data
            val transactionId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            
            val rawJson = """{"id":"$transactionId","amount":"$amount","category":"$category","timestamp":$timestamp}"""
            
            // 2. Encrypt data for Zero-Knowledge Cloud Storage
            val encryptedData = encryptionManager.encrypt(rawJson)
            
            // 3. Upload to Supabase
            try {
                val uid = SupabaseInstance.currentUserId()
                if (uid != null) {
                    val postgrest = SupabaseInstance.client.postgrest
                    val json = buildJsonObject {
                        put("uid", uid)
                        put("payload", encryptedData)
                        put("captured_at", timestamp)
                    }
                    postgrest.from("captured_transactions").insert(json)
                    Log.i("SyncWorker", "Encrypted transaction uploaded to Supabase: $transactionId")
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Supabase upload failed: ${e.message}")
            }

            // 4. Add to local encrypted queue so the user sees it in the app for review/confirmation
            repository.addTransaction(rawJson)
            
            return Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error in SyncWorker: ${e.message}")
            return Result.retry()
        }
    }
}
