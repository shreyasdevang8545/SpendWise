package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentSecurityLockBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SecurityLockFragment : Fragment() {

    private var _binding: FragmentSecurityLockBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecurityLockBinding.inflate(inflater, container, false)
        settingsManager = SettingsManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backBtn.setOnClickListener {
            findNavController().navigateUp()
        }

        loadCurrentState()
        setupListeners()
    }

    private fun loadCurrentState() {
        lifecycleScope.launch {
            val lockEnabled = settingsManager.appLockEnabled.first()
            val pinSet = settingsManager.appLockPin.first().isNotEmpty()
            val biometricEnabled = settingsManager.biometricEnabled.first()

            binding.switchAppLock.isChecked = lockEnabled
            updateUIState(lockEnabled, pinSet)

            binding.switchBiometric.isChecked = biometricEnabled

            // Check biometric availability
            val biometricManager = BiometricManager.from(requireContext())
            val canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )

            when (canAuthenticate) {
                BiometricManager.BIOMETRIC_SUCCESS -> {
                    binding.biometricSubtitle.text = getString(R.string.security_biometric_available)
                }
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                    binding.biometricSubtitle.text = getString(R.string.security_biometric_no_hardware)
                    binding.switchBiometric.isEnabled = false
                }
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                    binding.biometricSubtitle.text = getString(R.string.security_biometric_unavailable)
                    binding.switchBiometric.isEnabled = false
                }
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    binding.biometricSubtitle.text = getString(R.string.security_biometric_not_enrolled)
                    binding.switchBiometric.isEnabled = false
                }
            }

            if (pinSet) {
                binding.pinTitle.text = getString(R.string.security_change_pin)
                binding.pinSubtitle.text = getString(R.string.security_pin_is_set)
            }
        }
    }

    private fun updateUIState(lockEnabled: Boolean, pinSet: Boolean) {
        val alpha = if (lockEnabled) 1.0f else 0.4f
        binding.pinSetupRow.alpha = alpha
        binding.pinSetupRow.isClickable = lockEnabled
        binding.biometricRow.alpha = if (lockEnabled && pinSet) 1.0f else 0.4f
        binding.switchBiometric.isEnabled = lockEnabled && pinSet
    }

    private fun setupListeners() {
        binding.switchAppLock.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                if (isChecked) {
                    // Ask to set PIN immediately
                    val existingPin = settingsManager.appLockPin.first()
                    if (existingPin.isEmpty()) {
                        showSetPinDialog { pinSet ->
                            if (pinSet) {
                                lifecycleScope.launch {
                                    settingsManager.setBoolean(SettingsManager.APP_LOCK_ENABLED, true)
                                    updateUIState(true, true)
                                }
                            } else {
                                binding.switchAppLock.isChecked = false
                            }
                        }
                    } else {
                        settingsManager.setBoolean(SettingsManager.APP_LOCK_ENABLED, true)
                        updateUIState(true, true)
                    }
                } else {
                    settingsManager.setBoolean(SettingsManager.APP_LOCK_ENABLED, false)
                    settingsManager.setBoolean(SettingsManager.BIOMETRIC_ENABLED, false)
                    binding.switchBiometric.isChecked = false
                    updateUIState(false, false)
                }
            }
        }

        binding.pinSetupRow.setOnClickListener {
            if (!binding.switchAppLock.isChecked) return@setOnClickListener
            showSetPinDialog { pinSet ->
                if (pinSet) {
                    lifecycleScope.launch {
                        binding.pinTitle.text = getString(R.string.security_change_pin)
                        binding.pinSubtitle.text = getString(R.string.security_pin_is_set)
                        updateUIState(true, true)
                    }
                }
            }
        }

        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsManager.setBoolean(SettingsManager.BIOMETRIC_ENABLED, isChecked)
            }
        }
    }

    private fun showSetPinDialog(callback: (Boolean) -> Unit) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(
            android.R.layout.simple_list_item_1, null
        )

        // Build a custom PIN input dialog
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        val pinInput = EditText(requireContext()).apply {
            hint = getString(R.string.security_enter_new_pin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }

        val confirmInput = EditText(requireContext()).apply {
            hint = getString(R.string.security_confirm_pin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }

        container.addView(pinInput)
        container.addView(confirmInput)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.security_set_pin))
            .setView(container)
            .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                val pin = pinInput.text.toString()
                val confirm = confirmInput.text.toString()

                if (pin.length != 4) {
                    Toast.makeText(requireContext(), getString(R.string.security_pin_must_be_4), Toast.LENGTH_SHORT).show()
                    callback(false)
                    return@setPositiveButton
                }

                if (pin != confirm) {
                    Toast.makeText(requireContext(), getString(R.string.security_pin_mismatch), Toast.LENGTH_SHORT).show()
                    callback(false)
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    settingsManager.setString(SettingsManager.APP_LOCK_PIN, pin)
                    Toast.makeText(requireContext(), getString(R.string.security_pin_saved), Toast.LENGTH_SHORT).show()
                    callback(true)
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel)) { _, _ ->
                callback(false)
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
