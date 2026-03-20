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

class ReminderReceiver : BroadcastReceiver() {
    private val repository = FirestoreRepository()

    override fun onReceive(context: Context, intent: Intent) {
        val isDaily = intent.getBooleanExtra("is_daily_reminder", false)
        val lendId = intent.getStringExtra("lend_id")
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return

        if (isDaily) {
            handleDailyReminder(context)
            return
        }

        if (lendId == null) return

        Log.d("ReminderReceiver", "Alarm fired for lend: $lendId")

        // Fetch latest status from Firestore
        repository.getLendById(uid, lendId) { lend ->
            if (lend != null && !lend.isReturned) {
                // Show notification with actions
                showNotification(context, lend)
                
                // Reschedule for 24 hours later (persistent daily reminder)
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                
                ReminderManager.scheduleReminder(
                    context,
                    lend.id!!,
                    lend.name,
                    lend.amount,
                    cal.timeInMillis
                )
            } else if (lend?.isReturned == true) {
                Log.d("ReminderReceiver", "Lend $lendId already returned. Stopping reminders.")
                ReminderManager.cancelReminder(context, lendId)
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

        val notification = NotificationCompat.Builder(context, LEND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Money Return Reminder")
            .setContentText("${lend.name} was supposed to return ₹${lend.amount}.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Mark Returned", returnPendingIntent)
            .addAction(android.R.drawable.ic_menu_recent_history, "Remind Tomorrow", tomorrowPendingIntent)
            .build()

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

        val notification = NotificationCompat.Builder(context, DAILY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle("Add Your Expenses")
            .setContentText("Don't forget to log your spending for today!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(mainPendingIntent)
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

    companion object {
        const val LEND_CHANNEL_ID = "lend_reminders"
        const val DAILY_CHANNEL_ID = "daily_reminders"
        private const val DAILY_REMINDER_ID = 1001
    }
}
