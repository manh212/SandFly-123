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
import androidx.preference.ListPreference
import androidx.preference.PreferenceGroup
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
  NotificationXmlFragment(R.xml.timer_preferences, R.string.timer_settings_title) {

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
    val interval = findPreference<ListPreference>(Stopwatch.KEY_INTERVAL_MINUTES) ?: return
    interval.entries =
      Stopwatch.INTERVAL_CHOICES
        .map { minutes -> resources.getQuantityString(R.plurals.notification_minutes, minutes, minutes) }
        .toTypedArray()
    interval.entryValues = Stopwatch.INTERVAL_CHOICES.map { it.toString() }.toTypedArray()
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
      list.action(getString(R.string.stopwatch_history_empty)) {}
      return
    }
    val date = DateFormat.getDateFormat(context)
    val time = DateFormat.getTimeFormat(context)
    for (entry in entries) {
      val started = Date(entry.startedAtMillis)
      list.action(
        "${date.format(started)}, ${time.format(started)}",
        Stopwatch.formatElapsed(context, entry.durationMillis),
      ) {}
    }
  }
}
