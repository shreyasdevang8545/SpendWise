package com.tech.spendwise

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class SettingsManager(private val context: Context) {

    companion object {
        val SMS_AUTO_SCAN = booleanPreferencesKey("sms_auto_scan")
        val RCS_SUPPORT = booleanPreferencesKey("rcs_support")
        val AUTO_CATEGORISE = booleanPreferencesKey("auto_categorise")
        val CONFIRM_BEFORE_SAVING = booleanPreferencesKey("confirm_before_saving")
        val TRANSACTION_ALERTS = booleanPreferencesKey("transaction_alerts")
        val MONTHLY_SUMMARY = booleanPreferencesKey("monthly_summary")
        val DAILY_REMINDER = booleanPreferencesKey("daily_reminder")
        val DAILY_REMINDER_HOUR = intPreferencesKey("daily_reminder_hour")
        val DAILY_REMINDER_MINUTE = intPreferencesKey("daily_reminder_minute")
        
        val CURRENCY = stringPreferencesKey("currency")
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN = stringPreferencesKey("app_lock_pin")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        
        // Budget Alerts
        val OVERALL_BUDGET_LIMIT = floatPreferencesKey("overall_budget_limit")
        val CATEGORY_BUDGET_LIMITS = stringPreferencesKey("category_budget_limits") // stored as JSON
    }

    val smsAutoScan: Flow<Boolean> = context.dataStore.data.map { it[SMS_AUTO_SCAN] ?: true }
    val rcsSupport: Flow<Boolean> = context.dataStore.data.map { it[RCS_SUPPORT] ?: false }
    val autoCategorise: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CATEGORISE] ?: true }
    val confirmBeforeSaving: Flow<Boolean> = context.dataStore.data.map { it[CONFIRM_BEFORE_SAVING] ?: true }
    val transactionAlerts: Flow<Boolean> = context.dataStore.data.map { it[TRANSACTION_ALERTS] ?: true }
    val monthlySummary: Flow<Boolean> = context.dataStore.data.map { it[MONTHLY_SUMMARY] ?: true }
    val dailyReminder: Flow<Boolean> = context.dataStore.data.map { it[DAILY_REMINDER] ?: false }
    val dailyReminderHour: Flow<Int> = context.dataStore.data.map { it[DAILY_REMINDER_HOUR] ?: 20 } // Default 8 PM
    val dailyReminderMinute: Flow<Int> = context.dataStore.data.map { it[DAILY_REMINDER_MINUTE] ?: 0 }
    val language: Flow<String> = context.dataStore.data.map { it[LANGUAGE] ?: "en" }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[APP_LOCK_ENABLED] ?: false }
    val appLockPin: Flow<String> = context.dataStore.data.map { it[APP_LOCK_PIN] ?: "" }
    val biometricEnabled: Flow<Boolean> = context.dataStore.data.map { it[BIOMETRIC_ENABLED] ?: false }

    val overallBudgetLimit: Flow<Float> = context.dataStore.data.map { it[OVERALL_BUDGET_LIMIT] ?: 0f }
    val categoryBudgetLimits: Flow<String> = context.dataStore.data.map { it[CATEGORY_BUDGET_LIMITS] ?: "{}" }

    suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setFloat(key: Preferences.Key<Float>, value: Float) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    /**
     * Clears all settings data.
     */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
