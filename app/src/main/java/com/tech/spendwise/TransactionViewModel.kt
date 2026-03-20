package com.tech.spendwise

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/**
 * ViewModel for sharing transaction data between fragments and activity,
 * backed by persistent local storage (DataStore) and Firestore for cloud sync.
 */
class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository          = PreferenceRepository(application)
    private val firestoreRepository = FirestoreRepository()

    // All pending transactions
    val pendingTransactions: LiveData<List<String>> = repository.pendingTransactionsFlow.asLiveData()

    // All confirmed (saved) transactions, newest first (local DataStore)
    val confirmedTransactions: LiveData<List<String>> = repository.confirmedTransactionsFlow.asLiveData()

    // Transactions fetched from Firestore (decrypted)
    private val _firestoreTransactions = MutableLiveData<List<String>>(emptyList())
    val firestoreTransactions: LiveData<List<String>> = _firestoreTransactions

    // Lends fetched from Firestore
    private val _lends = MutableLiveData<List<com.tech.spendwise.models.LendTransaction>>(emptyList())
    val lends: LiveData<List<com.tech.spendwise.models.LendTransaction>> = _lends

    // Loading state for Firestore fetch
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Voice guide status
    val isVoiceGuideShown: LiveData<Boolean> = repository.isVoiceGuideShownFlow.asLiveData()

    // The first transaction to be reviewed (the head of the queue)
    val firstTransaction: LiveData<String?> = pendingTransactions.map { list ->
        list.firstOrNull()
    }

    /**
     * Appends a new transaction to the pending review queue.
     */
    fun addTransaction(json: String) {
        viewModelScope.launch {
            repository.addTransaction(json)
        }
    }

    /**
     * Persists a confirmed (reviewed and saved) transaction locally,
     * then syncs it to Firestore (encrypted) if the user is signed in.
     */
    fun addConfirmedTransaction(json: String, retryCount: Int = 0) {
        viewModelScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                firestoreRepository.saveTransaction(uid, json) { result ->
                    result.onSuccess {
                        Log.d("TransactionVM", "Transaction synced successfully")
                    }.onFailure { e ->
                        Log.e("TransactionVM", "Sync failed: ${e.message}")
                        if (retryCount < 3) {
                            Log.d("TransactionVM", "Retrying sync... attempt ${retryCount + 1}")
                            addConfirmedTransaction(json, retryCount + 1)
                        } else {
                            // Max retries reached or terminal error: Save locally for later
                            viewModelScope.launch { repository.addConfirmedTransaction(json) }
                        }
                    }
                }
            } else {
                // Not logged in: Save locally
                repository.addConfirmedTransaction(json)
            }
        }
    }

    /**
     * Retries uploading a locally saved transaction to Firestore.
     * Removes from local storage on success.
     */
    fun uploadOfflineTransaction(json: String) {
        viewModelScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            firestoreRepository.saveTransaction(uid, json) { result ->
                result.onSuccess {
                    viewModelScope.launch {
                        repository.removeConfirmedTransaction(json)
                    }
                }.onFailure { e ->
                    Log.e("TransactionVM", "Offline upload failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Fetches the [limit] most recent confirmed transactions from Firestore for the signed-in user.
     * Results are posted to [firestoreTransactions].
     */
    fun fetchFromFirestore(limit: Long = 100) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        _isLoading.value = true
        firestoreRepository.fetchRecentTransactions(
            uid  = uid,
            limit = limit,
            onResult = { result -> 
                result.onSuccess { list ->
                    _firestoreTransactions.postValue(list)
                }.onFailure { e ->
                    Log.e("TransactionVM", "Fetch failed: ${e.message}")
                }
                _isLoading.postValue(false)
            }
        )
        // Also fetch lends for the summary
        firestoreRepository.fetchLends(uid) { result ->
            result.onSuccess { list ->
                _lends.postValue(list)
            }.onFailure { e ->
                Log.e("TransactionVM", "Lend fetch failed: ${e.message}")
            }
        }
    }

    /**
     * Deletes a transaction from Firestore and refreshes the list.
     */
    fun deleteTransaction(docId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        firestoreRepository.deleteTransaction(uid, docId) {
            fetchFromFirestore() // Refresh after deletion
        }
    }

    /**
     * Deletes multiple transactions from Firestore and refreshes the list.
     */
    fun deleteTransactionsBatch(docIds: List<String>) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        firestoreRepository.deleteTransactionsBatch(uid, docIds) {
            fetchFromFirestore() // Refresh after deletion
        }
    }

    /**
     * Updates a transaction in Firestore and refreshes the list.
     */
    fun updateTransaction(docId: String, json: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        firestoreRepository.updateTransaction(uid, docId, json) {
            fetchFromFirestore() // Refresh after update
        }
    }

    /**
     * Removes the oldest transaction (the one currently being reviewed).
     */
    fun popTransaction() {
        viewModelScope.launch {
            repository.removeTopTransaction()
        }
    }

    /**
     * Clears all pending transactions.
     */
    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    /**
     * Updates the voice guide shown status.
     */
    fun setVoiceGuideShown(shown: Boolean) {
        viewModelScope.launch {
            repository.setVoiceGuideShown(shown)
        }
    }

    /**
     * Removes a transaction from the local persistent store (for manual cleanup of offline items).
     */
    fun deleteLocalTransaction(json: String) {
        viewModelScope.launch {
            repository.removeConfirmedTransaction(json)
        }
    }
}
