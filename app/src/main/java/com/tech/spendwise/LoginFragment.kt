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
            authViewModel.signInWithGoogle()
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
