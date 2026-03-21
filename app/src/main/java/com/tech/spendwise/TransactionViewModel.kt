package com.tech.spendwise

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.tech.spendwise.SupabaseInstance
import kotlinx.coroutines.launch

/**
 * ViewModel for sharing transaction data between fragments and activity,
 * backed by persistent local storage (DataStore) and Firestore for cloud sync.
 */
class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository          = PreferenceRepository(application)
    private val supabaseRepository = SupabaseRepository()
    private val splitRepository    = com.tech.spendwise.splitwise.SupabaseSplitRepository()

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
    fun addConfirmedTransaction(json: String, syncToCloud: Boolean = true, retryCount: Int = 0) {
        viewModelScope.launch {
            if (!syncToCloud) {
                repository.addConfirmedTransaction(json)
                return@launch
            }
            
            val uid = SupabaseInstance.currentUserId()
            if (uid != null) {
                try {
                    supabaseRepository.saveTransaction(json)
                    Log.d("TransactionVM", "Transaction synced successfully")
                } catch (e: Exception) {
                    Log.e("TransactionVM", "Sync failed: ${e.message}")
                    if (retryCount < 3) {
                        Log.d("TransactionVM", "Retrying sync... attempt ${retryCount + 1}")
                        addConfirmedTransaction(json, syncToCloud = true, retryCount = retryCount + 1)
                    } else {
                        // Max retries reached: Save locally for later
                        repository.addConfirmedTransaction(json)
                    }
                }
            } else {
                // Not logged in: Save locally
                repository.addConfirmedTransaction(json)
            }
        }
    }

    /**
     * Retries uploading a locally saved transaction to Supabase.
     * Removes from local storage on success.
     */
    fun uploadOfflineTransaction(json: String) {
        viewModelScope.launch {
            val uid = SupabaseInstance.currentUserId() ?: return@launch
            try {
                supabaseRepository.saveTransaction(json)
                repository.removeConfirmedTransaction(json)
                Log.d("TransactionVM", "Offline transaction synced and removed local copy")
            } catch (e: Exception) {
                Log.e("TransactionVM", "Offline upload failed: ${e.message}")
            }
        }
    }

    /**
     * Fetches the [limit] most recent confirmed transactions from Firestore for the signed-in user.
     * Results are posted to [firestoreTransactions].
     */
    fun fetchFromFirestore() {
        val uid = SupabaseInstance.currentUserId() ?: return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val transactions = supabaseRepository.fetchRecentTransactions()
                _firestoreTransactions.postValue(transactions)
                
                val lends = supabaseRepository.fetchLends()
                _lends.postValue(lends)
            } catch (e: Exception) {
                Log.e("TransactionVM", "Fetch failed: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * Deletes a transaction from Firestore and refreshes the list.
     */
    fun deleteTransaction(id: String) {
        viewModelScope.launch {
            supabaseRepository.deleteTransaction(id)
            fetchFromFirestore()
        }
    }

    fun deleteTransactionsBatch(ids: List<String>) {
        viewModelScope.launch {
            supabaseRepository.deleteTransactionsBatch(ids)
            fetchFromFirestore()
        }
    }

    /**
     * Saves a lend transaction and its linked record.
     */
    fun saveLend(lend: com.tech.spendwise.models.LendTransaction) {
        viewModelScope.launch {
            try {
                _isLoading.postValue(true)
                supabaseRepository.saveLend(lend)
                fetchFromFirestore()
            } catch (e: Exception) {
                Log.e("TransactionVM", "Save lend failed: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun updateTransaction(id: String, json: String) {
        viewModelScope.launch {
            try {
                supabaseRepository.updateTransaction(id, json)
            } finally {
                fetchFromFirestore()
            }
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
     * Wipes all local and remote data for the user.
     */
    fun clearEverything(onComplete: () -> Unit) {
        viewModelScope.launch {
            val uid = SupabaseInstance.currentUserId()
            
            // 1. Clear Local Preferences & State
            repository.clearEverything()
            val settingsManager = SettingsManager(getApplication())
            settingsManager.clearAll()
            
            // 2. Reset LiveData
            _firestoreTransactions.postValue(emptyList())
            _lends.postValue(emptyList())

            if (uid != null) {
                // 3. Clear Supabase Data
                supabaseRepository.clearAllUserData()
                // 4. Clear Splitwise Data
                // splitRepository.clearAllUserData() // If implemented
                onComplete()
            } else {
                onComplete()
            }
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
