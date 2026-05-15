package com.tech.spendwise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)
            
            scope.launch {
                try {
                    // 1. Restore Session
                    SupabaseInstance.restoreSession(context)
                    
                    val settingsManager = SettingsManager(context)
                    val repository = SupabaseRepository()
                    
                    // 2. Reschedule Daily Reminder
                    val dailyEnabled = settingsManager.dailyReminder.first()
                    if (dailyEnabled) {
                        val hour = settingsManager.dailyReminderHour.first()
                        val minute = settingsManager.dailyReminderMinute.first()
                        ReminderManager.scheduleDailyReminder(context, hour, minute)
                    }
                    
                    // 3. Reschedule Lend Reminders
                    val uid = SupabaseInstance.currentUserId()
                    if (uid != null) {
                        val activeLends = repository.fetchLends().filter { !it.isReturned }
                        activeLends.forEach { lend ->
                            if (lend.id != null && lend.returnDate > System.currentTimeMillis()) {
                                ReminderManager.scheduleReminder(
                                    context,
                                    lend.id,
                                    lend.name,
                                    lend.amount,
                                    lend.returnDate
                                )
                            }
                        }

                        // 4. Reschedule Credit Card Payback Reminders
                        val cards = repository.fetchCreditCards()
                        cards.forEach { card ->
                            if (card.reminderEnabled && card.id != null) {
                                ReminderManager.scheduleCreditCardPayback(
                                    context,
                                    card.id,
                                    card.name,
                                    card.paybackDay
                                )
                            }
                        }
                        // 5. Reschedule Recurring Transactions
                        val transactions = repository.fetchRecentTransactions()
                        ReminderManager.rescheduleAllRecurringTransactions(context, transactions)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
