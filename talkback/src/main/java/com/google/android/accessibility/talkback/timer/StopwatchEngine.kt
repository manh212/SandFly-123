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
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.AlarmClockIntents
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Runs the stopwatch: at each multiple of the reminder interval it says how long the stopwatch
 * has run, with the sound and vibration the stopwatch settings choose. A reminder is a system
 * alarm clock, so it still fires on time while the phone rests. If a reminder's alarm is lost —
 * SandFly restarting across it, for one — the milestone is not dropped: the last announced
 * milestone is kept, and when the engine next runs it says the newest milestone it owes, once.
 */
class StopwatchEngine(
  private val context: Context,
  /** Says a text, interrupting what notifications are saying when the second value is true. */
  private val say: (CharSequence, Boolean) -> Unit,
  /** Plays sound and vibration feedback through SandFly's pipeline. */
  private val feedback: (Feedback.Part.Builder) -> Unit,
) {
  private val alarms = context.getSystemService(AlarmManager::class.java)
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  /** Receives a reminder when its alarm clock fires, and runs the reminder. */
  private val alarmReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(receiverContext: Context, intent: Intent) {
        if (intent.action == alarmAction()) onReminder()
      }
    }
  private var started = false

  private val settingsListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == Stopwatch.KEY_INTERVAL_MINUTES) schedule()
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
    announceLatestMilestone()
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
    prefs
      .edit()
      .putLong(Stopwatch.KEY_STARTED_AT, System.currentTimeMillis())
      .putLong(Stopwatch.KEY_LAST_ANNOUNCED, 0)
      .apply()
    signal(R.raw.chime_up)
    say(context.getString(R.string.stopwatch_started), false)
    schedule()
  }

  private fun stopStopwatch() {
    val startedAt = Stopwatch.startedAt(context)
    val now = System.currentTimeMillis()
    prefs
      .edit()
      .putLong(Stopwatch.KEY_STARTED_AT, 0)
      .putLong(Stopwatch.KEY_LAST_ANNOUNCED, 0)
      .apply()
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
    val pending = AlarmClockIntents.alarm(context, AlarmClockIntents.ACTION_STOPWATCH)
    try {
      alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, AlarmClockIntents.showSettings(context)), pending)
    } catch (e: SecurityException) {
      // An alarm clock needs no permission; should a system ever refuse one, an alarm that may
      // run late still beats none — a late milestone is still announced, a lost one caught up.
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
  }

  private fun cancelAlarm(alarms: AlarmManager) {
    alarms.cancel(AlarmClockIntents.alarm(context, AlarmClockIntents.ACTION_STOPWATCH))
  }

  private fun alarmAction(): String =
    AlarmClockIntents.action(context, AlarmClockIntents.ACTION_STOPWATCH)

  private fun onReminder() {
    schedule()
    announceLatestMilestone()
  }

  /**
   * Says the newest milestone the stopwatch has reached and not yet said, with the reminder's
   * sound. A milestone an alarm delivered late, or one whose alarm never arrived, is said here
   * instead of being lost; one already said is not said again.
   */
  private fun announceLatestMilestone() {
    val startedAt = Stopwatch.startedAt(context)
    if (startedAt <= 0) return
    val intervalMs = Stopwatch.intervalMinutes(context) * 60_000L
    if (intervalMs <= 0) return
    val elapsed = System.currentTimeMillis() - startedAt
    val milestone = elapsed / intervalMs * intervalMs
    if (milestone <= 0) return
    if (!prefs.contains(Stopwatch.KEY_LAST_ANNOUNCED)) {
      // First sight of the marker, as after an upgrade from a version that never wrote it:
      // everything up to now counts as said, so the newest milestone is not said a second time.
      prefs.edit().putLong(Stopwatch.KEY_LAST_ANNOUNCED, milestone).apply()
      return
    }
    if (milestone <= prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, 0)) return
    prefs.edit().putLong(Stopwatch.KEY_LAST_ANNOUNCED, milestone).apply()
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
}
