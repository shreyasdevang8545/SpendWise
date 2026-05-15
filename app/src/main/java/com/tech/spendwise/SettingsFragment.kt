package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.tech.spendwise.databinding.FragmentSettingsBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.tech.spendwise.R
import java.util.Locale
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tech.spendwise.utils.UIUtils
import android.content.DialogInterface
import kotlinx.coroutines.flow.first
import android.widget.Toast
import com.tech.spendwise.TransactionViewModel
import org.json.JSONObject

import androidx.activity.result.contract.ActivityResultContracts

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager
    private val viewModel: TransactionViewModel by activityViewModels()

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { saveCsvToUri(it) }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                binding.itemDailyReminder.rowSwitch.isChecked = false
                binding.itemTransactionAlerts.rowSwitch.isChecked = false
                binding.itemMonthlySummary.rowSwitch.isChecked = false
                UIUtils.showErrorSnackbar(binding.root, "Notification permission is required for this feature.")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        settingsManager = SettingsManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        populatePremiumSection()
        populateGeneralSection()
        populateSmsSection()
        populateNotificationsSection()
        populateMonthlyReportSection()
        populateDataPrivacySection()
        populateSupportSection()
        setupClickListeners()
    }

    private fun populatePremiumSection() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                settingsManager.isProUser,
                settingsManager.proPlanType,
                settingsManager.proExpiryDate
            ) { isPro, planType, expiryDate ->
                Triple(isPro, planType, expiryDate)
            }.collect { (isPro, planType, expiryDate) ->
                _binding?.itemPremium?.apply {
                    if (isPro) {
                        rowTitle.text = "SpendWise Pro ($planType)"
                        val daysLeft = ((expiryDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).coerceAtLeast(0)
                        rowSubtitle.text = "Active • $daysLeft days left"
                    } else {
                        rowTitle.text = "Upgrade to Pro"
                        rowSubtitle.text = "Unlock all premium features"
                    }
                    rowIcon.setImageResource(R.drawable.ic_star)
                    rowIcon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFD700.toInt()) // Gold
                    rowBadge.visibility = if (isPro) View.VISIBLE else View.GONE
                    rowBadge.text = "PRO"
                    rowBadge.setTextColor(android.graphics.Color.parseColor("#121212"))
                    rowBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary_green)
                    )
                    root.setOnClickListener { findNavController().navigate(R.id.premiumFragment) }
                }
            }
        }
    }


    private fun populateGeneralSection() {
        binding.itemProfile.apply {
            rowTitle.text = getString(R.string.title_profile)
            rowSubtitle.text = getString(R.string.subtitle_profile)
            rowIcon.setImageResource(R.drawable.ic_person)
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_profile) }
        }
