package com.tech.spendwise

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.tech.spendwise.security.TransactionCrypto

/**
 * Handles reading and writing encrypted transactions to Firestore.
 *
 * Collection path: `users/{uid}/transactions`
 *
 * On write  : all sensitive fields are encrypted via [TransactionCrypto.encryptField] before upload.
 * On fetch  : each document is decrypted and returned as a plain JSON string compatible with
 *             [TransactionListAdapter].
 */
class FirestoreRepository {

    private val db = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "FirestoreRepository"
        private const val USERS = "users"
        private const val TRANSACTIONS = "transactions"
    }

    /**
     * Encrypts [plainJson] and writes it to Firestore under `users/{uid}/transactions`.
     * Fails silently (logs error) to avoid blocking the user on network issues.
     */
    fun saveTransaction(uid: String, plainJson: String, onResult: (Result<String>) -> Unit) {
        Log.d(TAG, "saveTransaction: Preparing to sync. UID=$uid")
        try {
            val encryptedMap = TransactionCrypto.encryptTransaction(plainJson, uid)
            Log.d(TAG, "saveTransaction: Data encrypted. Map size=${encryptedMap.size}")
            
            db.collection(USERS)
                .document(uid)
                .collection(TRANSACTIONS)
                .add(encryptedMap)
                .addOnSuccessListener { ref ->
                    Log.d(TAG, "saveTransaction SUCCESS: Document added with ID=${ref.id}")
                    onResult(Result.success(ref.id))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "saveTransaction FAILURE: ${e.message}", e)
                    onResult(Result.failure(e))
                }
        } catch (e: Exception) {
            Log.e(TAG, "saveTransaction ERROR: Encryption failed: ${e.message}", e)
            onResult(Result.failure(e))
        }
    }

    /**
     * Fetches the [limit] most recent transactions for [uid], decrypts them,
     * and delivers them via [onResult] callback on success.
     * Ordered by `saved_at` descending (newest first).
     */
    fun fetchRecentTransactions(
        uid: String,
        limit: Long = 10,
        onResult: (Result<List<String>>) -> Unit
    ) {
        db.collection(USERS)
            .document(uid)
            .collection(TRANSACTIONS)
            .orderBy("saved_at", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snapshot ->
                val decryptedList = snapshot.documents.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        val decrypted = TransactionCrypto.decryptTransaction(data, uid)
                        // Inject document ID into the JSON string so we can refer to it later
                        decrypted.replaceFirst("{", "{\"id\":\"${doc.id}\",")
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupted document ${doc.id}: ${e.message}")
                        null
                    }
                }
                onResult(Result.success(decryptedList))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Fetch failed: ${e.message}")
                onResult(Result.failure(e))
            }
    }
    /**
     * Encrypts and saves a lend transaction to Firestore.
     */
    fun saveLend(uid: String, lend: com.tech.spendwise.models.LendTransaction, onResult: (Result<String>) -> Unit) {
        try {
            val encryptedMap = TransactionCrypto.encryptLend(lend, uid)
            val collection = db.collection(USERS).document(uid).collection("lends")
            
            val docRef = if (lend.id != null) {
                collection.document(lend.id)
            } else {
                collection.document()
            }

            docRef.set(encryptedMap)
                .addOnSuccessListener { 
                    Log.d(TAG, "Lend saved: ${docRef.id}")
                    // Sync with public collection for the shared webpage
                    savePublicLend(lend, docRef.id)
                    onResult(Result.success(docRef.id))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Lend save failed: ${e.message}")
                    onResult(Result.failure(e))
                }
        } catch (e: Exception) {
            Log.e(TAG, "Lend save error: ${e.message}")
            onResult(Result.failure(e))
        }
    }

    /**
     * Fetches all lend transactions for a user.
     */
    fun fetchLends(uid: String, onResult: (Result<List<com.tech.spendwise.models.LendTransaction>>) -> Unit) {
        db.collection(USERS)
            .document(uid)
            .collection("lends")
            .orderBy("created_at", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                val list = snapshot.documents.mapNotNull { doc ->
                    TransactionCrypto.decryptLend(doc.id, doc.data ?: emptyMap(), uid)
                }
                onResult(Result.success(list))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Lend fetch failed: ${e.message}")
                onResult(Result.failure(e))
            }
    }

    /**
     * Updates the status of a lend transaction.
     */
    fun updateLendStatus(uid: String, lendId: String, isReturned: Boolean, onComplete: () -> Unit = {}) {
        db.collection(USERS)
            .document(uid)
            .collection("lends")
            .document(lendId)
            .update("is_returned", isReturned)
            .addOnSuccessListener { 
                // Also update public collection
                db.collection("public_lends").document(lendId).update("isReturned", isReturned)
                onComplete() 
            }
    }

    /**
     * Updates the return date of a lend transaction (for "Remind Tomorrow").
     */
    fun updateLendReturnDate(uid: String, lendId: String, newDate: Long, onComplete: () -> Unit = {}) {
        db.collection(USERS)
            .document(uid)
            .collection("lends")
            .document(lendId)
            .update("return_date", newDate)
            .addOnSuccessListener { 
                // Also update public collection
                db.collection("public_lends").document(lendId).update("returnDate", newDate)
                onComplete() 
            }
    }

    /**
     * Fetches a specific lend by ID.
     */
    fun getLendById(uid: String, lendId: String, onResult: (com.tech.spendwise.models.LendTransaction?) -> Unit) {
        db.collection(USERS)
            .document(uid)
            .collection("lends")
            .document(lendId)
            .get()
            .addOnSuccessListener { doc ->
                onResult(TransactionCrypto.decryptLend(doc.id, doc.data ?: emptyMap(), uid))
            }
            .addOnFailureListener { onResult(null) }
    }
    /**
     * Deletes a specific transaction document from Firestore.
     */
    fun deleteTransaction(uid: String, docId: String, onComplete: () -> Unit = {}) {
        db.collection(USERS)
            .document(uid)
            .collection(TRANSACTIONS)
            .document(docId)
            .delete()
            .addOnSuccessListener { onComplete() }
            .addOnFailureListener { Log.e(TAG, "Delete failed: ${it.message}") }
    }

    /**
     * Deletes multiple transaction documents in a single batch.
     */
    fun deleteTransactionsBatch(uid: String, docIds: List<String>, onComplete: () -> Unit = {}) {
        val batch = db.batch()
        val collection = db.collection(USERS).document(uid).collection(TRANSACTIONS)
        
        docIds.forEach { id ->
            batch.delete(collection.document(id))
        }
        
        batch.commit()
            .addOnSuccessListener { onComplete() }
            .addOnFailureListener { Log.e(TAG, "Batch delete failed: ${it.message}") }
    }

    /**
     * Updates an existing transaction document with new data.
     */
    fun updateTransaction(uid: String, docId: String, plainJson: String, onComplete: () -> Unit = {}) {
        try {
            val encryptedMap = TransactionCrypto.encryptTransaction(plainJson, uid)
            db.collection(USERS)
                .document(uid)
                .collection(TRANSACTIONS)
                .document(docId)
                .set(encryptedMap)
                .addOnSuccessListener { onComplete() }
                .addOnFailureListener { Log.e(TAG, "Update failed: ${it.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "Update error: ${e.message}")
        }
    }

    /**
     * Deletes a specific lend record from Firestore.
     */
    fun deleteLend(uid: String, lendId: String, onComplete: () -> Unit = {}) {
        db.collection(USERS)
            .document(uid)
            .collection("lends")
            .document(lendId)
            .delete()
            .addOnSuccessListener { 
                // Also delete from public collection
                db.collection("public_lends").document(lendId).delete()
                onComplete() 
            }
            .addOnFailureListener { Log.e(TAG, "Lend delete failed: ${it.message}") }
    }

    /**
     * Saves a simplified, unencrypted version of the lend to a public collection.
     * This is used for the shared lend details webpage.
     */
    private fun savePublicLend(lend: com.tech.spendwise.models.LendTransaction, lendId: String) {
        val publicLend = mapOf(
            "name" to lend.name,
            "amount" to lend.amount,
            "paymentMode" to lend.paymentMode,
            "returnDate" to lend.returnDate,
            "isReturned" to lend.isReturned,
            "createdAt" to lend.createdAt,
            "updatedAt" to System.currentTimeMillis()
        )
        db.collection("public_lends").document(lendId).set(publicLend)
            .addOnFailureListener { e -> Log.e(TAG, "Public lend sync failed: ${e.message}") }
    }
}
