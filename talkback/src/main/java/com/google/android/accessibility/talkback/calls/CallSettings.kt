package com.google.android.accessibility.talkback.calls

import android.content.SharedPreferences
import java.util.Calendar

data class CallSettings(
  val announce: Boolean = true,
  val duration: Boolean = false,
  val intervalSeconds: Int = 0,
  val repeats: Int = 0,
  val voiceProfile: String = "",
  val quietHours: Boolean = false,
  val quietStart: Int = 23 * 60,
  val quietEnd: Int = 6 * 60,
  val duringDnd: Boolean = false,
) {
  fun isQuiet(minutes: Int): Boolean = quietHours && when {
    quietStart == quietEnd -> true
    quietStart < quietEnd -> minutes in quietStart until quietEnd
    else -> minutes >= quietStart || minutes < quietEnd
  }

  fun isQuietNow(): Boolean = Calendar.getInstance().let {
    isQuiet(it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE))
  }

  companion object {
    const val ANNOUNCE = "pref_call_announce"
    const val DURATION = "pref_call_duration"
    const val INTERVAL = "pref_call_duration_interval"
    const val REPEATS = "pref_call_repeats"
    const val VOICE = "pref_call_voice_profile"
    const val QUIET = "pref_call_quiet_hours"
    const val START = "pref_call_quiet_start"
    const val END = "pref_call_quiet_end"
    const val DND = "pref_call_during_dnd"

    fun read(prefs: SharedPreferences): CallSettings {
      fun number(key: String, default: Int): Int =
        prefs.all[key]?.toString()?.toIntOrNull() ?: default
      return CallSettings(
        announce = prefs.getBoolean(ANNOUNCE, true),
        duration = prefs.getBoolean(DURATION, false),
        intervalSeconds = number(INTERVAL, 0).coerceIn(0, 3600),
        repeats = number(REPEATS, 0).coerceIn(0, 10),
        voiceProfile = prefs.getString(VOICE, "").orEmpty(),
        quietHours = prefs.getBoolean(QUIET, false),
        quietStart = number(START, 23 * 60).coerceIn(0, 1439),
        quietEnd = number(END, 6 * 60).coerceIn(0, 1439),
        duringDnd = prefs.getBoolean(DND, false),
      )
    }
  }
}
