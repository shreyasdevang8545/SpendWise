package com.tech.spendwise

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.IntentFilter
import android.provider.Telephony
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        init {
            Log.e("MainActivity", "CLASS LOADED IN MEMORY - STATIC BLOCK")
        }
    }

    private val smsReceiver = SmsReceiver()

    /**
     * Request launcher for SMS permissions (Android 6.0+).
     * Requests RECEIVE_SMS and READ_SMS permissions.
     */
    private val requestSmsPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                Log.i(TAG, "All SMS permissions granted!")
            } else {
                Log.w(TAG, "Some permissions were denied:")
                permissions.forEach { (permission, granted) ->
                    Log.w(TAG, "  $permission: $granted")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "ONCREATE STARTED - DEBUGGING")
        android.widget.Toast.makeText(this, "SpendWise: App Started!", android.widget.Toast.LENGTH_SHORT).show()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Request SMS permissions if running on Android 6.0+
        requestSmsPermissions()

        // Dynamically register SmReceiver as an extra layer (Android 8.0+ sometimes needs this)
        registerSmsReceiver()
    }

    private val foregroundSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e("MainActivity", "FOREGROUND SMS RECEIVED: ${intent?.action}")
            android.widget.Toast.makeText(context, "SpendWise: Foreground SMS Intercepted!", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSmsReceiver() {
        try {
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                priority = 9999
                addAction("com.tech.spendwise.TEST_ACTION")
            }
            
            // Register both the class-based receiver and the foreground diagnostic one
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(this, smsReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
                ContextCompat.registerReceiver(this, foregroundSmsReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            } else {
                registerReceiver(smsReceiver, filter)
                registerReceiver(foregroundSmsReceiver, filter)
            }
            Log.i(TAG, "SmsReceiver registered dynamically (exported).")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SmsReceiver dynamically: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(smsReceiver)
            unregisterReceiver(foregroundSmsReceiver)
        } catch (e: Exception) {
            // Might not be registered
        }
    }

    /**
     * Requests SMS permissions required for SmsReceiver to work.
     * Only called on Android 6.0+ (API 23+).
     */
    private fun requestSmsPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Check RECEIVE_SMS
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECEIVE_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }

        // Check READ_SMS
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting SMS permissions: $permissionsToRequest")
            requestSmsPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "SMS permissions already granted.")
        }
    }
}