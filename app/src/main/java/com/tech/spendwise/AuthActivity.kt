package com.tech.spendwise

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
// FirebaseAuth removed
import com.tech.spendwise.SupabaseInstance
import com.tech.spendwise.utils.NetworkMonitor
import android.view.View
import android.util.Log
import android.widget.Toast
import com.tech.spendwise.databinding.ActivityAuthBinding
import kotlinx.coroutines.flow.first

/**
 * Entry-point activity for phone-number authentication.
 * Skips itself if the user is already signed in.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        // Show a blank/loading state during restoration to prevent flickering
        // Alternatively, set a splash theme in manifest
        
        lifecycleScope.launch {
            val onboardingCompleted = SettingsManager(this@AuthActivity).onboardingCompleted.first()
            if (!onboardingCompleted) {
                startActivity(Intent(this@AuthActivity, OnboardingActivity::class.java))
                finish()
                return@launch
            }

            intent?.let { SupabaseInstance.handleIntent(it) }
            SupabaseInstance.restoreSession(this@AuthActivity)

            if (SupabaseInstance.isLoggedIn()) {
                // If still not logged in, redirect
                startMainActivity()
                finish()
            } else {
                // If not logged in, THEN inflate and show the login UI
                binding = ActivityAuthBinding.inflate(layoutInflater)
                setContentView(binding.root)
                setupNetworkMonitoring()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch {
            SupabaseInstance.handleIntent(intent)
            if (SupabaseInstance.isLoggedIn()) {
                startMainActivity()
                finish()
            }
        }
    }

    private fun setupNetworkMonitoring() {
        val networkMonitor = NetworkMonitor(this)
        val noInternetOverlay = findViewById<View>(R.id.no_internet_container)
        val retryButton = findViewById<View>(R.id.btn_retry_internet)

        lifecycleScope.launch {
            networkMonitor.isConnected.collect { isConnected ->
                noInternetOverlay.visibility = if (isConnected) View.GONE else View.VISIBLE
                Log.d("AuthActivity", "Network status: ${if (isConnected) "Connected" else "Disconnected"}")
            }
        }

        retryButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.status_loading), Toast.LENGTH_SHORT).show()
        }
    }

    /** Called by [OtpFragment] (and on auto-verify) after successful sign-in. */
    fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
