package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.navArgs
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.databinding.FragmentOtpBinding

/**
 * Screen 2 of auth flow: user enters the 6-digit OTP received on their phone.
 */
class OtpFragment : Fragment() {

    private var _binding: FragmentOtpBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by activityViewModels()
    private val args: OtpFragmentArgs by navArgs()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOtpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val phone = args.phoneNumber
        if (phone.isNotBlank()) {
            binding.otpSentToText.text = "A 6-digit code was sent to $phone"
        }

        authViewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.otpProgress.visibility = View.VISIBLE
                    binding.verifyButton.isEnabled = false
                }
                is AuthState.Success -> {
                    binding.otpProgress.visibility = View.GONE
                    (activity as? AuthActivity)?.startMainActivity()
                }
                is AuthState.Error -> {
                    binding.otpProgress.visibility = View.GONE
                    binding.verifyButton.isEnabled = true
                    UIUtils.showErrorSnackbar(binding.root, state.message)
                    authViewModel.resetState()
                }
                else -> {
                    binding.otpProgress.visibility = View.GONE
                    binding.verifyButton.isEnabled = true
                }
            }
        }

        binding.verifyButton.setOnClickListener { verifyCode() }

        binding.resendOtpText.setOnClickListener {
            if (phone.isNotBlank()) {
                authViewModel.resendOtp(phone, activity as AuthActivity)
                UIUtils.showSuccessSnackbar(binding.root, "OTP resent to $phone")
            }
        }
    }

    private fun verifyCode() {
        val code = binding.otpEditText.text?.toString()?.trim() ?: ""
        if (code.length != 6) {
            binding.otpInputLayout.error = "Enter the 6-digit OTP"
            return
        }
        binding.otpInputLayout.error = null
        authViewModel.verifyOtp(code)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
