package com.tech.spendwise

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import org.json.JSONObject

object BudgetNotificationHelper {

    private const val CHANNEL_ID = "budget_alerts_channel_v2"
    private const val NOTIFICATION_ID_BASE = 5000

    suspend fun checkBudgetsAndNotify(context: Context, newTransactionJson: String) {
        try {
            val settingsManager = SettingsManager(context)
            val overallLimit = settingsManager.overallBudgetLimit.first()
            val categoryLimitsStr = settingsManager.categoryBudgetLimits.first()
            
            // If no limits are set, we can return early
            if (overallLimit <= 0f && (categoryLimitsStr.isEmpty() || categoryLimitsStr == "{}")) {
                return
            }

            // Parse the incoming new transaction to get its amount & category
            val newTxn = JSONObject(newTransactionJson)
            val newAmount = newTxn.optDouble("amount", 0.0)
            val newCategory = newTxn.optString("category", "Others")
            
            // Calculate current month's totals from the repository
            val repo = PreferenceRepository(context)
            val txList = repo.confirmedTransactionsFlow.first()

            // Calculate totals (including the new transaction we just received)
            var totalSpend = newAmount
            var categorySpend = newAmount

            for (txStr in txList) {
                if (txStr.isBlank()) continue
                try {
                    val tx = JSONObject(txStr)
                    // Basic safeguard: only count expenses, not income
                    val type = tx.optString("type", "Expense")
                    if (type.equals("Income", ignoreCase = true)) continue

                    // In a real app we'd filter by current month/year.
                    // For simplicity in this demo, we'll sum all existing transactions.
                    // (Assuming User clears data per month or we add date parsing later)
                    val amt = tx.optDouble("amount", 0.0)
                    totalSpend += amt
                    
                    if (tx.optString("category", "Others") == newCategory) {
                        categorySpend += amt
                    }
                } catch (e: Exception) {
                    // Ignore malformed transactions
                }
            }

            // 1. Check Overall Budget
            if (overallLimit > 0f && totalSpend > overallLimit) {
                // Check if it just crossed the limit with this transaction to avoid spam
                val previousSpend = totalSpend - newAmount
                if (previousSpend <= overallLimit) {
                    sendNotification(
                        context, 
                        "Overall Budget Exceeded", 
                        "You have exceeded your overall budget of ₹${overallLimit.toInt()}! Total spent: ₹${totalSpend.toInt()}.",
                        NOTIFICATION_ID_BASE + 1
                    )
                }
            }

            // 2. Check Category Budget
            if (categoryLimitsStr.isNotEmpty() && categoryLimitsStr != "{}") {
                val catLimitsJson = JSONObject(categoryLimitsStr)
                if (catLimitsJson.has(newCategory)) {
                    val catLimit = catLimitsJson.getDouble(newCategory)
                    if (catLimit > 0 && categorySpend > catLimit) {
                        val previousCatSpend = categorySpend - newAmount
                        if (previousCatSpend <= catLimit) {
                            sendNotification(
                                context,
                                "$newCategory Budget Exceeded",
                                "You have exceeded your budget for $newCategory (₹${catLimit.toInt()}). Total spent: ₹${categorySpend.toInt()}.",
                                NOTIFICATION_ID_BASE + newCategory.hashCode()
                            )
                        }
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendNotification(context: Context, title: String, message: String, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val soundUri = android.net.Uri.parse(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + R.raw.google_notification)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()
                
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Budget Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when you exceed your set budget limits."
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_account_balance) // Use consistent financial icon
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
