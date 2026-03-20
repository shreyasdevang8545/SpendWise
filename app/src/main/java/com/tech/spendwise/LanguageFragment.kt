package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentLanguageBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LanguageFragment : Fragment() {

    private var _binding: FragmentLanguageBinding? = null
    private val binding get() = _binding!!
    private lateinit var settingsManager: SettingsManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLanguageBinding.inflate(inflater, container, false)
        settingsManager = SettingsManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadCurrentLanguage()
    }

    private fun setupUI() {
        binding.languageToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnApply.setOnClickListener {
            val selectedLang = when (binding.languageRadioGroup.checkedRadioButtonId) {
                R.id.radioHindi -> "hi"
                R.id.radioKannada -> "kn"
                else -> "en"
            }
            applyLanguage(selectedLang)
        }
    }

    private fun loadCurrentLanguage() {
        lifecycleScope.launch {
            val lang = settingsManager.language.first()
            when (lang) {
                "hi" -> binding.radioHindi.isChecked = true
                "kn" -> binding.radioKannada.isChecked = true
                else -> binding.radioEnglish.isChecked = true
            }
        }
    }

    private fun applyLanguage(langCode: String) {
        lifecycleScope.launch {
            settingsManager.setString(SettingsManager.LANGUAGE, langCode)
            
            // Apply locale using AppCompatDelegate
            val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(langCode)
            AppCompatDelegate.setApplicationLocales(appLocale)
            
            // AppCompatDelegate handles activity recreation automatically
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
