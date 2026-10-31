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
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
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
  private val playSound: (Int) -> Unit,
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
    if (milestone <= prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, 0)) return
    prefs.edit().putLong(Stopwatch.KEY_LAST_ANNOUNCED, milestone).apply()
    signal(R.raw.chime_up, Stopwatch.pulsesForMilestone(milestone / 60_000L))
    say(Stopwatch.formatElapsed(context, milestone), false)
  }

  private fun signal(soundResource: Int, pulses: Int = 1) {
    if (prefs.getBoolean(Stopwatch.KEY_SOUND, true)) playSound(soundResource)
    if (prefs.getBoolean(Stopwatch.KEY_VIBRATION, true)) vibrate(pulses)
  }

  private fun vibrate(pulses: Int) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    val strength =
      prefs.getInt(
        context.getString(R.string.pref_vibration_strength_key),
        context.resources.getInteger(R.integer.pref_vibration_strength_default),
      ).coerceIn(0, 100)
    if (strength == 0) return
    val count = pulses.coerceIn(1, 6)
    val timings =
      LongArray(count * 2) { index ->
        if (index == 0) 0L else if (index % 2 == 0) 140L else 60L
      }
    val effect =
      if (vibrator.hasAmplitudeControl()) {
        val amplitude = (255 * strength / 100f).toInt().coerceAtLeast(1)
        val amplitudes = IntArray(count * 2) { index -> if (index % 2 == 0) 0 else amplitude }
        VibrationEffect.createWaveform(timings, amplitudes, -1)
      } else {
        VibrationEffect.createWaveform(timings, -1)
      }
    runCatching { vibrator.vibrate(effect) }
  }
}
