package com.tech.spendwise

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.tech.spendwise.databinding.ActivityLockBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var settingsManager: SettingsManager

    private var enteredPin = StringBuilder()
    private var storedPin = ""
    private var attempts = 0
    private val maxAttempts = 5

    private val dots by lazy {
        listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        // Load stored PIN
        storedPin = runBlocking { settingsManager.appLockPin.first() }

        setupNumberPad()
        setupBiometric()
    }

    private fun setupNumberPad() {
        val buttons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )

        buttons.forEach { btn ->
            btn.setOnClickListener {
                if (enteredPin.length < 4) {
                    enteredPin.append(btn.text)
                    updateDots()

                    if (enteredPin.length == 4) {
                        verifyPin()
                    }
                }
            }
        }

        binding.btnBackspace.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin.deleteCharAt(enteredPin.length - 1)
                updateDots()
                binding.errorText.visibility = View.GONE
            }
        }
    }

    private fun updateDots() {
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index < enteredPin.length) R.drawable.bg_pin_dot_filled
                else R.drawable.bg_pin_dot
            )
        }
    }

    private fun verifyPin() {
        if (enteredPin.toString() == storedPin) {
            onUnlocked()
        } else {
            attempts++
            enteredPin.clear()
            updateDots()

            if (attempts >= maxAttempts) {
                binding.errorText.text = getString(R.string.lock_too_many_attempts)
                binding.errorText.visibility = View.VISIBLE
                binding.numberPad.alpha = 0.3f
                binding.numberPad.isEnabled = false
                // Re-enable after 30 seconds
                binding.numberPad.postDelayed({
                    binding.numberPad.alpha = 1f
                    binding.numberPad.isEnabled = true
                    attempts = 0
                    binding.errorText.visibility = View.GONE
                }, 30_000)
            } else {
                binding.errorText.text = getString(R.string.lock_wrong_pin, maxAttempts - attempts)
                binding.errorText.visibility = View.VISIBLE

                // Shake animation on dots
                dots.forEach { dot ->
                    dot.animate()
                        .translationX(10f).setDuration(50)
                        .withEndAction {
                            dot.animate().translationX(-10f).setDuration(50).withEndAction {
                                dot.animate().translationX(0f).setDuration(50).start()
                            }.start()
                        }.start()
                }
            }
        }
    }

    private fun setupBiometric() {
        val biometricEnabled = runBlocking { settingsManager.biometricEnabled.first() }
        if (!biometricEnabled) {
            binding.btnBiometric.visibility = View.GONE
            return
        }

        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            binding.btnBiometric.visibility = View.GONE
            return
        }

        binding.btnBiometric.visibility = View.VISIBLE
        binding.btnBiometric.setOnClickListener {
            showBiometricPrompt()
        }

        // Auto-show biometric on open
        binding.root.post { showBiometricPrompt() }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // User cancelled or error — fall back to PIN
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(this@LockActivity,
                        getString(R.string.lock_biometric_failed), Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_biometric_title))
            .setSubtitle(getString(R.string.lock_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun onUnlocked() {
        // Mark as unlocked
        SpendWiseLockManager.onUnlocked()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Don't allow back to bypass lock
        moveTaskToBack(true)
    }
}
