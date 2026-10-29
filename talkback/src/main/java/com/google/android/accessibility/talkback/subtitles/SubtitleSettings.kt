package com.google.android.accessibility.talkback.subtitles

import android.content.SharedPreferences
import android.graphics.Rect

object SubtitleSettings {
  const val ENGINE = "pref_subtitle_engine"
  const val REGION = "pref_subtitle_region"
  const val SPEECH = "pref_subtitle_speech"
  const val INTERVAL = "pref_subtitle_interval"
  const val LEFT = "pref_subtitle_left"
  const val TOP = "pref_subtitle_top"
  const val RIGHT = "pref_subtitle_right"
  const val BOTTOM = "pref_subtitle_bottom"

  fun engine(prefs: SharedPreferences): String = prefs.getString(ENGINE, "tesseract") ?: "tesseract"
  fun region(prefs: SharedPreferences): String = prefs.getString(REGION, "auto") ?: "auto"
  fun interrupt(prefs: SharedPreferences): Boolean = prefs.getString(SPEECH, "finish") == "interrupt"
  fun interval(prefs: SharedPreferences): Long =
    (prefs.getString(INTERVAL, "750")?.toLongOrNull() ?: 750L).coerceIn(500L, 3000L)

  fun bounds(prefs: SharedPreferences, screen: Rect, focus: Rect?): Rect? {
    val result = Rect(screen)
    when (region(prefs)) {
      "top" -> result.bottom = screen.top + screen.height() * 40 / 100
      "middle" -> {
        result.top = screen.top + screen.height() * 30 / 100
        result.bottom = screen.top + screen.height() * 70 / 100
      }
      "bottom" -> result.top = screen.top + screen.height() * 60 / 100
      "focus" -> {
        if (focus == null || focus.isEmpty) return null
        result.set(focus)
      }
      "custom" -> {
        val left = prefs.getInt(LEFT, 0).coerceIn(0, 99)
        val top = prefs.getInt(TOP, 60).coerceIn(0, 99)
        val right = prefs.getInt(RIGHT, 100).coerceIn(left + 1, 100)
        val bottom = prefs.getInt(BOTTOM, 100).coerceIn(top + 1, 100)
        result.set(screen.left + screen.width() * left / 100,
          screen.top + screen.height() * top / 100,
          screen.left + screen.width() * right / 100,
          screen.top + screen.height() * bottom / 100)
      }
    }
    return if (result.intersect(screen) && !result.isEmpty) result else null
  }
}
