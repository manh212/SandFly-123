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

import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.SpokenSchedules
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Runs the stopwatch: at each multiple of the reminder interval it says how long the stopwatch
 * has run, with the sound and vibration the stopwatch settings choose. Like [SpokenSchedules],
 * it uses alarms with listeners in SandFly's process, which wake the phone without extra
 * permission, set again after each one and when the settings change.
 */
class StopwatchEngine(
  private val context: Context,
  /** Says a text, interrupting what notifications are saying when the second value is true. */
  private val say: (CharSequence, Boolean) -> Unit,
  /** Plays sound and vibration feedback through SandFly's pipeline. */
  private val feedback: (Feedback.Part.Builder) -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val alarms = context.getSystemService(AlarmManager::class.java)
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val reminderAlarm = AlarmManager.OnAlarmListener { onReminder() }
  private var started = false

  private val settingsListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == Stopwatch.KEY_INTERVAL_MINUTES || key == SpokenSchedules.KEY_CLOCK_EXACT) {
        schedule()
      }
    }

  @MainThread
  fun start() {
    if (started) return
    started = true
    prefs.registerOnSharedPreferenceChangeListener(settingsListener)
    schedule()
  }

  @MainThread
  fun stop() {
    if (!started) return
    started = false
    prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
    alarms?.cancel(reminderAlarm)
  }

  /** Starts the stopwatch if it is stopped, and stops it if it is running. */
  @MainThread
  fun toggle() {
    if (Stopwatch.isRunning(context)) stopStopwatch() else startStopwatch()
  }

  private fun startStopwatch() {
    prefs.edit().putLong(Stopwatch.KEY_STARTED_AT, System.currentTimeMillis()).apply()
    signal(R.raw.chime_up)
    say(context.getString(R.string.stopwatch_started), false)
    schedule()
  }

  private fun stopStopwatch() {
    val startedAt = Stopwatch.startedAt(context)
    val now = System.currentTimeMillis()
    prefs.edit().putLong(Stopwatch.KEY_STARTED_AT, 0).apply()
    alarms?.cancel(reminderAlarm)
    if (startedAt > 0) {
      Stopwatch.addHistory(context, Stopwatch.Entry(startedAt, now - startedAt))
    }
    signal(R.raw.chime_down)
    say(
      context.getString(R.string.stopwatch_stopped, Stopwatch.formatElapsed(context, now - startedAt)),
      false,
    )
  }

  private fun schedule() {
    val alarms = alarms ?: return
    alarms.cancel(reminderAlarm)
    val startedAt = Stopwatch.startedAt(context)
    if (startedAt <= 0) return
    val intervalMs = Stopwatch.intervalMinutes(context) * 60_000L
    if (intervalMs <= 0) return
    val now = System.currentTimeMillis()
    val next = startedAt + ((now - startedAt) / intervalMs + 1) * intervalMs
    set(alarms, next)
  }

  private fun set(alarms: AlarmManager, at: Long) {
    val exact = prefs.getBoolean(SpokenSchedules.KEY_CLOCK_EXACT, false)
    try {
      if (exact && canScheduleExact(alarms)) {
        alarms.setExact(AlarmManager.RTC_WAKEUP, at, TAG, reminderAlarm, handler)
      } else {
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, TAG, reminderAlarm, handler)
      }
    } catch (e: SecurityException) {
      alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, TAG, reminderAlarm, handler)
    }
  }

  private fun canScheduleExact(alarms: AlarmManager): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

  private fun onReminder() {
    schedule()
    val startedAt = Stopwatch.startedAt(context)
    if (startedAt <= 0) return
    val intervalMs = Stopwatch.intervalMinutes(context) * 60_000L
    if (intervalMs <= 0) return
    val elapsed = System.currentTimeMillis() - startedAt
    val milestone = elapsed / intervalMs * intervalMs
    if (milestone <= 0) return
    signal(R.raw.chime_up)
    say(Stopwatch.formatElapsed(context, milestone), false)
  }

  /** The sound and vibration of a start, a stop or a reminder, as the settings choose. */
  private fun signal(soundResource: Int) {
    val sound = prefs.getBoolean(Stopwatch.KEY_SOUND, true)
    val vibration = prefs.getBoolean(Stopwatch.KEY_VIBRATION, true)
    if (!sound && !vibration) return
    val builder = Feedback.part()
    if (sound) builder.sound(soundResource)
    if (vibration) builder.vibration(R.array.view_clicked_pattern)
    feedback(builder)
  }

  private companion object {
    const val TAG = "Stopwatch"
    const val WINDOW_MS = 60_000L
  }
}
