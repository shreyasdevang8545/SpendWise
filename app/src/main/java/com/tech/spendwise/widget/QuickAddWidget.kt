package com.tech.spendwise.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.currentState
import com.tech.spendwise.R
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import com.tech.spendwise.sync.SyncWorker

class QuickAddWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val amount = prefs[AmountKey] ?: ""
            val category = prefs[CategoryKey] ?: "Others"
            
            WidgetContent(context, amount, category)
        }
    }

    @Composable
    private fun WidgetContent(context: Context, amount: String, category: String) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(R.color.white))
                .padding(8.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            verticalAlignment = Alignment.Vertical.Top
        ) {
            Text(
                text = if (amount.isEmpty()) "₹ 0.00" else "₹ $amount",
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(R.color.text_primary)
                )
            )
            
            Spacer(modifier = GlanceModifier.height(4.dp))
            
            Text(
                text = "Category: $category",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(R.color.text_secondary)
                )
            )

            Spacer(modifier = GlanceModifier.height(4.dp))

            // Simplified Numeric Pad
            NumericPad()
            
            Spacer(modifier = GlanceModifier.height(4.dp))
            
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Button(
                    text = "Clear",
                    onClick = actionRunCallback<ClearAction>(),
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                Button(
                    text = "Add",
                    onClick = actionRunCallback<AddAction>(),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }
    }

    @Composable
    private fun NumericPad() {
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", "0", "⌫")
        )
        
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            keys.forEach { row ->
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    row.forEach { key ->
                        Button(
                            text = key,
                            onClick = actionRunCallback<KeyAction>(actionParametersOf(KeyParam to key)),
                            modifier = GlanceModifier.defaultWeight().padding(1.dp)
                        )
                    }
                }
            }
        }
    }

    companion object {
        val AmountKey = stringPreferencesKey("widget_amount")
        val CategoryKey = stringPreferencesKey("widget_category")
        val KeyParam = ActionParameters.Key<String>("key")
    }
}

class KeyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val key = parameters[QuickAddWidget.KeyParam] ?: return
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                val current = this[QuickAddWidget.AmountKey] ?: ""
                this[QuickAddWidget.AmountKey] = when (key) {
                    "⌫" -> if (current.isNotEmpty()) current.dropLast(1) else ""
                    "." -> if (!current.contains(".")) "$current." else current
                    else -> current + key
                }
            }
        }
        QuickAddWidget().update(context, glanceId)
    }
}

class ClearAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                this.remove(QuickAddWidget.AmountKey)
            }
        }
        QuickAddWidget().update(context, glanceId)
    }
}

class AddAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, glanceId)
        val amount = prefs[QuickAddWidget.AmountKey] ?: ""
        val category = prefs[QuickAddWidget.CategoryKey] ?: "Others"
        
        if (amount.isNotEmpty()) {
            val workData = Data.Builder()
                .putString("amount", amount)
                .putString("category", category)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workData)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            
            // Clear the widget
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { p ->
                p.toMutablePreferences().apply {
                    this.remove(QuickAddWidget.AmountKey)
                }
            }
            QuickAddWidget().update(context, glanceId)
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}
