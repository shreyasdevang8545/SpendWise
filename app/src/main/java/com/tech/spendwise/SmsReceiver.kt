package com.tech.spendwise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * BroadcastReceiver that intercepts incoming SMS messages and delegates
 * parsing to [TransactionExtractor].
 *
 * AndroidManifest registration required:
 * ```xml
 * <uses-permission android:name="android.permission.RECEIVE_SMS" />
 *
 * <receiver
 *     android:name=".SmsReceiver"
 *     android:exported="true"
 *     android:permission="android.permission.BROADCAST_SMS">
 *     <intent-filter android:priority="999">
 *         <action android:name="android.provider.Telephony.SMS_RECEIVED" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * IMPORTANT: Runtime permissions must be requested in MainActivity for Android 6.0+:
 * - android.permission.RECEIVE_SMS
 * - android.permission.READ_SMS
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED"
        private const val PDU_KEY = "pdus"
        private const val FORMAT_KEY = "format"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "onReceive() called. Action: ${intent?.action}")

        if (intent?.action == "com.tech.spendwise.TEST_ACTION") {
            Log.i(TAG, "Test action received!")
            if (context != null) {
                android.widget.Toast.makeText(context, "SpendWise: TEST Action Received!", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        if (intent?.action != SMS_RECEIVED_ACTION) {
            Log.d(TAG, "Ignoring intent with action: ${intent?.action}")
            return
        }

        Log.i(TAG, "SMS_RECEIVED intent detected!")

        if (context == null) {
            Log.e(TAG, "Context is null, cannot proceed.")
            return
        }

        // Check if SMS auto-scan is enabled
        val settingsManager = SettingsManager(context)
        val autoScanEnabled = runBlocking { settingsManager.smsAutoScan.first() }
        if (!autoScanEnabled) {
            Log.i(TAG, "SMS auto-scan is disabled in settings. Ignoring SMS.")
            return
        }

        val bundle = intent.extras ?: run {
            Log.w(TAG, "SMS intent received but extras bundle is null.")
            return
        }

        // Extract PDU format (e.g., "3gpp" or "3gpp2")
        val format = bundle.getString(FORMAT_KEY) ?: "3gpp"
        Log.d(TAG, "PDU Format: $format")

        // Pull raw PDU byte arrays from the bundle
        @Suppress("UNCHECKED_CAST")
        val pdus = bundle.get(PDU_KEY) as? Array<Any?> ?: run {
            Log.w(TAG, "No PDUs found in SMS bundle.")
            return
        }

        Log.d(TAG, "Found ${pdus.size} PDU(s)")

        var senderAddress: String? = null

        // Reconstruct the full message body by concatenating all PDU parts
        val fullBody = buildString {
            for ((index, pdu) in pdus.withIndex()) {
                pdu ?: continue
                try {
                    val smsMsg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SmsMessage.createFromPdu(pdu as ByteArray, format)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsMessage.createFromPdu(pdu as ByteArray)
                    }
                    
                    if (index == 0) {
                        senderAddress = smsMsg.originatingAddress
                    }
                    
                    val msgBody = smsMsg.messageBody ?: ""
                    Log.d(TAG, "PDU $index body: $msgBody")
                    append(msgBody)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing PDU $index: ${e.message}", e)
                }
            }
        }.trim()

        if (fullBody.isBlank()) {
            Log.w(TAG, "Reconstructed SMS body is blank — skipping.")
            return
        }

        Log.d(TAG, "Received SMS from $senderAddress: $fullBody")

        // Filter: only process if it's a bank transaction
        if (!TransactionExtractor.isBankTransaction(fullBody, senderAddress)) {
            Log.i(TAG, "Ignoring non-bank or promotional SMS from $senderAddress")
            return
        }

        Log.i(TAG, "Processing bank transaction SMS...")

        try {
            val json = TransactionExtractor.parseSms(fullBody)
            Log.i(TAG, "Parsed transaction JSON: $json")

            // Don't alert if there was a parsing error (e.g. missing crucial fields)
            if (json.contains("\"error\":")) {
                Log.w(TAG, "JSON contains error key, skipping alert.")
                return
            }

            // Extract amount for extra validation
            val pattern = Regex(""""amount"\s*:\s*([\d.]+)""")
            val amountMatch = pattern.find(json)
            val amountVal = amountMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            
            if (amountVal <= 0.0) {
                Log.i(TAG, "Amount is 0.0 or could not be parsed, skipping alert.")
                return
            }

            // Send notification or broadcast to UI
            persistAndAlert(context, json)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SMS: ${e.message}", e)
        }
    }

    private fun persistAndAlert(context: Context, json: String) {
        // Persist the transaction immediately using DataStore
        val repo = PreferenceRepository(context)
        runBlocking {
            repo.addTransaction(json)
        }

        // Check if transaction alerts (notifications) are enabled
        val settingsManager = SettingsManager(context)
        val alertsEnabled = runBlocking { settingsManager.transactionAlerts.first() }

        val intent = Intent(MainActivity.NEW_TRANSACTION_ACTION).apply {
            putExtra("transaction_json", json)
            `package` = context.packageName
        }

        // Send ordered broadcast. If MainActivity is in foreground, it will handle it.
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (resultCode != AppCompatActivity.RESULT_OK) {
                    // Not handled by activity -> App is in background
                    if (alertsEnabled) {
                        Log.i(TAG, "Broadcast not handled by activity. Showing notification.")
                        showNotification(ctx ?: context, json)
                    } else {
                        Log.i(TAG, "Broadcast not handled by activity. Transaction alerts disabled — skipping notification.")
                    }
                } else {
                    Log.i(TAG, "Broadcast handled by activity. Skipping notification.")
                }
            }
        }, null, 0, null, null)
    }

    private fun showNotification(context: Context, jsonString: String) {
        // Simple manual JSON parsing for notification text
        val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
        val data = mutableMapOf<String, String>()
        for (match in pattern.findAll(jsonString)) {
            val key = match.groupValues[1]
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            data[key] = value
        }

        val amount = data["amount"] ?: "0.0"
        val currency = data["currency"] ?: "INR"
        val type = data["type"] ?: "UNKNOWN"

        val typePrefix = if (type != "UNKNOWN") "$type " else ""
        val contentText = "${typePrefix}transaction found for $currency $amount. Tap to add more details."

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("transaction_json", jsonString)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, contentIntent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = System.currentTimeMillis().toInt()
        
        // Ignore Action
        val ignoreIntent = Intent(context, TransactionActionReceiver::class.java).apply {
            action = TransactionActionReceiver.ACTION_IGNORE
            putExtra("transaction_json", jsonString)
            putExtra("notification_id", notificationId)
        }
        val ignorePendingIntent = android.app.PendingIntent.getBroadcast(
            context, notificationId + 1, ignoreIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(context, MainActivity.TRANSACTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_account_balance)
            .setContentTitle("New Transaction Detected")
            .setContentText(contentText)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ignore", ignorePendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
