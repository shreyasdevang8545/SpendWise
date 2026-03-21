package com.tech.spendwise

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
// FirebaseAuth removed
import com.tech.spendwise.databinding.ActivityAuthBinding
import com.tech.spendwise.SupabaseInstance

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
            SupabaseInstance.restoreSession(this@AuthActivity)

            if (SupabaseInstance.isLoggedIn()) {
                startMainActivity()
                finish()
            } else {
                // If not logged in, THEN inflate and show the login UI
                binding = ActivityAuthBinding.inflate(layoutInflater)
                setContentView(binding.root)
            }
        }
    }

    /** Called by [OtpFragment] (and on auto-verify) after successful sign-in. */
    fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
