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

    // Credit Cards fetched from Firestore
    private val _creditCards = MutableLiveData<List<com.tech.spendwise.models.CreditCard>>(emptyList())
    val creditCards: LiveData<List<com.tech.spendwise.models.CreditCard>> = _creditCards

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

                val cards = supabaseRepository.fetchCreditCards()
                _creditCards.postValue(cards)
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
            _isLoading.postValue(true)
            val uid = SupabaseInstance.currentUserId()
            
            if (uid != null) {
                try {
                    supabaseRepository.saveLend(lend)
                    Log.d("TransactionVM", "Lend synced successfully ${lend.id}")
                    fetchFromFirestore()
                } catch (e: Exception) {
                    Log.e("TransactionVM", "Lend Sync failed: ${e.message}")
                    // Fallback to local
                    saveLendLocally(lend)
                } finally {
                    _isLoading.postValue(false)
                }
            } else {
                saveLendLocally(lend)
                _isLoading.postValue(false)
            }
        }
    }

    private fun saveLendLocally(lend: com.tech.spendwise.models.LendTransaction) {
        viewModelScope.launch {
             val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date())

            fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")

            val json = """
                {
                  "amount": ${lend.amount},
                  "type": "DEBIT",
                  "merchant": "${lend.name.esc()}",
                  "category": "Lend",
                  "payment_mode": "${lend.paymentMode.esc()}",
                  "currency": "INR",
                  "saved_at": "$timestamp",
                  "is_lend": true,
                  "lend_name": "${lend.name.esc()}",
                  "phone_number": "${(lend.phoneNumber ?: "").esc()}",
                  "note": "${(lend.note ?: "").esc()}",
                  "return_date": ${lend.returnDate},
                  "created_at": ${if (lend.createdAt > 0) lend.createdAt else System.currentTimeMillis()}
                }
            """.trimIndent()
            repository.addConfirmedTransaction(json)
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

    fun saveCreditCard(card: com.tech.spendwise.models.CreditCard) {
        viewModelScope.launch {
            _isLoading.postValue(true)
            try {
                supabaseRepository.saveCreditCard(card)
                fetchFromFirestore()
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun deleteCreditCard(id: String) {
        viewModelScope.launch {
            supabaseRepository.deleteCreditCard(id)
            fetchFromFirestore()
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
     * Removes a specific pending transaction from the queue.
     */
    fun removePendingTransaction(index: Int) {
        viewModelScope.launch {
            repository.removeTransactionAtIndex(index)
        }
    }

    /**
     * Removes a specific pending transaction from the queue by its JSON string.
     */
    fun removePendingTransactionByJson(json: String) {
        viewModelScope.launch {
            repository.removeTransaction(json)
        }
    }

    /**
     * Clears all pending transactions.
     */
    fun clearPendingTransactions() {
        viewModelScope.launch {
            repository.clearAllPendingTransactions()
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
