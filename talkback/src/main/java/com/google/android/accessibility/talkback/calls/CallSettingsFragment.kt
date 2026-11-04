package com.google.android.accessibility.talkback.calls

import android.Manifest
import android.app.TimePickerDialog
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notificationlist.NotificationListService
import com.google.android.accessibility.talkback.notifications.NotificationXmlFragment
import com.google.android.accessibility.talkback.permission.PermissionUtils
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.VoiceProfiles

class CallSettingsFragment : TalkbackBaseFragment(R.xml.call_preferences) {
  private var pendingLimitEnable = false
  private val limitListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key in CallLimitSettings.KEYS) updateLimitNotice()
  }

  override fun getTitle(): CharSequence = getText(R.string.call_settings_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    pendingLimitEnable = savedInstanceState?.getBoolean("pendingCallLimitEnable") == true
    setUpCallLimit()
    findPreference<ListPreference>(CallSettings.REPEATS)?.let { preference ->
      preference.entries = (0..10).map { getString(R.string.call_repeat_count, it) }.toTypedArray()
      preference.entryValues = (0..10).map { it.toString() }.toTypedArray()
    }
    findPreference<ListPreference>(CallSettings.INTERVAL)?.let { preference ->
      val seconds = listOf(0, 10, 15, 30, 60, 120, 300)
      preference.entries = seconds.map {
        if (it == 0) getString(R.string.call_interval_off) else getString(R.string.call_seconds, it)
      }.toTypedArray()
      preference.entryValues = seconds.map { it.toString() }.toTypedArray()
    }
    listOf(CallSettings.START to 23 * 60, CallSettings.END to 6 * 60).forEach { (key, default) ->
      findPreference<Preference>(key)?.let { preference ->
        fun current() = (prefs.all[key]?.toString()?.toIntOrNull() ?: default).coerceIn(0, 1439)
        preference.summary = NotificationXmlFragment.timeText(requireContext(), current())
        preference.setOnPreferenceClickListener {
          val minutes = current()
          TimePickerDialog(requireContext(), { _, hour, minute ->
            val value = hour * 60 + minute
            prefs.edit().putInt(key, value).apply()
            preference.summary = NotificationXmlFragment.timeText(requireContext(), value)
          }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(requireContext())).show()
          true
        }
      }
    }
  }

  private fun limitReady(): Boolean {
    val context = requireContext()
    val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
      PackageManager.PERMISSION_GRANTED
    return phone && (CallLimitController.hasManagedAccess(context) || CallLimitController.canUseLegacy(context))
  }

  private fun setUpCallLimit() {
    findPreference<EditTextPreference>(CallLimitSettings.MINUTES)?.let { preference ->
      preference.setSummaryProvider { item ->
        val minutes = (item as EditTextPreference).text?.toIntOrNull() ?: 10
        getString(R.string.call_limit_minutes_value, minutes)
      }
      preference.setOnBindEditTextListener {
        it.inputType = InputType.TYPE_CLASS_NUMBER
        it.selectAll()
      }
      preference.setOnPreferenceChangeListener { _, value ->
        val valid = value.toString().toIntOrNull()?.let { it in 1..1440 } == true
        if (!valid) Toast.makeText(requireContext(), R.string.call_limit_minutes_invalid, Toast.LENGTH_LONG).show()
        valid
      }
    }
    findPreference<TwoStatePreference>(CallLimitSettings.ENABLED)?.let { preference ->
      preference.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
      preference.setOnPreferenceChangeListener { _, value ->
        if (value != true || limitReady()) true
        else {
          val context = requireContext()
          val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
          if (!CallLimitController.hasManagedAccess(context)) permissions += Manifest.permission.ANSWER_PHONE_CALLS
          val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
          }
          pendingLimitEnable = true
          PermissionUtils.requestPermissions(context, *missing.toTypedArray())
          false
        }
      }
    }
    updateLimitNotice()
  }

  private fun updateLimitNotice() {
    val context = context ?: return
    val text = when {
      Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> R.string.call_limit_unsupported
      CallLimitController.hasManagedAccess(context) -> R.string.call_limit_managed_notice
      else -> R.string.call_limit_legacy_notice
    }
    findPreference<Preference>("pref_call_limit_notice")?.title = getString(text)
  }

  override fun onResume() {
    super.onResume()
    SharedPreferencesUtils.getSharedPreferences(requireContext())
      .registerOnSharedPreferenceChangeListener(limitListener)
    if (pendingLimitEnable) {
      pendingLimitEnable = false
      if (limitReady()) findPreference<TwoStatePreference>(CallLimitSettings.ENABLED)?.isChecked = true
      else Toast.makeText(requireContext(), R.string.call_limit_permission_missing, Toast.LENGTH_LONG).show()
    }
    updateLimitNotice()
  }

  override fun onPause() {
    SharedPreferencesUtils.getSharedPreferences(requireContext())
      .unregisterOnSharedPreferenceChangeListener(limitListener)
    super.onPause()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean("pendingCallLimitEnable", pendingLimitEnable)
  }

  override fun onStart() {
    super.onStart()
    NotificationListService.rebind(requireContext())
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    val names = VoiceProfiles.names(prefs, getString(R.string.voice_profile_default)) {
      getString(R.string.voice_profile_new_name, it)
    }
    findPreference<ListPreference>(CallSettings.VOICE)?.let {
      it.entries = (listOf(getString(R.string.call_voice_default)) + names.values).toTypedArray()
      it.entryValues = (listOf("") + names.keys).toTypedArray()
      if (it.findIndexOfValue(it.value.orEmpty()) < 0) it.value = ""
    }
  }
}
