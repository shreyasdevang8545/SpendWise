package com.tech.spendwise

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Repository for managing FCM tokens in Supabase.
 */
class FcmTokenRepository {

    private val postgrest = SupabaseInstance.client.postgrest
    private val TAG = "FcmTokenRepository"

    /**
     * Saves or updates the FCM token for the current user in Supabase.
     */
    suspend fun saveToken(token: String) = withContext(Dispatchers.IO) {
        val uid = SupabaseInstance.currentUserId() ?: return@withContext
        try {
            val json = buildJsonObject {
                put("uid", uid)
                put("fcm_token", token)
                put("updated_at", System.currentTimeMillis())
            }
            
            // Using upsert to handle both insert and update
            postgrest.from("user_fcm_tokens").upsert(json)
            Log.d(TAG, "FCM token saved successfully for user $uid")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving FCM token", e)
        }
    }
}
