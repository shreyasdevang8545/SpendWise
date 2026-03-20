package com.tech.spendwise

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val settingsManager = SettingsManager(context)
            GlobalScope.launch {
                val dailyEnabled = settingsManager.dailyReminder.first()
                if (dailyEnabled) {
                    val hour = settingsManager.dailyReminderHour.first()
                    val minute = settingsManager.dailyReminderMinute.first()
                    ReminderManager.scheduleDailyReminder(context, hour, minute)
                }
            }
        }
    }
}
