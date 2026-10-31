package com.google.android.accessibility.talkback.timer

import android.content.SharedPreferences
import android.media.AudioManager
import com.google.android.accessibility.utils.output.VoiceProfiles

/** Shared voice and audio routing for periodic clock announcements and stopwatch speech. */
object TimerSpeechSettings {
  const val KEY_PROFILE = "pref_timer_voice_profile"
  const val KEY_STREAM = "pref_timer_audio_stream"

  @JvmStatic
  fun profileId(prefs: SharedPreferences): String {
    val id = prefs.getString(KEY_PROFILE, "").orEmpty()
    return id.takeIf { it in VoiceProfiles.ids(prefs) }.orEmpty()
  }

  @JvmStatic
  fun stream(prefs: SharedPreferences): Int =
    when (prefs.getString(KEY_STREAM, "accessibility")) {
      "media" -> AudioManager.STREAM_MUSIC
      "ring" -> AudioManager.STREAM_RING
      else -> AudioManager.STREAM_ACCESSIBILITY
    }
}
