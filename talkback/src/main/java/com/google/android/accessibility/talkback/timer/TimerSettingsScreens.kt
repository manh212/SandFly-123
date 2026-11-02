/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.timer

import android.os.Bundle
import android.text.format.DateFormat
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import com.google.android.accessibility.talkback.notifications.SpokenSchedules
import com.google.android.accessibility.talkback.speech.VoiceProfileNames
import com.google.android.accessibility.utils.output.VoiceProfiles
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.NotificationCodeFragment
import com.google.android.accessibility.talkback.notifications.NotificationXmlFragment
import java.util.Date

/**
 * Timer settings on one screen: the time announcements, which keep their keys and values from
 * the notification schedules screen they were on, and the stopwatch below them.
 */
class TimerSettingsFragment :
  NotificationXmlFragment(R.xml.timer_preferences, R.string.title_pref_clock_stopwatch) {

  override fun onCreate(savedInstanceState: Bundle?) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    if (!prefs.contains(Stopwatch.KEY_INTERVAL_SLIDER)) {
      prefs.edit().putInt(Stopwatch.KEY_INTERVAL_SLIDER, Stopwatch.intervalMinutes(requireContext())).apply()
    }
    super.onCreate(savedInstanceState)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    findPreference<ListPreference>(TimerSpeechSettings.KEY_PROFILE)?.let { voiceChoice ->
      val ids = VoiceProfiles.ids(prefs)
      voiceChoice.entries =
        (listOf(getString(R.string.timer_voice_default)) +
          ids.map { VoiceProfileNames.nameOf(requireContext(), prefs, it) }).toTypedArray()
      voiceChoice.entryValues = (listOf("") + ids).toTypedArray()
      voiceChoice.value = TimerSpeechSettings.profileId(prefs)
      voiceChoice.summary = voiceChoice.entry ?: getString(R.string.timer_voice_default)
      voiceChoice.setOnPreferenceChangeListener { preference, value ->
        val id = value as String
        preference.summary =
          if (id.isEmpty()) getString(R.string.timer_voice_default)
          else VoiceProfileNames.nameOf(requireContext(), prefs, id)
        true
      }
    }
    findPreference<Preference>("pref_notification_clock_hours_screen")?.let { hours ->
      fun updateSummary() {
        val selected = SpokenSchedules.selectedHours(prefs)
        hours.summary =
          when (selected.size) {
            0 -> getString(R.string.timer_clock_hours_off)
            24 -> getString(R.string.timer_clock_hours_all)
            else -> getString(R.string.timer_clock_hours_count, selected.size)
          }
      }
      updateSummary()
      hours.setOnPreferenceClickListener {
        val labels = (0..23).map { getString(R.string.timer_clock_hour_label, it) }.toTypedArray()
        val selected =
          if (prefs.contains(SpokenSchedules.KEY_CLOCK_HOURS))
            SpokenSchedules.selectedHours(prefs)
          else SpokenSchedules.ALL_CLOCK_HOURS
        val checked = BooleanArray(24) { it in selected }
        AlertDialog.Builder(requireContext())
          .setTitle(R.string.timer_clock_hours_title)
          .setMultiChoiceItems(labels, checked) { _, position, isChecked ->
            checked[position] = isChecked
          }
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(android.R.string.ok) { _, _ ->
            prefs.edit().putStringSet(
              SpokenSchedules.KEY_CLOCK_HOURS,
              (0..23).filter { checked[it] }.map { it.toString() }.toSet()
            ).apply()
            updateSummary()
          }
          .show()
        true
      }
    }
    findPreference<SeekBarPreference>(Stopwatch.KEY_INTERVAL_SLIDER)?.let { interval ->
      fun updateSummary(minutes: Int) {
        interval.summary =
          resources.getQuantityString(R.plurals.notification_minutes, minutes, minutes)
      }
      updateSummary(interval.value)
      interval.setOnPreferenceChangeListener { _, value ->
        updateSummary((value as Int).coerceIn(1, 60))
        true
      }
    }
  }
}

/** Timer history: the stopwatch runs that have finished, with when each started and ran for. */
class StopwatchHistoryFragment : NotificationCodeFragment(R.string.stopwatch_history_title) {

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    val entries = Stopwatch.history(context)
    if (entries.isNotEmpty()) {
      screen.action(getString(R.string.stopwatch_history_clear)) {
        Stopwatch.clearHistory(context)
        view?.post { rebuild() }
      }
    }
    val list = screen.category(R.string.stopwatch_history_title)
    if (entries.isEmpty()) {
      list.information(getString(R.string.stopwatch_history_empty))
      return
    }
    val date = DateFormat.getDateFormat(context)
    val time = DateFormat.getTimeFormat(context)
    for (entry in entries) {
      val started = Date(entry.startedAtMillis)
      list.information(
        "${date.format(started)}, ${time.format(started)}",
        Stopwatch.formatElapsed(context, entry.durationMillis),
      )
    }
  }
}
