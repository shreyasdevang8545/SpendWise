package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentSignUpBinding
import androidx.core.widget.doAfterTextChanged
import android.view.inputmethod.EditorInfo
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Screen for new user registration with email, password, and name.
 */
class SignUpFragment : Fragment() {

    private var _binding: FragmentSignUpBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSignUpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authViewModel.authState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.signUpProgress.visibility = View.VISIBLE
                    binding.signUpButton.isEnabled = false
                }
                is AuthState.Success -> {
                    binding.signUpProgress.visibility = View.GONE
                    // Supabase sends a confirmation email usually, 
                    // but we'll try to proceed to main activity.
                    (activity as? AuthActivity)?.startMainActivity()
                }
                is AuthState.Error -> {
                    binding.signUpProgress.visibility = View.GONE
                    binding.signUpButton.isEnabled = true
                    UIUtils.showErrorSnackbar(binding.root, state.message)
                    authViewModel.resetState()
                }
                else -> {
                    binding.signUpProgress.visibility = View.GONE
                    binding.signUpButton.isEnabled = true
                }
            }
        }

        binding.signUpButton.setOnClickListener { performSignUp() }
        
        binding.signInText.setOnClickListener {
            findNavController().navigateUp()
        }
 
        binding.googleSignInButton.setOnClickListener {
            handleGoogleSignIn()
        }
 
        setupTextFields()
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
                val credential = result.credential
                
                if (credential is GoogleIdTokenCredential) {
                    val idToken = credential.idToken
                    authViewModel.signInWithGoogle(idToken)
                } else {
                    Log.e("SignUpFragment", "Unexpected credential type: ${credential.type}")
                    UIUtils.showErrorSnackbar(binding.root, "Google Sign-In failed: Unexpected response")
                }
            } catch (e: Exception) {
                Log.e("SignUpFragment", "Google Sign-In Error", e)
                val errorMessage = if (getString(R.string.google_web_client_id) == "YOUR_WEB_CLIENT_ID_HERE") {
                    "Google Sign-In is not configured yet (missing Web Client ID)."
                } else {
                    e.message ?: "Google Sign-In failed"
                }
                UIUtils.showErrorSnackbar(binding.root, errorMessage)
            }
        }
    }
 
    private fun setupTextFields() {
        binding.nameEditText.doAfterTextChanged {
            binding.nameInputLayout.error = null
        }
        binding.emailEditText.doAfterTextChanged {
            binding.emailInputLayout.error = null
        }
        binding.passwordEditText.doAfterTextChanged {
            binding.passwordInputLayout.error = null
        }
 
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

        if (name.isEmpty()) {
            binding.nameInputLayout.error = "Enter name"
            return
        }
        if (email.isEmpty()) {
            binding.emailInputLayout.error = "Enter email"
            return
        }
        if (pass.length < 6) {
            binding.passwordInputLayout.error = "Password must be at least 6 characters"
            return
        }

        binding.nameInputLayout.error = null
        binding.emailInputLayout.error = null
        binding.passwordInputLayout.error = null
        
        authViewModel.signUp(email, pass, name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
