package com.tech.spendwise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.app.NotificationManager
import com.google.firebase.auth.FirebaseAuth
import java.util.Calendar

class LendActionReceiver : BroadcastReceiver() {
    private val repository = FirestoreRepository()

    override fun onReceive(context: Context, intent: Intent) {
        val lendId = intent.getStringExtra("lend_id") ?: return
        val action = intent.action
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Dismiss the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(lendId.hashCode())

        when (action) {
            ACTION_MARK_RETURNED -> {
                Log.d("LendAction", "Marking $lendId as returned")
                repository.updateLendStatus(uid, lendId, true)
                ReminderManager.cancelReminder(context, lendId)
            }
            ACTION_REMIND_TOMORROW -> {
                Log.d("LendAction", "Postponing $lendId to tomorrow")
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                val newDate = calendar.timeInMillis
                
                repository.updateLendReturnDate(uid, lendId, newDate) {
                    // Fetch lend details to reschedule alarm
                    repository.getLendById(uid, lendId) { lend ->
                        if (lend != null) {
                            ReminderManager.scheduleReminder(
                                context,
                                lend.id!!,
                                lend.name,
                                lend.amount,
                                newDate
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_MARK_RETURNED = "com.tech.spendwise.ACTION_MARK_RETURNED"
        const val ACTION_REMIND_TOMORROW = "com.tech.spendwise.ACTION_REMIND_TOMORROW"
    }
}
