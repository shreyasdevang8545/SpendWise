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
                    showPermanentDenialDialog(getString(R.string.title_notifications), getString(R.string.reason_reminders_alerts))
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
                UIUtils.showActionSnackbar(rootView, getString(R.string.msg_new_transaction_found), getString(R.string.btn_review)) {
                    val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    navHostFragment.navController.navigate(R.id.reviewTransactionFragment)
                }

                // Mark broadcast as handled so SmsReceiver knows app is in foreground
                resultCode = AppCompatActivity.RESULT_OK
            }
        }
    }

     /* Requests RECEIVE_SMS permission.
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
                    showPermanentDenialDialog(getString(R.string.title_sms), getString(R.string.reason_detected_bank_sms))
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
        ensureDailyReminderScheduled()
 
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
                        SelectionBottomSheet.SelectionOption.CREDIT_CARD ->
                            navController.navigate(R.id.addCreditCardFragment)
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
            val name = getString(R.string.channel_transaction_alerts_name)
            val descriptionText = getString(R.string.channel_transaction_alerts_desc)
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
                getString(R.string.channel_lend_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_lend_reminders_desc)
            }
            notificationManager.createNotificationChannel(lendChannel)

            // Create Daily Reminder channel
            val dailyChannel = NotificationChannel(
                "daily_reminders",
                getString(R.string.channel_daily_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_daily_reminders_desc)
            }
            notificationManager.createNotificationChannel(dailyChannel)
            // 4. Budget Alerts channel
            val budgetChannel = NotificationChannel(
                "budget_alerts_channel",
                getString(R.string.channel_budget_alerts_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_budget_alerts_desc)
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
                        title = getString(R.string.dialog_notifications_permission_title),
                        message = getString(R.string.dialog_notifications_permission_msg),
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
        val permissions = arrayOf(Manifest.permission.RECEIVE_SMS)
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            val shouldShowRationale = missingPermissions.any { shouldShowRequestPermissionRationale(it) }
            if (shouldShowRationale) {
                showPermissionRationaleDialog(
                    title = getString(R.string.dialog_sms_access_title),
                    message = getString(R.string.dialog_sms_access_msg),
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
            .setPositiveButton(getString(R.string.btn_grant_access)) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.btn_not_now), null)
            .show()
    }

    private fun showPermanentDenialDialog(featureName: String, reason: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_permission_required_title, featureName))
            .setMessage(getString(R.string.dialog_permission_required_msg, featureName, reason))
            .setPositiveButton(getString(R.string.btn_go_to_settings)) { _, _ -> openAppSettings() }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    /**
     * Ensures the daily reminder is scheduled if enabled in settings.
     * This is called on every app launch to maintain alarm persistence.
     */
    private fun ensureDailyReminderScheduled() {
        val settingsManager = SettingsManager(this)
        lifecycleScope.launch {
            val isEnabled = settingsManager.dailyReminder.first()
            if (isEnabled) {
                val hour = settingsManager.dailyReminderHour.first()
                val minute = settingsManager.dailyReminderMinute.first()
                Log.i(TAG, "Ensuring daily reminder is scheduled for $hour:$minute")
                ReminderManager.scheduleDailyReminder(this@MainActivity, hour, minute)
            } else {
                Log.d(TAG, "Daily reminder is disabled in settings.")
            }
        }
    }
}