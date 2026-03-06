package com.tech.spendwise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log

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

            android.widget.Toast.makeText(context, "SpendWise: Processed Bank Transaction!", android.widget.Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SMS: ${e.message}", e)
        }
    }
}
