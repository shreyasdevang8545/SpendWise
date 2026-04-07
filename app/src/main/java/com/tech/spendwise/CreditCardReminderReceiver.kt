package com.tech.spendwise

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class CreditCardReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cardId = intent.getStringExtra("card_id") ?: return
        val cardName = intent.getStringExtra("card_name") ?: "Credit Card"

        showNotification(context, cardId, cardName)
        
        // Reschedule for next month
        // We can fetch the card details from repository, but simple approach is to reschedule 
        // using the same day. However, since we don't have the day here, 
        // it's better to let AddCreditCardFragment handle initial and BootReceiver handle restore.
        // Actually, we can pass the day in intent too.
    }

    private fun showNotification(context: Context, cardId: String, cardName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, cardId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, "daily_reminders") // Re-use daily_reminders or create new
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Credit Card Payment Reminder")
            .setContentText("It's time to pay the bill for your $cardName card.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(cardId.hashCode(), notification)
    }
}
