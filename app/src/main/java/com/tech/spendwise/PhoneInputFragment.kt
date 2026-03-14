package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentPhoneInputBinding

/**
 * Screen 1 of auth flow: user enters their phone number and requests an OTP.
 */
class PhoneInputFragment : Fragment() {

    private var _binding: FragmentPhoneInputBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        authViewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.phoneProgress.visibility = View.VISIBLE
                    binding.sendOtpButton.isEnabled  = false
                }
                is AuthState.CodeSent -> {
                    binding.phoneProgress.visibility = View.GONE
                    binding.sendOtpButton.isEnabled  = true
                    val input = binding.phoneEditText.text?.toString() ?: ""
                    val fullPhone = if (input.isNotBlank()) "+91$input" else ""
                    // Navigate to OTP screen
                    val action = PhoneInputFragmentDirections.actionPhoneInputToOtp(phoneNumber = fullPhone)
                    findNavController().navigate(action)
                    authViewModel.resetState()
                }
                is AuthState.Error -> {
                    binding.phoneProgress.visibility = View.GONE
                    binding.sendOtpButton.isEnabled  = true
                    UIUtils.showErrorSnackbar(binding.root, state.message)
                    authViewModel.resetState()
                }
                is AuthState.Success -> {
                    // Auto-verified (rare on emulator). Pass to activity.
                    (activity as? AuthActivity)?.startMainActivity()
                }
                else -> {
                    binding.phoneProgress.visibility = View.GONE
                    binding.sendOtpButton.isEnabled  = true
                }
            }
        }

        binding.sendOtpButton.setOnClickListener { sendOtp() }
        binding.phoneEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { sendOtp(); true } else false
        }
    }

    private fun sendOtp() {
        val input = binding.phoneEditText.text?.toString()?.trim() ?: ""
        if (input.length != 10) {
            binding.phoneInputLayout.error = "Enter a valid 10-digit phone number"
            return
        }
        val fullPhone = "+91$input"
        binding.phoneInputLayout.error = null
        authViewModel.sendOtp(fullPhone, activity as AuthActivity)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
