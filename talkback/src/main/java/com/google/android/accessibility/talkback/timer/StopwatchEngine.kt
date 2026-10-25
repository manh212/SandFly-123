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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.AlarmClockIntents
import com.google.android.accessibility.talkback.notifications.SpokenSchedules
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Runs the stopwatch: at each multiple of the reminder interval it says how long the stopwatch
 * has run, with the sound and vibration the stopwatch settings choose. Like [SpokenSchedules],
 * its alarms are set again after each one and when the settings change. With exact times on, a
 * reminder is a system alarm clock, so it still fires on time while the phone rests; otherwise
 * it is an alarm with a listener in SandFly's process, which the system may delay by a minute.
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

  /** Receives an exact reminder when its alarm clock fires, and runs the reminder. */
  private val alarmReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(receiverContext: Context, intent: Intent) {
        if (intent.action == alarmAction()) onReminder()
      }
    }
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
    ContextCompat.registerReceiver(
      context,
      alarmReceiver,
      IntentFilter(alarmAction()),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    prefs.registerOnSharedPreferenceChangeListener(settingsListener)
    schedule()
  }

  @MainThread
  fun stop() {
    if (!started) return
    started = false
    prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
    runCatching { context.unregisterReceiver(alarmReceiver) }
    alarms?.let { cancelAlarm(it) }
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
    alarms?.let { cancelAlarm(it) }
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
    cancelAlarm(alarms)
    val startedAt = Stopwatch.startedAt(context)
    if (startedAt <= 0) return
    val intervalMs = Stopwatch.intervalMinutes(context) * 60_000L
    if (intervalMs <= 0) return
    val now = System.currentTimeMillis()
    val next = startedAt + ((now - startedAt) / intervalMs + 1) * intervalMs
    set(alarms, next)
  }

  private fun set(alarms: AlarmManager, at: Long) {
    if (prefs.getBoolean(SpokenSchedules.KEY_CLOCK_EXACT, false)) {
      alarms.setAlarmClock(
        AlarmManager.AlarmClockInfo(at, AlarmClockIntents.showSettings(context)),
        AlarmClockIntents.alarm(context, AlarmClockIntents.ACTION_STOPWATCH),
      )
    } else {
      alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, TAG, reminderAlarm, handler)
    }
  }

  /** Drops the pending reminder in both of its forms, so switching forms never leaves two. */
  private fun cancelAlarm(alarms: AlarmManager) {
    alarms.cancel(reminderAlarm)
    alarms.cancel(AlarmClockIntents.alarm(context, AlarmClockIntents.ACTION_STOPWATCH))
  }

  private fun alarmAction(): String =
    AlarmClockIntents.action(context, AlarmClockIntents.ACTION_STOPWATCH)

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
