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
import com.tech.spendwise.utils.NetworkMonitor
import androidx.navigation.NavDeepLinkRequest
import android.net.Uri
import androidx.navigation.fragment.NavHostFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val TRANSACTION_CHANNEL_ID = "transaction_alerts_v2"
        const val NEW_TRANSACTION_ACTION = "com.tech.spendwise.NEW_TRANSACTION"
        
        init {
            Log.e("MainActivity", "CLASS LOADED IN MEMORY - STATIC BLOCK")
        }
    }

    private val smsReceiver = SmsReceiver()
    private val viewModel: TransactionViewModel by viewModels()

    private lateinit var appUpdateManager: AppUpdateManager
    private val UPDATE_REQUEST_CODE = 100    
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
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val smsGranted = permissions[Manifest.permission.RECEIVE_SMS] == true
            val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.POST_NOTIFICATIONS] == true
            } else true

            if (smsGranted) {
                Log.i(TAG, "SMS permission granted!")
            } else {
                Log.w(TAG, "SMS permission denied")
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) {
                    showPermanentDenialDialog(getString(R.string.title_sms), getString(R.string.reason_detected_bank_sms))
                }
            }

            if (notificationGranted) {
                Log.i(TAG, "Notification permission granted!")
            } else {
                Log.w(TAG, "Notification permission denied")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                        UIUtils.showActionSnackbar(
                            findViewById(android.R.id.content),
                            "Notifications are blocked. Please enable them in settings to receive alerts.",
                            "Settings"
                        ) { openAppSettings() }
                    }
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
            } else {
                checkAppStatusAndPro()
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

        requestPermissions()
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

                        SelectionBottomSheet.SelectionOption.SPLITWISE ->
                            Toast.makeText(this, getString(R.string.msg_coming_soon), Toast.LENGTH_SHORT).show()
                        /* Commented for Release
                        SelectionBottomSheet.SelectionOption.CREDIT_CARD ->
                            navController.navigate(R.id.addCreditCardFragment)
                        */
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

        // Hide BottomNav on internal screens
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.homeFragment,
                R.id.budgetDashboardFragment,
                R.id.analyticsFragment,
                R.id.transactionHistoryFragment -> {
                    bottomNav.visibility = View.VISIBLE
                }
                else -> {
                    bottomNav.visibility = View.GONE
                }
            }
        }

        // Handle intent if app was opened via notification
        handleIntent(intent)

        setupNetworkMonitoring()
        checkForAppUpdates()
    }

    private fun checkForAppUpdates() {
        appUpdateManager = AppUpdateManagerFactory.create(this)
        
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.IMMEDIATE,
                        this,
                        UPDATE_REQUEST_CODE
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start update flow: ${e.message}")
                }
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
                Log.d(TAG, "Network status: ${if (isConnected) "Connected" else "Disconnected"}")
            }
        }

        retryButton.setOnClickListener {
            // The monitor is automatic, but we can log or show a toast to indicate we're checking
            Toast.makeText(this, getString(R.string.status_loading), Toast.LENGTH_SHORT).show()
        }
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
            val soundUri = Uri.parse(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + packageName + "/" + R.raw.google_notification)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val name = getString(R.string.channel_transaction_alerts_name)
            val descriptionText = getString(R.string.channel_transaction_alerts_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(TRANSACTION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setSound(soundUri, audioAttributes)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            // Create Lend Remainder channel
            val lendChannel = NotificationChannel(
                "lend_reminders_v2",
                getString(R.string.channel_lend_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_lend_reminders_desc)
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(lendChannel)

            // Create Daily Reminder channel
            val dailyChannel = NotificationChannel(
                "daily_reminders_v2",
                getString(R.string.channel_daily_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_daily_reminders_desc)
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(dailyChannel)
            // 4. Budget Alerts channel
            val budgetChannel = NotificationChannel(
                "budget_alerts_channel_v2",
                getString(R.string.channel_budget_alerts_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_budget_alerts_desc)
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(budgetChannel)

            // 5. Recurring Reminders channel
            val recurringChannel = NotificationChannel(
                "recurring_reminders_v2",
                "Recurring Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for your recurring transactions"
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(recurringChannel)
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


    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(NEW_TRANSACTION_ACTION)
        filter.priority = 100
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, transactionBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transactionBroadcastReceiver, filter)
        }

        // Resume Immediate update if in progress
        if (::appUpdateManager.isInitialized) {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            AppUpdateType.IMMEDIATE,
                            this,
                            UPDATE_REQUEST_CODE
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resume update flow: ${e.message}")
                    }
                }
            }
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

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            val shouldShowRationale = missingPermissions.any { shouldShowRequestPermissionRationale(it) }
            if (shouldShowRationale) {
                showPermissionRationaleDialog(
                    title = "Permissions Required",
                    message = "SpendWise needs SMS access to track expenses and Notification access to send you alerts and reminders.",
                    onConfirm = { requestPermissionsLauncher.launch(missingPermissions.toTypedArray()) }
                )
            } else {
                requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
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

    private fun checkAppStatusAndPro() {
        val settingsManager = SettingsManager(this)
        val uid = SupabaseInstance.currentUserId() ?: return
        
        lifecycleScope.launch {
            val repo = SupabaseRepository()
            
            // 1. Check Maintenance Mode
            val (isMaintenanceMode, message) = repo.checkAppConfig()
            if (isMaintenanceMode) {
                val intent = Intent(this@MainActivity, MaintenanceActivity::class.java)
                intent.putExtra("maintenance_message", message)
                startActivity(intent)
                finish() // Prevent going back to MainActivity
                return@launch
            }

            // 2. Check Pro Status
            val isProInDb = repo.checkIfUserIsPro(uid)
            if (isProInDb) {
                settingsManager.setBoolean(SettingsManager.IS_PRO_USER, true)
                settingsManager.setString(SettingsManager.PRO_PLAN_TYPE, "Lifetime")
            } else {
                // Do not auto-revoke if they purchased via Google Play locally, 
                // but if backend is the absolute source of truth, you could set to false here.
                // Keeping it as is to allow local/Play Store pro users to coexist.
            }
        }
    }
}