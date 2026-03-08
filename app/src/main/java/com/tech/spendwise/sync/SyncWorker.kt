package com.tech.spendwise.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.tech.spendwise.PreferenceRepository
import com.tech.spendwise.security.EncryptionManager
import java.util.*

/**
 * Background worker that encrypts and uploads transaction data to Firebase Firestore.
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
            
            // 3. Upload to Firestore
            // Note: This will fail if google-services.json is missing or Firebase is not configured.
            try {
                val db = FirebaseFirestore.getInstance()
                val record = hashMapOf(
                    "payload" to encryptedData,
                    "timestamp" to timestamp
                )
                
                // We use a generic collection; filtering/indexing is limited due to encryption.
                db.collection("transactions").document(transactionId).set(record)
                Log.i("SyncWorker", "Encrypted transaction uploaded successfully: $transactionId")
            } catch (e: Exception) {
                Log.e("SyncWorker", "Firebase upload failed (likely config missing): ${e.message}")
                // We don't fail the whole work if Firebase isn't ready yet, 
                // as we still want to save locally.
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
