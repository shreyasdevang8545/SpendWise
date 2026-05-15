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
import com.tech.spendwise.databinding.FragmentSignUpBinding
import com.tech.spendwise.utils.UIUtils
import kotlinx.coroutines.launch

/**
 * Screen for new user registration with email, password, and name.
 * Provides standard email signup and social authentication options.
 */
class SignUpFragment : Fragment() {

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, 
        container: ViewGroup?, 
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupTextFields()
        observeViewModel()
    }

    private fun setupUI() {
        // Main Registration Action
        binding.signUpButton.setOnClickListener { performSignUp() }
        
        // Navigation back to Sign In
        binding.signInText.setOnClickListener {
            findNavController().navigateUp()
        }

        // Social Authentication
        binding.googleSignInButton.setOnClickListener {
            handleGoogleSignIn()
        }
    }

    private fun observeViewModel() {
        authViewModel.authState.observe(viewLifecycleOwner) { state ->
            updateLoadingState(state is AuthState.Loading)
            
            when (state) {
                is AuthState.Success -> {
                    (activity as? AuthActivity)?.startMainActivity()
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
        binding.signUpProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.signUpButton.isEnabled = !isLoading
        binding.googleSignInButton.isEnabled = !isLoading
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
        if (credential is GoogleIdTokenCredential) {
            authViewModel.signInWithGoogle(credential.idToken)
        } else {
            Log.e("SignUpFragment", "Unexpected credential type: ${credential.type}")
            UIUtils.showErrorSnackbar(binding.root, "Google Sign-In failed: Unexpected response")
        }
    }

    private fun handleGoogleSignInError(e: Exception) {
        Log.e("SignUpFragment", "Google Sign-In Error", e)
        val isNotConfigured = getString(R.string.google_web_client_id) == "YOUR_WEB_CLIENT_ID_HERE"
        val errorMessage = if (isNotConfigured) {
            "Google Sign-In is not configured yet (missing Web Client ID)."
        } else {
            e.message ?: "Google Sign-In failed"
        }
        UIUtils.showErrorSnackbar(binding.root, errorMessage)
    }

    private fun setupTextFields() {
        binding.nameEditText.doAfterTextChanged { binding.nameInputLayout.error = null }
        binding.emailEditText.doAfterTextChanged { binding.emailInputLayout.error = null }
        binding.passwordEditText.doAfterTextChanged { binding.passwordInputLayout.error = null }

        binding.passwordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performSignUp()
                true
            } else false
        }
    }

    private fun performSignUp() {
        val name = binding.nameEditText.text?.toString()?.trim() ?: ""
        val email = binding.emailEditText.text?.toString()?.trim() ?: ""
        val pass = binding.passwordEditText.text?.toString() ?: ""

        if (validateInputs(name, email, pass)) {
            authViewModel.signUp(email, pass, name)
        }
    }

    private fun validateInputs(name: String, email: String, pass: String): Boolean {
        var isValid = true
        
        if (name.isEmpty()) {
            binding.nameInputLayout.error = "Enter name"
            isValid = false
        }
        
        if (email.isEmpty()) {
            binding.emailInputLayout.error = "Enter email"
            isValid = false
        }
        
        if (pass.length < 6) {
            binding.passwordInputLayout.error = "Password must be at least 6 characters"
            isValid = false
        }
        
        return isValid
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
