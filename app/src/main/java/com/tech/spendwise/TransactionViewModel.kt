package com.tech.spendwise

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel for sharing transaction data between fragments and activity,
 * backed by persistent storage.
 */
class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferenceRepository(application)
    
    // All pending transactions
    val pendingTransactions: LiveData<List<String>> = repository.pendingTransactionsFlow.asLiveData()

    // The first transaction to be reviewed (the head of the queue)
    val firstTransaction: LiveData<String?> = pendingTransactions.map { list ->
        list.firstOrNull()
    }

    /**
     * Appends a new transaction to the queue.
     */
    fun addTransaction(json: String) {
        viewModelScope.launch {
            repository.addTransaction(json)
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
}
