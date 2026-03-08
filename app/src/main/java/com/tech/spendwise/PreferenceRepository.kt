package com.tech.spendwise

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tech.spendwise.security.EncryptionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Handles persistent storage of app preferences using DataStore.
 * Manages an encrypted queue of pending transactions as a delimited string.
 */
class PreferenceRepository(private val context: Context) {

    companion object {
        private val PENDING_TRANSACTIONS_LIST_KEY = stringPreferencesKey("pending_transactions_list_encrypted")
        private const val DELIMITER = "|_|"
    }

    private val encryptionManager = EncryptionManager()

    /**
     * Returns a Flow of the list of pending transaction JSON strings after decryption.
     */
    val pendingTransactionsFlow: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            val encrypted = preferences[PENDING_TRANSACTIONS_LIST_KEY] ?: ""
            if (encrypted.isEmpty()) {
                emptyList()
            } else {
                try {
                    val decrypted = encryptionManager.decrypt(encrypted)
                    if (decrypted.isEmpty()) emptyList() else decrypted.split(DELIMITER)
                } catch (e: Exception) {
                    // Fallback if decryption fails (e.g. key issue)
                    emptyList()
                }
            }
        }

    /**
     * Appends a new transaction JSON to the encrypted persistent queue.
     */
    suspend fun addTransaction(json: String) {
        context.dataStore.edit { preferences ->
            val encryptedCurrent = preferences[PENDING_TRANSACTIONS_LIST_KEY] ?: ""
            val currentDecrypted = if (encryptedCurrent.isEmpty()) {
                ""
            } else {
                try { encryptionManager.decrypt(encryptedCurrent) } catch (e: Exception) { "" }
            }
            
            val newListDecrypted = if (currentDecrypted.isEmpty()) json else "$currentDecrypted$DELIMITER$json"
            preferences[PENDING_TRANSACTIONS_LIST_KEY] = encryptionManager.encrypt(newListDecrypted)
        }
    }

    /**
     * Removes the first transaction in the queue (the oldest one).
     */
    suspend fun removeTopTransaction() {
        context.dataStore.edit { preferences ->
            val encryptedCurrent = preferences[PENDING_TRANSACTIONS_LIST_KEY] ?: ""
            if (encryptedCurrent.isNotEmpty()) {
                try {
                    val currentDecrypted = encryptionManager.decrypt(encryptedCurrent)
                    val list = currentDecrypted.split(DELIMITER).toMutableList()
                    if (list.isNotEmpty()) {
                        list.removeAt(0)
                        if (list.isEmpty()) {
                            preferences.remove(PENDING_TRANSACTIONS_LIST_KEY)
                        } else {
                            val newListDecrypted = list.joinToString(DELIMITER)
                            preferences[PENDING_TRANSACTIONS_LIST_KEY] = encryptionManager.encrypt(newListDecrypted)
                        }
                    }
                } catch (e: Exception) {
                    preferences.remove(PENDING_TRANSACTIONS_LIST_KEY)
                }
            }
        }
    }

    /**
     * Clears all pending transactions.
     */
    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.remove(PENDING_TRANSACTIONS_LIST_KEY)
        }
    }
}