/*
        binding.itemCurrency.apply {
            rowTitle.text = getString(R.string.title_currency)
            rowSubtitle.text = getString(R.string.subtitle_currency)
            rowIcon.setImageResource(R.drawable.ic_currency_exchange)
            rowBadge.visibility = View.VISIBLE
            rowBadge.text = getString(R.string.badge_inr)
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_currency) }
        }
        binding.itemAppearance.apply {
            rowTitle.text = getString(R.string.title_appearance)
            rowSubtitle.text = getString(R.string.subtitle_appearance)
            rowIcon.setImageResource(R.drawable.ic_light_mode)
            rowBadge.visibility = View.VISIBLE
            rowBadge.text = getString(R.string.badge_light)
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_appearance) }
        }
*/
        binding.itemLanguage.apply {
            rowTitle.text = getString(R.string.title_language)
            rowSubtitle.text = getString(R.string.subtitle_language)
            rowIcon.setImageResource(R.drawable.ic_language)
            rowBadge.visibility = View.VISIBLE
            lifecycleScope.launch {
                val currentLang = settingsManager.language.first()
                rowBadge.text = when(currentLang) {
                    "hi" -> "हिंदी"
                    "kn" -> "ಕನ್ನಡ"
                    else -> "English"
                }
            }
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_language) }
        }
    }

    private fun populateSmsSection() {
        lifecycleScope.launch {
            binding.itemSmsAutoScan.apply {
                rowTitle.text = getString(R.string.title_sms_auto_scan)
                rowSubtitle.text = getString(R.string.subtitle_sms_auto_scan)
                rowIcon.setImageResource(R.drawable.ic_sms)
                rowSwitch.isChecked = settingsManager.smsAutoScan.first()
                rowSwitch.setOnCheckedChangeListener { _, isChecked ->
                    lifecycleScope.launch { settingsManager.setBoolean(SettingsManager.SMS_AUTO_SCAN, isChecked) }
                }
            }
        }
    }

    private fun populateMonthlyReportSection() {
        binding.itemMonthlyReport.apply {
            rowTitle.text = getString(R.string.title_monthly_report)
            rowSubtitle.text = getString(R.string.subtitle_monthly_report)
            rowIcon.setImageResource(R.drawable.ic_history)
            
            rowBadge.visibility = View.VISIBLE
            rowBadge.text = "PRO"
            rowBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary_green)
            )

            root.setOnClickListener { 
                viewLifecycleOwner.lifecycleScope.launch {
                    val isPro = settingsManager.isProUser.first()
                    if (isPro) {
                        findNavController().navigate(R.id.action_settings_to_monthlyReport)
                    } else {
                        Toast.makeText(requireContext(), "Pro version required for Monthly Reports", Toast.LENGTH_SHORT).show()
                        findNavController().navigate(R.id.premiumFragment)
                    }
                }
            }
        }
    }

    private fun populateNotificationsSection() {
        lifecycleScope.launch {
            // Transaction Alerts
            binding.itemTransactionAlerts.apply {
                rowTitle.text = getString(R.string.title_transaction_alerts)
                rowSubtitle.text = getString(R.string.subtitle_transaction_alerts)
                rowIcon.setImageResource(R.drawable.ic_notifications)
                rowSwitch.isChecked = settingsManager.transactionAlerts.first()
                rowSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && !UIUtils.isNotificationPermissionGranted(requireContext())) {
                        rowSwitch.isChecked = false
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnCheckedChangeListener
                    }
                    if (isChecked && !UIUtils.areNotificationsEnabled(requireContext())) {
                        rowSwitch.isChecked = false
                        UIUtils.showActionSnackbar(binding.root, "Notifications are disabled in system settings.", "Settings") {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                            }
                            startActivity(intent)
                        }
                        return@setOnCheckedChangeListener
                    }
                    lifecycleScope.launch { settingsManager.setBoolean(SettingsManager.TRANSACTION_ALERTS, isChecked) }
                }
                root.setOnClickListener { rowSwitch.toggle() }
            }

            // Monthly Summary
            binding.itemMonthlySummary.apply {
                rowTitle.text = getString(R.string.title_monthly_summary)
                rowSubtitle.text = getString(R.string.subtitle_monthly_summary)
                rowIcon.setImageResource(R.drawable.ic_calendar_today)
                rowSwitch.isChecked = settingsManager.monthlySummary.first()
                rowSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && !UIUtils.isNotificationPermissionGranted(requireContext())) {
                        rowSwitch.isChecked = false
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnCheckedChangeListener
                    }
                    lifecycleScope.launch { settingsManager.setBoolean(SettingsManager.MONTHLY_SUMMARY, isChecked) }
                }
                root.setOnClickListener { rowSwitch.toggle() }
            }

            // Daily Reminder
            binding.itemDailyReminder.apply {
                rowTitle.text = getString(R.string.title_daily_reminder)
                rowIcon.setImageResource(R.drawable.ic_schedule)
                
                val isEnabled = settingsManager.dailyReminder.first()
                rowSwitch.isChecked = isEnabled
                
                if (isEnabled) {
                    val hour = settingsManager.dailyReminderHour.first()
                    val minute = settingsManager.dailyReminderMinute.first()
                    rowSubtitle.text = getString(R.string.reminder_scheduled_at, hour, minute)
                } else {
                    rowSubtitle.text = getString(R.string.subtitle_daily_reminder)
                }

                rowSwitch.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!UIUtils.isNotificationPermissionGranted(requireContext())) {
                            rowSwitch.isChecked = false
                            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else if (!UIUtils.areNotificationsEnabled(requireContext())) {
                            rowSwitch.isChecked = false
                            UIUtils.showActionSnackbar(binding.root, "Notifications are disabled in system settings.", "Settings") {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                                }
                                startActivity(intent)
                            }
                        } else {
                            showTimePicker()
                        }
                    } else {
                        lifecycleScope.launch {
                            settingsManager.setBoolean(SettingsManager.DAILY_REMINDER, false)
                            ReminderManager.cancelDailyReminder(requireContext())
                            rowSubtitle.text = getString(R.string.subtitle_daily_reminder)
                        }
                    }
                }
                
                root.setOnClickListener { rowSwitch.toggle() }
            }
        }
    }

    private fun showTimePicker() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(18)
            .setMinute(0)
            .setTitleText(getString(R.string.set_reminder_time))
            .build()

        picker.addOnPositiveButtonClickListener {
            lifecycleScope.launch {
                settingsManager.setBoolean(SettingsManager.DAILY_REMINDER, true)
                settingsManager.setInt(SettingsManager.DAILY_REMINDER_HOUR, picker.hour)
                settingsManager.setInt(SettingsManager.DAILY_REMINDER_MINUTE, picker.minute)
                
                ReminderManager.scheduleDailyReminder(requireContext(), picker.hour, picker.minute)
                
                binding.itemDailyReminder.rowSubtitle.text = 
                    getString(R.string.reminder_scheduled_at, picker.hour, picker.minute)
            }
        }

        picker.addOnCancelListener {
            binding.itemDailyReminder.rowSwitch.isChecked = false
        }

        picker.addOnNegativeButtonClickListener {
            binding.itemDailyReminder.rowSwitch.isChecked = false
        }

        picker.show(childFragmentManager, "time_picker")
    }

    private fun populateDataPrivacySection() {
        binding.itemBackupRestore.apply {
            rowTitle.text = getString(R.string.title_backup_restore)
            rowSubtitle.text = getString(R.string.subtitle_backup_restore)
            rowIcon.setImageResource(R.drawable.ic_cloud_upload)
            rowBadge.visibility = View.VISIBLE
            rowBadge.text = "PRO"
            rowBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary_green)
            )
            root.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    val isPro = settingsManager.isProUser.first()
                    if (isPro) {
                        exportLauncher.launch("SpendWise_Transactions_${System.currentTimeMillis()}.csv")
                    } else {
                        Toast.makeText(requireContext(), "Pro version required for export", Toast.LENGTH_SHORT).show()
                        findNavController().navigate(R.id.premiumFragment)
                    }
                }
            }
        }
        binding.itemSecurityLock.apply {
            rowTitle.text = getString(R.string.title_security_lock)
            rowSubtitle.text = getString(R.string.subtitle_security_lock)
            rowIcon.setImageResource(R.drawable.ic_lock)
            rowBadge.visibility = View.VISIBLE
            lifecycleScope.launch {
                val lockEnabled = settingsManager.appLockEnabled.first()
                if (lockEnabled) {
                    rowBadge.text = getString(R.string.badge_on)
                    rowBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(0x334CAF50.toInt())
                } else {
                    rowBadge.text = getString(R.string.badge_off)
                    rowBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(0x33F44336.toInt())
                }
            }
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_securityLock) }
        }
        binding.itemClearAllData.apply {
            rowTitle.text = getString(R.string.title_clear_all_data)
            rowSubtitle.text = getString(R.string.subtitle_clear_all_data)
            rowIcon.setImageResource(R.drawable.ic_delete_forever)
            rowTitle.setTextColor(resources.getColor(R.color.error_red, null))
            root.setOnClickListener { showClearAllDataDialog() }
        }
    }

    private fun showClearAllDataDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_clear_all_title)
            .setMessage(R.string.dialog_clear_all_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.action_clear_everything) { _: DialogInterface, _: Int ->
                performClearAll()
            }
            .show()
    }

    private fun performClearAll() {
        viewModel.clearEverything {
            view?.let {
                UIUtils.showSuccessSnackbar(it, getString(R.string.msg_data_cleared))
            }
        }
    }

    private fun populateSupportSection() {
        binding.itemAbout.apply {
            rowTitle.text = getString(R.string.title_about)
            rowSubtitle.text = getString(R.string.subtitle_about)
            rowIcon.setImageResource(R.drawable.ic_info)
            rowBadge.visibility = View.VISIBLE
            
            try {
                val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                rowBadge.text = "v${pInfo.versionName}"
            } catch (e: Exception) {
                rowBadge.text = "v1.0.0"
            }
            
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_about) }
        }
        binding.itemFeedback.apply {
            rowTitle.text = getString(R.string.title_feedback)
            rowSubtitle.text = getString(R.string.subtitle_feedback)
            rowIcon.setImageResource(R.drawable.ic_chat)
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_feedback) }
        }
        binding.itemPrivacyPolicy.apply {
            rowTitle.text = getString(R.string.title_privacy_policy)
            rowSubtitle.text = getString(R.string.subtitle_privacy_policy)
            rowIcon.setImageResource(R.drawable.ic_policy)
            root.setOnClickListener { findNavController().navigate(R.id.action_settings_to_privacyPolicy) }
        }
        binding.itemRate.apply {
            rowTitle.text = getString(R.string.title_rate)
            rowSubtitle.text = getString(R.string.subtitle_rate)
            rowIcon.setImageResource(R.drawable.ic_star)
        }
    }

    private fun setupClickListeners() {
        binding.settingsToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun saveCsvToUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            val transactionsJson = viewModel.allTransactions.value ?: emptyList()
            if (transactionsJson.isEmpty()) {
                Toast.makeText(requireContext(), "No transactions to export", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val csvHeader = "Date,Amount,Currency,Type,Category,Merchant,Note\n"
            val csvRows = transactionsJson.mapNotNull { json ->
                try {
                    val obj = org.json.JSONObject(json)
                    val date = obj.optString("saved_at", "").replace(",", "")
                    val amount = obj.optDouble("amount", 0.0)
                    val currency = obj.optString("currency", "INR")
                    val type = obj.optString("type", "DEBIT")
                    val category = obj.optString("category", "Others").replace(",", " ")
                    val merchant = obj.optString("merchant", "UNKNOWN").replace(",", " ")
                    val note = obj.optString("raw_sms", "").replace(",", " ").replace("\n", " ")
                    
                    "$date,$amount,$currency,$type,$category,$merchant,$note"
                } catch (e: Exception) { null }
            }.joinToString("\n")

            val csvContent = csvHeader + csvRows

            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(csvContent.toByteArray())
                }
                Toast.makeText(requireContext(), "Transactions exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
