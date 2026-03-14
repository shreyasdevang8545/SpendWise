package com.tech.spendwise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking

/**
 * Handles actions from the transaction notification (e.g., Ignore).
 */
class TransactionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val json = intent.getStringExtra("transaction_json") ?: return
        val notificationId = intent.getIntExtra("notification_id", -1)

        Log.i("TransactionAction", "Received action: $action")

        if (action == ACTION_IGNORE) {
            val repository = PreferenceRepository(context)
            runBlocking {
                repository.removeTransaction(json)
            }
            Log.i("TransactionAction", "Transaction ignored and removed.")
            
            // Dismiss the notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationId != -1) {
                notificationManager.cancel(notificationId)
            }
        }
    }

    companion object {
        const val ACTION_IGNORE = "com.tech.spendwise.ACTION_IGNORE"
    }
}
