package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

open class BaseSettingsStubFragment(private val title: String) : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return TextView(requireContext()).apply {
            text = "$title Screen - Coming Soon"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
        }
    }
}

class CurrencyRegionFragment : BaseSettingsStubFragment("Currency & Region")
class AppearanceFragment : BaseSettingsStubFragment("Appearance")
class BankSendersFragment : BaseSettingsStubFragment("Manage Bank Senders")
class BackupRestoreFragment : BaseSettingsStubFragment("Backup & Restore")
