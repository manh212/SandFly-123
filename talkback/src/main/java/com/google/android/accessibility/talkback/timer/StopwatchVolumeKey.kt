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

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.MainThread
import com.google.android.accessibility.talkback.monitor.RingerModeAndScreenMonitor
import com.google.android.accessibility.talkback.notifications.NotificationReader
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Starts or stops the stopwatch when volume up is held just after the screen turns on, while
 * the phone is locked. The press is taken, so the volume does not change while it is held; a
 * press let go before the hold counts as a tap, and is given back as one volume step.
 */
object StopwatchVolumeKey {
  /** How soon after the screen turns on the press must start. */
  private const val WINDOW_MS = 500L
  /** How long the key must be held to start or stop the stopwatch. */
  private const val HOLD_MS = 500L

  private val handler = Handler(Looper.getMainLooper())
  private var captured = false
  private var triggered = false

  private val trigger = Runnable {
    // The screen turning off again in the meantime ends the press without a toggle.
    if (captured && RingerModeAndScreenMonitor.getLastScreenOnTimeMs() > 0) {
      triggered = true
      NotificationReader.toggleStopwatch()
    }
  }

  /** Returns whether [event] was taken for the stopwatch. */
  @JvmStatic
  @MainThread
  fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
    return when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        if (captured) {
          true
        } else if (event.repeatCount == 0 && conditionsMet(context, event)) {
          captured = true
          triggered = false
          handler.postDelayed(trigger, HOLD_MS)
          true
        } else {
          false
        }
      }
      KeyEvent.ACTION_UP -> {
        if (!captured) {
          false
        } else {
          captured = false
          handler.removeCallbacks(trigger)
          if (!triggered) raiseVolume(context)
          triggered = false
          true
        }
      }
      else -> captured
    }
  }

  private fun conditionsMet(context: Context, event: KeyEvent): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (!prefs.getBoolean(Stopwatch.KEY_VOLUME_ACTIVATION, true)) return false
    val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
    if (!keyguard.isKeyguardLocked) return false
    val screenOnAt = RingerModeAndScreenMonitor.getLastScreenOnTimeMs()
    if (screenOnAt <= 0) return false
    val delay = event.eventTime - screenOnAt
    return delay in 0..WINDOW_MS
  }

  /** Gives back the volume step a press that turned out to be a tap would have made. */
  private fun raiseVolume(context: Context) {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    audioManager.adjustVolume(AudioManager.ADJUST_RAISE, 0)
  }
}
