package com.sshtool.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sshtool.R
import com.sshtool.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var languageDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.languageRow.setOnClickListener { showLanguagePicker() }
        renderLanguage()
    }

    override fun onResume() {
        super.onResume()
        // Read the same source as Android's app-language settings; no duplicate preference.
        renderLanguage()
    }

    private fun currentLanguage() = AppLanguage.fromLanguageTags(
        AppCompatDelegate.getApplicationLocales().toLanguageTags()
    )

    private fun renderLanguage() {
        _binding?.tvLanguageValue?.setText(currentLanguage().labelRes)
    }

    private fun showLanguagePicker() {
        if (languageDialog != null) return
        val languages = AppLanguage.values()
        val selected = currentLanguage()
        val labels = languages.map { getString(it.labelRes) }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, languages.indexOf(selected)) { picker, index ->
                picker.dismiss()
                val language = languages[index]
                val locales = LocaleListCompat.forLanguageTags(language.languageTag)
                if (AppCompatDelegate.getApplicationLocales() != locales) {
                    // AppCompat may recreate the Activity. Its ViewModels and navigation
                    // state survive; never explicitly shut down an SSH session here.
                    AppCompatDelegate.setApplicationLocales(locales)
                }
                renderLanguage()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        languageDialog = dialog
        dialog.setOnDismissListener {
            if (languageDialog === dialog) languageDialog = null
        }
        dialog.show()
    }

    override fun onDestroyView() {
        languageDialog?.dismiss()
        languageDialog = null
        _binding = null
        super.onDestroyView()
    }
}
