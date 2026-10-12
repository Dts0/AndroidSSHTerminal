package com.sshtool.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sshtool.SSHToolApp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    private var batteryDialog: androidx.appcompat.app.AlertDialog? = null
    private var renderingKeepAlive = false
    private val keepAliveController get() = SSHToolApp.instance.keepAliveController
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        renderKeepAlive()
        if (_binding != null && keepAliveController.state.value.enabled) showBatteryGuidance()
    }

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
        renderKeepAlive()
        binding.switchKeepAlive.setOnCheckedChangeListener { _, enabled ->
            if (!renderingKeepAlive) {
                keepAliveController.setEnabled(enabled)
                if (enabled) requestNotificationAndExplainBattery()
            }
        }
        binding.batterySettingsRow.setOnClickListener { showBatteryGuidance() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                keepAliveController.state.collect { renderKeepAlive() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Read the same source as Android's app-language settings; no duplicate preference.
        renderLanguage()
        renderKeepAlive()
    }

    private fun renderKeepAlive() {
        val b = _binding ?: return
        val context = requireContext()
        val state = keepAliveController.state.value
        renderingKeepAlive = true
        b.switchKeepAlive.isChecked = state.enabled
        renderingKeepAlive = false
        b.tvKeepAliveStatus.text = when {
            !state.enabled -> getString(R.string.keep_alive_off)
            state.startFailed || (state.sessionCount > 0 && !state.serviceRequested) ->
                getString(R.string.keep_alive_start_failed)
            !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
                getString(R.string.keep_alive_notification_denied)
            state.sessionCount > 0 -> getString(R.string.keep_alive_active, state.sessionCount)
            else -> getString(R.string.keep_alive_waiting)
        }
        val exempt = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        b.tvBatteryStatus.setText(
            if (exempt) R.string.settings_battery_exempt else R.string.settings_battery_not_exempt
        )
    }

    private fun requestNotificationAndExplainBattery() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showBatteryGuidance()
        }
    }

    private fun showBatteryGuidance() {
        if (batteryDialog != null) return
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_battery)
            .setMessage(R.string.keep_alive_battery_guidance)
            .setPositiveButton(R.string.settings_open_system) { _, _ -> openSystemSettings() }
            .setNegativeButton(R.string.settings_later, null)
            .create()
        batteryDialog = dialog
        dialog.setOnDismissListener {
            if (batteryDialog === dialog) batteryDialog = null
        }
        dialog.show()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            })
        } catch (_: ActivityNotFoundException) {
            openGeneralSettings()
        } catch (_: SecurityException) {
            openGeneralSettings()
        }
    }

    private fun openGeneralSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.settings_system_unavailable, Toast.LENGTH_LONG).show()
        }
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
        batteryDialog?.dismiss()
        batteryDialog = null
        languageDialog?.dismiss()
        languageDialog = null
        _binding = null
        super.onDestroyView()
    }
}
