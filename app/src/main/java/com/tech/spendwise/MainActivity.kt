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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout

import androidx.activity.viewModels
import com.tech.spendwise.utils.UIUtils
import androidx.navigation.NavDeepLinkRequest
import android.net.Uri
import androidx.navigation.fragment.NavHostFragment
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val TRANSACTION_CHANNEL_ID = "transaction_alerts"
        const val NEW_TRANSACTION_ACTION = "com.tech.spendwise.NEW_TRANSACTION"
        
        init {
            Log.e("MainActivity", "CLASS LOADED IN MEMORY - STATIC BLOCK")
        }
    }

    private val smsReceiver = SmsReceiver()
    private val viewModel: TransactionViewModel by viewModels()
    
    /**
     * Receiver for transaction data sent from SmsReceiver when app is in foreground.
     */
    private val transactionBroadcastReceiverFilter = IntentFilter(NEW_TRANSACTION_ACTION)
    private val transactionBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra("transaction_json")
            if (json != null) {
                Log.i(TAG, "Foreground transaction broadcast received!")
                
                // Update ViewModel state
                viewModel.addTransaction(json)
                
                // Show Snackbar
                val rootView = findViewById<View>(android.R.id.content)
                UIUtils.showActionSnackbar(rootView, "New Transaction Found", "Review") {
                    val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    navHostFragment.navController.navigate(R.id.reviewTransactionFragment)
                }

                // Mark broadcast as handled so SmsReceiver knows app is in foreground
                resultCode = AppCompatActivity.RESULT_OK
            }
        }
    }

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
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        Log.e(TAG, "ONCREATE STARTED - DEBUGGING")

        // ── Auth guard: redirect to AuthActivity if not signed in ────────
        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        // Handle window insets for fragment container (Top only, bottom is handled by nav card)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_host_fragment)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0) // No bottom padding here
            insets
        }

        requestSmsPermissions()
        requestNotificationPermission()
        registerSmsReceiver()
        createNotificationChannel()
 
        // Set up Bottom Navigation
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
        
        // Connect BottomNav with NavController
        androidx.navigation.ui.NavigationUI.setupWithNavController(bottomNav, navController)

        // Modern Animations for Navigation without breaking NavigationUI
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.addTransactionFragment) {
                // Show Selection Bottom Sheet instead of direct navigation
                SelectionBottomSheet { option ->
                    when (option) {
                        SelectionBottomSheet.SelectionOption.TRANSACTION -> 
                            navController.navigate(R.id.addTransactionFragment)
                        SelectionBottomSheet.SelectionOption.LEND -> 
                            navController.navigate(R.id.addLendFragment)
                        SelectionBottomSheet.SelectionOption.HISTORY -> 
                            navController.navigate(R.id.lendHistoryFragment)
                    }
                }.show(supportFragmentManager, SelectionBottomSheet.TAG)
                false // We handle the selection ourselves for this item
            } else {
                // Standard NavigationUI handling
                androidx.navigation.ui.NavigationUI.onNavDestinationSelected(item, navController)
            }
        }

        // Apply Insets to the bottom nav
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        // Handle intent if app was opened via notification
        handleIntent(intent)
    }

    // Helper for DP to PX
    private fun Int.toPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data

        // ── Deep link: spendwise://voice ────────────────────────────────────
        // Triggered by Google Assistant shortcut or adb:
        //   adb shell am start -a android.intent.action.VIEW -d "spendwise://voice" com.tech.spendwise
        if (data?.scheme == "spendwise" && data.host == "voice") {
            Log.i(TAG, "Deep link received: $data — opening voice entry")
            findViewById<View>(android.R.id.content).post {
                try {
                    val navHostFragment = supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    // Navigate using the deep link URI so NavController resolves it via nav-graph
                    val request = NavDeepLinkRequest.Builder
                        .fromUri(Uri.parse("spendwise://voice"))
                        .build()
                    navHostFragment.navController.navigate(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to navigate to voice: ${e.message}")
                }
            }
            return
        }

        // ── SMS transaction from notification ───────────────────────────────
        val json = intent?.getStringExtra("transaction_json")
        if (json != null) {
            Log.i(TAG, "Transaction data found in intent!")
            viewModel.addTransaction(json)

            // Navigate to Review screen if app was opened via notification
            // We use post to ensure NavController is ready
            findViewById<View>(android.R.id.content).post {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHostFragment.navController.navigate(R.id.reviewTransactionFragment)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Transaction Alerts"
            val descriptionText = "Notifications for detected bank transactions"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(TRANSACTION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            // Create Lend Remainder channel
            val lendChannel = NotificationChannel(
                "lend_reminders",
                "Lend Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for money return commitments"
            }
            notificationManager.createNotificationChannel(lendChannel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(NEW_TRANSACTION_ACTION)
        filter.priority = 100
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, transactionBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transactionBroadcastReceiver, filter)
        }

        // Check app lock
        checkAppLock()
    }

    override fun onPause() {
        super.onPause()
        SpendWiseLockManager.onAppBackgrounded()
        try {
            unregisterReceiver(transactionBroadcastReceiver)
        } catch (e: Exception) {}
    }

    private fun checkAppLock() {
        SpendWiseLockManager.onAppForegrounded()
        val lockEnabled = runBlocking { SettingsManager(this@MainActivity).appLockEnabled.first() }
        val pinSet = runBlocking { SettingsManager(this@MainActivity).appLockPin.first().isNotEmpty() }
        if (lockEnabled && pinSet && SpendWiseLockManager.shouldShowLockScreen()) {
            startActivity(Intent(this, LockActivity::class.java))
        }
    }

    private val foregroundSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.e("MainActivity", "FOREGROUND SMS RECEIVED: ${intent?.action}")
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSmsReceiver() {
        try {
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                priority = 9999
                addAction("com.tech.spendwise.TEST_ACTION")
            }
            
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
        } catch (e: Exception) {}
    }

    /**
     * Signs the user out of Firebase and returns them to the AuthActivity.
     */
    fun signOut() {
        FirebaseAuth.getInstance().signOut()
        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun requestSmsPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
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