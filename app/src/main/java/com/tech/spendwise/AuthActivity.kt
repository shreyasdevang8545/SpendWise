package com.tech.spendwise

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.FirebaseAuth
import com.tech.spendwise.databinding.ActivityAuthBinding

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

        // If already authenticated, skip directly to main app
        if (FirebaseAuth.getInstance().currentUser != null) {
            startMainActivity()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // NavHostFragment is declared in the layout with app:navGraph — no manual setup needed
    }

    /** Called by [OtpFragment] (and on auto-verify) after successful sign-in. */
    fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
