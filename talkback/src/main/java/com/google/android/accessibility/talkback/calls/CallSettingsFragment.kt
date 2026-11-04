package com.google.android.accessibility.talkback.calls

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notificationlist.NotificationListService
import com.google.android.accessibility.talkback.notifications.NotificationXmlFragment
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.VoiceProfiles

class CallSettingsFragment : TalkbackBaseFragment(R.xml.call_preferences) {
  override fun getTitle(): CharSequence = getText(R.string.call_settings_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
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
