package com.tech.spendwise

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tech.spendwise.security.EncryptionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Handles persistent storage of app preferences using DataStore.
 * Manages an encrypted queue of pending transactions as a delimited string.
 */
class PreferenceRepository(private val context: Context) {

    companion object {
        private val PENDING_TRANSACTIONS_LIST_KEY = stringPreferencesKey("pending_transactions_list_encrypted")
        private val CONFIRMED_TRANSACTIONS_KEY = stringPreferencesKey("confirmed_transactions_encrypted")
        private val IS_VOICE_GUIDE_SHOWN_KEY = booleanPreferencesKey("is_voice_guide_shown")
        private val SUPABASE_TOKENS_KEY = stringPreferencesKey("supabase_tokens_encrypted")
        private const val DELIMITER = "|_|"
        private const val MAX_CONFIRMED = 100
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
     * Returns a Flow of the confirmed (saved) transaction JSON strings, newest first.
     */
    val confirmedTransactionsFlow: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            val encrypted = preferences[CONFIRMED_TRANSACTIONS_KEY] ?: ""
            if (encrypted.isEmpty()) {
                emptyList()
            } else {
                try {
                    val decrypted = encryptionManager.decrypt(encrypted)
                    if (decrypted.isEmpty()) emptyList() else decrypted.split(DELIMITER)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

    /**
     * Returns a Flow suggesting if the voice guide has been shown.
     */
    val isVoiceGuideShownFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_VOICE_GUIDE_SHOWN_KEY] ?: false
        }

    /**
     * Marks the voice guide as shown.
     */
    suspend fun setVoiceGuideShown(shown: Boolean = true) {
        context.dataStore.edit { preferences ->
            preferences[IS_VOICE_GUIDE_SHOWN_KEY] = shown
        }
    }

    /**
     * Prepends a confirmed transaction JSON to the persistent store (newest-first).
     * Automatically trims the list to [MAX_CONFIRMED] entries.
     */
    suspend fun addConfirmedTransaction(json: String) {
        context.dataStore.edit { preferences ->
            val encryptedCurrent = preferences[CONFIRMED_TRANSACTIONS_KEY] ?: ""
            val currentDecrypted = if (encryptedCurrent.isEmpty()) {
                ""
            } else {
                try { encryptionManager.decrypt(encryptedCurrent) } catch (e: Exception) { "" }
            }
            val currentList = if (currentDecrypted.isEmpty()) mutableListOf() else currentDecrypted.split(DELIMITER).toMutableList()
            // Prepend so newest is first
            currentList.add(0, json)
            // Cap list
            val trimmed = currentList.take(MAX_CONFIRMED)
            preferences[CONFIRMED_TRANSACTIONS_KEY] = encryptionManager.encrypt(trimmed.joinToString(DELIMITER))
        }
    }

    /**
     * Removes a specific confirmed transaction JSON from the store.
     * Used when a previously offline transaction is successfully synced to cloud.
     */
    suspend fun removeConfirmedTransaction(json: String) {
        context.dataStore.edit { preferences ->
            val encryptedCurrent = preferences[CONFIRMED_TRANSACTIONS_KEY] ?: ""
            if (encryptedCurrent.isNotEmpty()) {
                try {
                    val currentDecrypted = encryptionManager.decrypt(encryptedCurrent)
                    val list = currentDecrypted.split(DELIMITER).toMutableList()
                    if (list.remove(json)) {
                        if (list.isEmpty()) {
                            preferences.remove(CONFIRMED_TRANSACTIONS_KEY)
                        } else {
                            val newListDecrypted = list.joinToString(DELIMITER)
                            preferences[CONFIRMED_TRANSACTIONS_KEY] = encryptionManager.encrypt(newListDecrypted)
                        }
                    }
                } catch (e: Exception) {
                    preferences.remove(CONFIRMED_TRANSACTIONS_KEY)
                }
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
            
            // Duplicate Check: If exact JSON already exists, skip
            if (currentDecrypted.isNotEmpty()) {
                val list = currentDecrypted.split(DELIMITER)
                if (list.contains(json)) {
                    Log.d("PreferenceRepo", "Duplicate transaction detected — skipping.")
                    return@edit
                }
            }

            val newListDecrypted = if (currentDecrypted.isEmpty()) json else "$currentDecrypted$DELIMITER$json"
            preferences[PENDING_TRANSACTIONS_LIST_KEY] = encryptionManager.encrypt(newListDecrypted)
        }
        
        // Trigger Budget Check
        BudgetNotificationHelper.checkBudgetsAndNotify(context, json)
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
     * Removes a specific transaction JSON from the queue.
     */
    suspend fun removeTransaction(json: String) {
        context.dataStore.edit { preferences ->
            val encryptedCurrent = preferences[PENDING_TRANSACTIONS_LIST_KEY] ?: ""
            if (encryptedCurrent.isNotEmpty()) {
                try {
                    val currentDecrypted = encryptionManager.decrypt(encryptedCurrent)
                    val list = currentDecrypted.split(DELIMITER).toMutableList()
                    if (list.remove(json)) {
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
    suspend fun clearAllPendingTransactions() {
        context.dataStore.edit { preferences ->
            preferences.remove(PENDING_TRANSACTIONS_LIST_KEY)
        }
    }

    /**
     * Removes a specific pending transaction by its index in the queue.
     */
    suspend fun removeTransactionAtIndex(index: Int) {
        context.dataStore.edit { preferences ->
            val encryptedCurrent = preferences[PENDING_TRANSACTIONS_LIST_KEY] ?: ""
            if (encryptedCurrent.isNotEmpty()) {
                try {
                    val currentDecrypted = encryptionManager.decrypt(encryptedCurrent)
                    val list = currentDecrypted.split(DELIMITER).toMutableList()
                    if (index in list.indices) {
                        list.removeAt(index)
                        if (list.isEmpty()) {
                            preferences.remove(PENDING_TRANSACTIONS_LIST_KEY)
                        } else {
                            val newListDecrypted = list.joinToString(DELIMITER)
                            preferences[PENDING_TRANSACTIONS_LIST_KEY] = encryptionManager.encrypt(newListDecrypted)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore or clear on failure
                }
            }
        }
    }

    /**
     * Clears all confirmed transactions (not used by pop, but for completeness).
     */
    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.remove(PENDING_TRANSACTIONS_LIST_KEY)
        }
    }

    fun decryptData(encrypted: String): String {
        return try {
            encryptionManager.decrypt(encrypted)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Clears all local persistent data.
     */
    suspend fun clearEverything() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * Saves the Supabase session tokens securely using synchronous SharedPreferences.
     */
    fun saveSupabaseTokensSync(accessToken: String?, refreshToken: String?) {
        if (accessToken.isNullOrEmpty() || refreshToken.isNullOrEmpty()) return
        val prefs = context.getSharedPreferences("supabase_session", Context.MODE_PRIVATE)
        val payload = "$accessToken$DELIMITER$refreshToken"
        prefs.edit().putString(SUPABASE_TOKENS_KEY.name, encryptionManager.encrypt(payload)).apply()
    }

    /**
     * Retrieves the saved Supabase session tokens synchronously.
     * @return Pair of (accessToken, refreshToken) or null.
     */
    fun getSupabaseTokensSync(): Pair<String, String>? {
        val prefs = context.getSharedPreferences("supabase_session", Context.MODE_PRIVATE)
        val encrypted = prefs.getString(SUPABASE_TOKENS_KEY.name, "") ?: ""
        if (encrypted.isNotEmpty()) {
            try {
                val decrypted = encryptionManager.decrypt(encrypted)
                val parts = decrypted.split(DELIMITER)
                if (parts.size == 2) {
                    return Pair(parts[0], parts[1])
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return null
    }

    /**
     * Clears the saved Supabase session tokens synchronously.
     */
    fun clearSupabaseTokensSync() {
        val prefs = context.getSharedPreferences("supabase_session", Context.MODE_PRIVATE)
        prefs.edit().remove(SUPABASE_TOKENS_KEY.name).apply()
    }
}
