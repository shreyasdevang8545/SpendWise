package com.tech.spendwise

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.content.Context

/**
 * Singleton object that manages the Supabase client instance.
 * Replace URL and Anon Key with actual credentials.
 */
object SupabaseInstance {
    private const val SUPABASE_URL = "https://barzqylbhgywfrajgyki.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_b7heHUw2SQjIv79LIBY4Ng_vbDmo7w3"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val client = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_KEY
    ) {
        install(Auth)
        install(Postgrest)
    }

    val auth get() = client.auth
    val postgrest get() = client.postgrest

    /**
     * Initialized background listener to save tokens automatically.
     */
    fun startSessionListener(context: Context) {
        val repository = PreferenceRepository(context)
        scope.launch {
            auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        repository.saveSupabaseTokensSync(
                            status.session.accessToken,
                            status.session.refreshToken
                        )
                    }
                    is SessionStatus.NotAuthenticated -> {
                        repository.clearSupabaseTokensSync()
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Centralized method to restore session from persistent storage.
     * This is now suspend to allow callers to wait for it.
     */
    suspend fun restoreSession(context: Context) {
        val repository = PreferenceRepository(context)
        val tokens = repository.getSupabaseTokensSync()
        
        if (tokens != null && !isLoggedIn()) {
            try {
                auth.importAuthToken(tokens.first, tokens.second, autoRefresh = true)
                // Once imported, also ensure listener is running if not already
                startSessionListener(context)
            } catch (e: Exception) {
                // Token might be invalid/expired, clear it
                repository.clearSupabaseTokensSync()
            }
        } else {
            // Even if no tokens, start listener to catch future login
            startSessionListener(context)
        }
    }

    /**
     * @return The current signed-in user's UID, or null if not authenticated.
     */
    fun currentUserId(): String? {
        return client.auth.currentSessionOrNull()?.user?.id
    }

    /**
     * @return True if a user is currently signed in.
     */
    fun isLoggedIn(): Boolean {
        return client.auth.currentSessionOrNull() != null
    }

    /**
     * Signs out the current user.
     */
    suspend fun signOut() {
        client.auth.signOut()
    }

    /**
     * @return The display name of the current user from metadata, if available.
     */
    fun currentUserDisplayName(): String? {
        val user = client.auth.currentSessionOrNull()?.user ?: return null
        return user.userMetadata?.get("display_name")?.jsonPrimitive?.contentOrNull
            ?: user.userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull
    }
}
