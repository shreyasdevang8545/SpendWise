package com.tech.spendwise

import com.tech.spendwise.SupabaseInstance

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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.app.NotificationManager
import android.app.NotificationChannel
import androidx.core.view.WindowInsetsCompat
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.messaging.FirebaseMessaging

import androidx.activity.viewModels
import com.tech.spendwise.utils.UIUtils
import androidx.navigation.NavDeepLinkRequest
import android.net.Uri
import androidx.navigation.fragment.NavHostFragment
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

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "Notification permission granted")
            } else {
                Log.w(TAG, "Notification permission denied")
                if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    // Permanently denied
                    showPermanentDenialDialog("Notifications", "reminders and alerts")
                }
            }
        }
    
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
                Log.w(TAG, "Some permissions were denied")
                val deniedAnyPermanently = permissions.any { (perm, granted) ->
                    !granted && !shouldShowRequestPermissionRationale(perm)
                }
                if (deniedAnyPermanently) {
                    showPermanentDenialDialog("SMS", "automatic transaction detection")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        Log.e(TAG, "ONCREATE STARTED - DEBUGGING")

        // ── Auth restoration & guard ────────
        lifecycleScope.launch {
            SupabaseInstance.restoreSession(this@MainActivity)
            
            if (!SupabaseInstance.isLoggedIn()) {
                // If still not logged in, redirect
                startActivity(Intent(this@MainActivity, AuthActivity::class.java))
                finish()
            }
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
        initFcm()
 
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
                        SelectionBottomSheet.SelectionOption.SPLITWISE ->
                            navController.navigate(R.id.splitGroupsFragment)
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

        // ── Deep link: spendwise:// voice | join | add_transaction ──
        if (data?.scheme == "spendwise" && (data.host == "voice" || data.host == "join" || data.host == "add_transaction")) {
            Log.i(TAG, "Deep link received: $data")
            findViewById<View>(android.R.id.content).post {
                try {
                    val navHostFragment = supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    val request = NavDeepLinkRequest.Builder
                        .fromUri(data)
                        .build()
                    navHostFragment.navController.navigate(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to navigate: ${e.message}")
                }
            }
            return
        }

        // ── Deep link: GitHub Pages lend.html & join.html ─────────
        if (data?.scheme == "https"
            && data.host == "shreyasdevang8545.github.io"
            && (data.path?.startsWith("/SpendWise/lend.html") == true || data.path?.startsWith("/SpendWise/join.html") == true)
        ) {
            Log.i(TAG, "HTTPS deep link received: $data")
            findViewById<View>(android.R.id.content).post {
                try {
                    val navHostFragment = supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    val request = NavDeepLinkRequest.Builder
                        .fromUri(data)
                        .build()
                    navHostFragment.navController.navigate(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to navigate: ${e.message}")
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
            findViewById<View>(android.R.id.content).post {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                navHostFragment.navController.navigate(R.id.reviewTransactionFragment)
            }
        }

        // Clear intent to prevent re-processing on rotation or return
        intent?.data = null
        intent?.removeExtra("transaction_json")
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

            // Create Daily Reminder channel
            val dailyChannel = NotificationChannel(
                "daily_reminders",
                "Daily Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily nudge to log your spending"
            }
            notificationManager.createNotificationChannel(dailyChannel)
            // 4. Budget Alerts channel
            val budgetChannel = NotificationChannel(
                "budget_alerts_channel",
                "Budget Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when you exceed your set budget limits."
            }
            notificationManager.createNotificationChannel(budgetChannel)
        }
    }

    private fun initFcm() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result
            Log.d(TAG, "FCM Token: $token")

            // Save to Supabase
            lifecycleScope.launch {
                if (SupabaseInstance.isLoggedIn()) {
                    FcmTokenRepository().saveToken(token)
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    // Already granted
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    showPermissionRationaleDialog(
                        title = "Notifications Permission",
                        message = "SpendWise needs notification access to send you daily reminders, lend return alerts, and budget warnings.",
                        onConfirm = { requestNotificationPermissionLauncher.launch(permission) }
                    )
                }
                else -> {
                    requestNotificationPermissionLauncher.launch(permission)
                }
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
     * Signs the user out of Supabase and returns them to the AuthActivity.
     */
    fun signOut() {
        lifecycleScope.launch {
            SupabaseInstance.signOut()
            val intent = Intent(this@MainActivity, AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun requestSmsPermissions() {
        val permissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            val shouldShowRationale = missingPermissions.any { shouldShowRequestPermissionRationale(it) }
            if (shouldShowRationale) {
                showPermissionRationaleDialog(
                    title = "SMS Access",
                    message = "SMS permission allows SpendWise to automatically detect bank transactions from your messages, saving you time on manual logging.",
                    onConfirm = { requestSmsPermissionsLauncher.launch(missingPermissions.toTypedArray()) }
                )
            } else {
                requestSmsPermissionsLauncher.launch(missingPermissions.toTypedArray())
            }
        }
    }

    private fun showPermissionRationaleDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Grant Access") { _, _ -> onConfirm() }
            .setNegativeButton("Not Now", null)
            .show()
    }

    private fun showPermanentDenialDialog(featureName: String, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("$featureName Permission Required")
            .setMessage("You have denied $featureName access. This is required for $reason. Please enable it in app settings.")
            .setPositiveButton("Go to Settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}