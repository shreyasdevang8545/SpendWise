package com.tech.spendwise

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import kotlin.text.toDoubleOrNull

class ReminderReceiver : BroadcastReceiver() {
    private val repository = SupabaseRepository()

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Important: Restore session before checking UID or fetching data
                SupabaseInstance.restoreSession(context)
                
                val isDaily = intent.getBooleanExtra("is_daily_reminder", false)
                val lendId = intent.getStringExtra("lend_id")
                val uid = SupabaseInstance.currentUserId()
                
                if (uid == null) {
                    Log.w("ReminderReceiver", "No user logged in. Skipping reminder.")
                    return@launch
                }

                if (isDaily) {
                    if (intent.action == ACTION_SNOOZE_DAILY) {
                        Log.d("ReminderReceiver", "Snoozing daily reminder")
                        val snoozeTime = System.currentTimeMillis() + (60 * 60 * 1000) // 1 hour
                        ReminderManager.scheduleSnooze(context, snoozeTime)
                        
                        // Dismiss the notification
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(DAILY_REMINDER_ID)
                    } else {
                        handleDailyReminder(context)
                    }
                    return@launch
                }

                val isRecurring = intent.getBooleanExtra("is_recurring", false)
                if (isRecurring) {
                    val merchant = intent.getStringExtra("merchant") ?: "Transaction"
                    val amount = intent.getDoubleExtra("amount", 0.0)
                    val category = intent.getStringExtra("category") ?: "Expense"
                    
                    showRecurringNotification(context, merchant, amount, category)
                    
                    // Reschedule for next month
                    val originalJson = intent.getStringExtra("transaction_json")
                    if (originalJson != null) {
                        ReminderManager.scheduleRecurringTransaction(context, originalJson)
                    }
                    return@launch
                }

                if (lendId == null) return@launch

                val lendName = intent.getStringExtra("lend_name") ?: "Lend"
                val lendAmount = intent.getDoubleExtra("lend_amount", 0.0)

                Log.d("ReminderReceiver", "Fetching lend data for ID: $lendId")
                val lend = repository.getLendById(lendId)
                
                val shouldShow = lend == null || !lend.isReturned

                if (shouldShow) {
                    val displayLend = lend ?: com.tech.spendwise.models.LendTransaction(
                        id = lendId,
                        name = lendName,
                        amount = lendAmount,
                        paymentMode = "Unknown",
                        returnDate = 0L,
                        isReturned = false,
                        phoneNumber = null,
                        note = null
                    )

                    withContext(Dispatchers.Main) {
                        Log.d("ReminderReceiver", "Showing notification for: ${displayLend.name}")
                        showNotification(context, displayLend)
                    }
                    
                    // Reschedule for 24 hours later (persistent daily reminder)
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = System.currentTimeMillis()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                    
                    ReminderManager.scheduleReminder(
                        context,
                        displayLend.id!!,
                        displayLend.name,
                        displayLend.amount,
                        cal.timeInMillis
                    )
                } else {
                    Log.d("ReminderReceiver", "Lend already returned. Stopping reminders.")
                    ReminderManager.cancelReminder(context, lendId)
                }
            } catch (e: Exception) {
                Log.e("ReminderReceiver", "Error processing reminder: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, lend: com.tech.spendwise.models.LendTransaction) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Mark as Returned
        val returnIntent = Intent(context, LendActionReceiver::class.java).apply {
            action = LendActionReceiver.ACTION_MARK_RETURNED
            putExtra("lend_id", lend.id)
        }
        val returnPendingIntent = PendingIntent.getBroadcast(
            context, lend.id.hashCode() + 1, returnIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Remind Tomorrow (effectively just dismisses current and keeps the 24h schedule)
        val tomorrowIntent = Intent(context, LendActionReceiver::class.java).apply {
            action = LendActionReceiver.ACTION_REMIND_TOMORROW
            putExtra("lend_id", lend.id)
        }
        val tomorrowPendingIntent = PendingIntent.getBroadcast(
            context, lend.id.hashCode() + 2, tomorrowIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = android.net.Uri.parse(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + R.raw.google_notification)

        val notification = NotificationCompat.Builder(context, LEND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_account_balance)
            .setContentTitle(context.getString(R.string.title_money_return_reminder))
            .setContentText(context.getString(R.string.msg_money_return_content, lend.name, lend.amount.toString()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, context.getString(R.string.action_mark_returned), returnPendingIntent)
            .addAction(android.R.drawable.ic_menu_recent_history, context.getString(R.string.action_remind_tomorrow), tomorrowPendingIntent)
            .build()

        Log.d("ReminderReceiver", "Sending notification to manager for id: ${lend.id}. Channel: ${LEND_CHANNEL_ID}")
        notificationManager.notify(lend.id.hashCode(), notification)
    }

    private fun handleDailyReminder(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Add a deep link or extra to open Add Transaction screen
            data = android.net.Uri.parse("spendwise://add_transaction")
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context, DAILY_REMINDER_ID, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE_DAILY
            putExtra("is_daily_reminder", true)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context, DAILY_REMINDER_ID + 1, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = android.net.Uri.parse(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + R.raw.google_notification)

        val notification = NotificationCompat.Builder(context, DAILY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_account_balance)
            .setContentTitle(context.getString(R.string.title_add_your_expenses))
            .setContentText(context.getString(R.string.msg_log_spending_today))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_timer, context.getString(R.string.action_snooze), snoozePendingIntent)
            .build()

        notificationManager.notify(DAILY_REMINDER_ID, notification)

        // Reschedule for tomorrow
        val settingsManager = SettingsManager(context)
        GlobalScope.launch {
            val hour = settingsManager.dailyReminderHour.first()
            val minute = settingsManager.dailyReminderMinute.first()
            ReminderManager.scheduleDailyReminder(context, hour, minute)
        }
    }

    private fun showRecurringNotification(context: Context, merchant: String, amount: Double, category: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context, merchant.hashCode(), mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = android.net.Uri.parse(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + R.raw.google_notification)

        val notification = NotificationCompat.Builder(context, RECURRING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_history)
            .setContentTitle(context.getString(R.string.title_recurring_reminder))
            .setContentText(context.getString(R.string.msg_recurring_content, merchant, "₹$amount"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent)
            .build()

        notificationManager.notify(merchant.hashCode(), notification)
    }

    companion object {
        const val LEND_CHANNEL_ID = "lend_reminders_v2"
        const val DAILY_CHANNEL_ID = "daily_reminders_v2"
        const val RECURRING_CHANNEL_ID = "recurring_reminders_v2"
        private const val DAILY_REMINDER_ID = 1001
        const val ACTION_SNOOZE_DAILY = "com.tech.spendwise.ACTION_SNOOZE_DAILY"
    }
}
