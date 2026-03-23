package com.tech.spendwise

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/** UI states for the auth flow */
sealed class AuthState {
    object Idle           : AuthState()
    object Loading        : AuthState()
    object Success        : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel : ViewModel() {

    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> get() = _authState

    /**
     * Signs in with Email and Password using Supabase Auth.
     */
    fun login(email: String, pass: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                SupabaseInstance.auth.signInWith(Email) {
                    this.email = email
                    password = pass
                }
                _authState.value = AuthState.Success
            } catch (e: Exception) {
                _authState.value = AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

    /**
     * Signs up with Email and Password, and sets initial user metadata.
     */
    fun signUp(email: String, pass: String, name: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                SupabaseInstance.auth.signUpWith(Email) {
                    this.email = email
                    password = pass
                }
                SupabaseInstance.auth.updateUser {
                    data = buildJsonObject {
                        put("display_name", name)
                        put("full_name", name)
                    }
                }
                _authState.value = AuthState.Success
            } catch (e: Exception) {
                _authState.value = AuthState.Error(e.message ?: "Sign up failed")
            }
        }
    }

    /**
     * Initiates Google Sign-In (handled by Supabase OAuth flow).
     */
    fun signInWithGoogle() {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                // In a real Android app, you'd use a native Google Sign-In intent
                // and then call Supabase with the ID Token.
                // For simplicity, we trigger the OAuth flow.
                SupabaseInstance.auth.signInWith(Google)
                _authState.value = AuthState.Success
            } catch (e: Exception) {
                _authState.value = AuthState.Error(e.message ?: "Google Login failed")
            }
        }
    }

    fun resetState() {
        _authState.value = AuthState.Idle
    }
}
