/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.talkback.timer.TimerSettingsFragment
import com.google.android.accessibility.utils.preference.BasePreferencesActivity

/**
 * The pending intents of the spoken schedules and the stopwatch. [AlarmManager.setAlarmClock]
 * takes pending intents rather than the in-process listeners the inexact alarms use, so each
 * exact alarm fires a broadcast with one of these actions, which the owning engine receives
 * through a receiver it registers while it runs, and a tap on the system's alarm entry opens
 * the timer settings.
 */
object AlarmClockIntents {
  const val ACTION_STOPWATCH = ".timer.action.STOPWATCH_REMINDER"
  const val ACTION_SPOKEN_CLOCK = ".notifications.action.SPOKEN_CLOCK"
  const val ACTION_SPOKEN_SUMMARY = ".notifications.action.SPOKEN_SUMMARY"

  private const val REQUEST_SHOW = 4100
  private const val REQUEST_STOPWATCH = 4101
  private const val REQUEST_SPOKEN_CLOCK = 4102
  private const val REQUEST_SPOKEN_SUMMARY = 4103

  /** The full broadcast action of one alarm, in SandFly's package. */
  fun action(context: Context, suffix: String): String = context.packageName + suffix

  /** The pending intent an exact alarm fires, carrying only its [suffix] action. */
  fun alarm(context: Context, suffix: String): PendingIntent {
    val intent = Intent(action(context, suffix)).setPackage(context.packageName)
    return PendingIntent.getBroadcast(
      context,
      requestCode(suffix),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  /** Opens the timer settings, as the show intent of every spoken alarm clock. */
  fun showSettings(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      REQUEST_SHOW,
      Intent(context, TalkBackPreferencesActivity::class.java)
        .putExtra(BasePreferencesActivity.FRAGMENT_NAME, TimerSettingsFragment::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun requestCode(suffix: String): Int =
    when (suffix) {
      ACTION_STOPWATCH -> REQUEST_STOPWATCH
      ACTION_SPOKEN_CLOCK -> REQUEST_SPOKEN_CLOCK
      else -> REQUEST_SPOKEN_SUMMARY
    }
}
