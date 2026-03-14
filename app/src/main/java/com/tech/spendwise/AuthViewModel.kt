package com.tech.spendwise

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

/** UI states for the phone-auth flow */
sealed class AuthState {
    object Idle           : AuthState()
    object Loading        : AuthState()
    data class CodeSent(val verificationId: String, val resendToken: PhoneAuthProvider.ForceResendingToken) : AuthState()
    object Success        : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * ViewModel shared between [PhoneInputFragment] and [OtpFragment].
 * Drives Firebase Phone Authentication.
 */
class AuthViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()

    private val _state = MutableLiveData<AuthState>(AuthState.Idle)
    val state: LiveData<AuthState> = _state

    // Kept across fragment navigation
    private var storedVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    // ── Phone verification ────────────────────────────────────────────────

    fun sendOtp(phoneNumber: String, activity: AuthActivity) {
        _state.value = AuthState.Loading

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Auto-retrieved or instant verify
                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                _state.value = AuthState.Error(e.message ?: "Verification failed")
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                storedVerificationId = verificationId
                resendToken          = token
                _state.value = AuthState.CodeSent(verificationId, token)
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .also { builder ->
                resendToken?.let { builder.setForceResendingToken(it) }
            }
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    fun resendOtp(phoneNumber: String, activity: AuthActivity) {
        sendOtp(phoneNumber, activity)
    }

    fun verifyOtp(code: String) {
        val verificationId = storedVerificationId ?: run {
            _state.value = AuthState.Error("Session expired. Please resend OTP.")
            return
        }
        _state.value = AuthState.Loading
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        signInWithCredential(credential)
    }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { _state.value = AuthState.Success }
            .addOnFailureListener { e ->
                _state.value = AuthState.Error(e.message ?: "Sign-in failed")
            }
    }

    fun resetState() {
        _state.value = AuthState.Idle
    }
}
