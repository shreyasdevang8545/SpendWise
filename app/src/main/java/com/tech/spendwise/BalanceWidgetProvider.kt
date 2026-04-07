package com.tech.spendwise

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_balance)
        
        // Immediate click listener
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        // Load data in background
        val repository = PreferenceRepository(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val balance = repository.widgetBalance.first()
                val income = repository.widgetIncome.first()
                val spent = repository.widgetSpent.first()
                val month = repository.widgetMonth.first()

                val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
                currencyFormatter.maximumFractionDigits = 0

                // Premium UI Population
                views.setTextViewText(R.id.widgetMonthText, month.ifEmpty { "Summary" })
                views.setTextViewText(R.id.widgetSpentText, currencyFormatter.format(spent))
                views.setTextViewText(R.id.widgetIncomeText, "+${currencyFormatter.format(income)} Income")
                views.setTextViewText(R.id.widgetBalanceText, "${currencyFormatter.format(balance)} Bal")

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                // Ignore or log
            }
        }
    }
}
