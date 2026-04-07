package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentLoginBinding
import androidx.core.widget.doAfterTextChanged
import android.view.inputmethod.EditorInfo
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Screen 1 of auth flow: user enters email/password or chooses Google Sign-In.
 */
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authViewModel.authState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.loginProgress.visibility = View.VISIBLE
                    binding.signInButton.isEnabled = false
                    binding.googleSignInButton.isEnabled = false
                }
                is AuthState.Success -> {
                    binding.loginProgress.visibility = View.GONE
                    (activity as? AuthActivity)?.startMainActivity()
                }
                is AuthState.Error -> {
                    binding.loginProgress.visibility = View.GONE
                    binding.signInButton.isEnabled = true
                    binding.googleSignInButton.isEnabled = true
                    UIUtils.showErrorSnackbar(binding.root, state.message)
                    authViewModel.resetState()
                }
                else -> {
                    binding.loginProgress.visibility = View.GONE
                    binding.signInButton.isEnabled = true
                    binding.googleSignInButton.isEnabled = true
                }
            }
        }

        binding.signInButton.setOnClickListener { performLogin() }
        
        binding.signUpText.setOnClickListener {
            findNavController().navigate(R.id.action_loginFragment_to_signUpFragment)
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
                
                when {
                    credential is GoogleIdTokenCredential -> {
                        val idToken = credential.idToken
                        authViewModel.signInWithGoogle(idToken)
                    }
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                        try {
                            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                            authViewModel.signInWithGoogle(googleIdTokenCredential.idToken)
                        } catch (e: GoogleIdTokenParsingException) {
                            Log.e("LoginFragment", "Received an invalid google id token response", e)
                            UIUtils.showErrorSnackbar(binding.root, "Google Sign-In failed: Invalid response")
                        }
                    }
                    else -> {
                        Log.e("LoginFragment", "Unexpected credential type: ${credential.type}")
                        UIUtils.showErrorSnackbar(binding.root, "Google Sign-In failed: Unexpected response")
                    }
                }
            } catch (e: Exception) {
                Log.e("LoginFragment", "Google Sign-In Error", e)
                // If the user hasn't replaced the placeholder, show a more helpful message
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
        binding.emailEditText.doAfterTextChanged {
            binding.emailInputLayout.error = null
        }
        binding.passwordEditText.doAfterTextChanged {
            binding.passwordInputLayout.error = null
        }
 
        binding.passwordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performLogin()
                true
            } else false
        }
    }

    private fun performLogin() {
        val email = binding.emailEditText.text?.toString()?.trim() ?: ""
        val pass = binding.passwordEditText.text?.toString() ?: ""

        if (email.isEmpty()) {
            binding.emailInputLayout.error = "Enter email"
            return
        }
        if (pass.isEmpty()) {
            binding.passwordInputLayout.error = "Enter password"
            return
        }

        binding.emailInputLayout.error = null
        binding.passwordInputLayout.error = null
        
        authViewModel.login(email, pass)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
