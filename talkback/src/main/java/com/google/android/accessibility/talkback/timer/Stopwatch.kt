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

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** The stopwatch's state and history, in preferences so they survive SandFly restarting. */
object Stopwatch {
  const val KEY_SOUND = "pref_stopwatch_sound"
  const val KEY_VIBRATION = "pref_stopwatch_vibration"
  const val KEY_INTERVAL_MINUTES = "pref_stopwatch_interval_minutes"
  const val KEY_STARTED_AT = "pref_stopwatch_started_at"
  const val KEY_LAST_ANNOUNCED = "pref_stopwatch_last_announced"
  const val KEY_HISTORY = "pref_stopwatch_history"
  const val MAX_HISTORY = 50
  val INTERVAL_CHOICES = listOf(5, 10, 15, 20, 30, 60)

  /** One finished run of the stopwatch. */
  data class Entry(val startedAtMillis: Long, val durationMillis: Long)

  @JvmStatic
  fun startedAt(context: Context): Long =
    SharedPreferencesUtils.getSharedPreferences(context).getLong(KEY_STARTED_AT, 0)

  @JvmStatic fun isRunning(context: Context): Boolean = startedAt(context) > 0

  fun intervalMinutes(context: Context): Int =
    SharedPreferencesUtils.getSharedPreferences(context)
      .getString(KEY_INTERVAL_MINUTES, null)
      ?.toIntOrNull() ?: 10

  /** How long the stopwatch has run, as it is said, or null while it is not running. */
  @JvmStatic
  fun elapsedText(context: Context): String? {
    val startedAt = startedAt(context)
    if (startedAt <= 0) return null
    return formatElapsed(context, System.currentTimeMillis() - startedAt)
  }

  /** A length of time as it is said: hours and minutes, or minutes and seconds under an hour. */
  @JvmStatic
  fun formatElapsed(context: Context, elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
    val resources = context.resources
    val hours = (totalSeconds / 3600).toInt()
    if (hours > 0) {
      val minutes = ((totalSeconds % 3600) / 60).toInt()
      return resources.getQuantityString(R.plurals.notification_hours, hours, hours) +
        " " +
        resources.getQuantityString(R.plurals.notification_minutes, minutes, minutes)
    }
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return resources.getQuantityString(R.plurals.notification_minutes, minutes, minutes) +
      " " +
      resources.getQuantityString(R.plurals.notification_seconds, seconds, seconds)
  }

  /** The finished runs, newest first. */
  fun history(context: Context): List<Entry> {
    val stored =
      SharedPreferencesUtils.getSharedPreferences(context).getString(KEY_HISTORY, null)
        ?: return emptyList()
    return stored
      .split(";")
      .mapNotNull { part ->
        val fields = part.split(":")
        val startedAt = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val duration = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        Entry(startedAt, duration)
      }
  }

  fun addHistory(context: Context, entry: Entry) {
    val entries = (listOf(entry) + history(context)).take(MAX_HISTORY)
    saveHistory(context, entries)
  }

  fun clearHistory(context: Context) {
    SharedPreferencesUtils.getSharedPreferences(context).edit().remove(KEY_HISTORY).apply()
  }

  private fun saveHistory(context: Context, entries: List<Entry>) {
    val stored = entries.joinToString(";") { "${it.startedAtMillis}:${it.durationMillis}" }
    SharedPreferencesUtils.getSharedPreferences(context)
      .edit()
      .putString(KEY_HISTORY, stored)
      .apply()
  }
}
