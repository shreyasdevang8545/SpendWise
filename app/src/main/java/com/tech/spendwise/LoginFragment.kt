package com.tech.spendwise

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.tech.spendwise.databinding.FragmentLoginBinding
import com.tech.spendwise.utils.UIUtils
import kotlinx.coroutines.launch

/**
 * Screen 1 of auth flow: user enters email/password or chooses Google Sign-In.
 * Handles login, password reset requests, and social authentication.
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupTextFields()
        observeViewModel()
    }

    private fun setupUI() {
        // Main Action
        binding.signInButton.setOnClickListener { performLogin() }
        
        // Navigation to Sign Up
        binding.signUpText.setOnClickListener {
            findNavController().navigate(R.id.action_loginFragment_to_signUpFragment)
        }

        // Google Authentication
        binding.googleSignInButton.setOnClickListener {
            handleGoogleSignIn()
        }

        // Forgot Password flow
        binding.forgotPasswordText.setOnClickListener {
            handleForgotPassword()
        }
    }

    private fun observeViewModel() {
        authViewModel.authState.observe(viewLifecycleOwner) { state ->
            updateLoadingState(state is AuthState.Loading)
            
            when (state) {
                is AuthState.Success -> {
                    (activity as? AuthActivity)?.startMainActivity()
                }
                is AuthState.ResetSent -> {
                    UIUtils.showSuccessSnackbar(binding.root, getString(R.string.msg_reset_email_sent))
                    authViewModel.resetState()
                }
                is AuthState.Error -> {
                    UIUtils.showErrorSnackbar(binding.root, state.message)
                    authViewModel.resetState()
                }
                else -> {}
            }
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        binding.loginProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.signInButton.isEnabled = !isLoading
        binding.googleSignInButton.isEnabled = !isLoading
        binding.forgotPasswordText.isEnabled = !isLoading
    }

    private fun handleForgotPassword() {
        val email = binding.emailEditText.text?.toString()?.trim() ?: ""
        if (email.isEmpty()) {
            binding.emailInputLayout.error = getString(R.string.error_enter_email_reset)
            UIUtils.showErrorSnackbar(binding.root, getString(R.string.error_enter_email_reset))
        } else {
            binding.emailInputLayout.error = null
            authViewModel.resetPassword(email)
        }
    }

    private fun handleGoogleSignIn() {
        val credentialManager = CredentialManager.create(requireContext())
        
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(R.string.google_web_client_id))
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(requireContext(), request)
                processGoogleCredential(result.credential)
            } catch (e: Exception) {
                handleGoogleSignInError(e)
            }
        }
    }

    private fun processGoogleCredential(credential: androidx.credentials.Credential) {
        when {
            credential is GoogleIdTokenCredential -> {
                authViewModel.signInWithGoogle(credential.idToken)
            }
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                try {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    authViewModel.signInWithGoogle(googleIdTokenCredential.idToken)
                } catch (e: GoogleIdTokenParsingException) {
                    Log.e("LoginFragment", "Invalid google id token response", e)
                    UIUtils.showErrorSnackbar(binding.root, "Google Sign-In failed: Invalid response")
                }
            }
            else -> {
                Log.e("LoginFragment", "Unexpected credential type: ${credential.type}")
                UIUtils.showErrorSnackbar(binding.root, "Google Sign-In failed: Unexpected response")
            }
        }
    }

    private fun handleGoogleSignInError(e: Exception) {
        Log.e("LoginFragment", "Google Sign-In Error", e)
        val isNotConfigured = getString(R.string.google_web_client_id) == "YOUR_WEB_CLIENT_ID_HERE"
        val errorMessage = if (isNotConfigured) {
            "Google Sign-In is not configured yet (missing Web Client ID)."
        } else {
            e.message ?: "Google Sign-In failed"
        }
        UIUtils.showErrorSnackbar(binding.root, errorMessage)
    }

    private fun setupTextFields() {
        binding.emailEditText.doAfterTextChanged { binding.emailInputLayout.error = null }
        binding.passwordEditText.doAfterTextChanged { binding.passwordInputLayout.error = null }

        binding.passwordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performLogin()
                true
            } else false
        }
    }

    private fun performLogin() {
        val email = binding.emailEditText.text?.toString()?.trim() ?: ""
        val password = binding.passwordEditText.text?.toString() ?: ""

        if (validateInputs(email, password)) {
            authViewModel.login(email, password)
        }
    }

    private fun validateInputs(email: String, pass: String): Boolean {
        var isValid = true
        if (email.isEmpty()) {
            binding.emailInputLayout.error = "Enter email"
            isValid = false
        }
        if (pass.isEmpty()) {
            binding.passwordInputLayout.error = "Enter password"
            isValid = false
        }
        return isValid
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
