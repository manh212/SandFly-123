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
import com.google.android.accessibility.talkback.notifications.NotificationReader
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Starts or stops the stopwatch when volume up is held while the phone is locked. In that
 * context the service takes the key completely: no volume up event reaches the system, a press
 * let go before the hold counts as a tap and is given back as one volume step, and a hold past
 * the threshold toggles the stopwatch instead of raising the volume.
 */
object StopwatchVolumeKey {
  /** How long the key must be held to start or stop the stopwatch. */
  private const val HOLD_MS = 500L

  private val handler = Handler(Looper.getMainLooper())
  private var captured = false
  private var triggered = false
  private var aborted = false
  private var captureContext: Context? = null

  private val trigger = Runnable {
    val context = captureContext ?: return@Runnable
    if (!captured || triggered || aborted) return@Runnable
    if (!conditionsMet(context)) {
      // The phone was unlocked, or the setting turned off, while the key was held.
      aborted = true
      return@Runnable
    }
    triggered = true
    NotificationReader.toggleStopwatch()
  }

  /** Returns whether [event] was taken for the stopwatch. */
  @JvmStatic
  @MainThread
  fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
    return when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        if (event.repeatCount > 0) {
          if (captured) noteUnlock(context)
          captured
        } else {
          // A fresh press while a capture is somehow still open ends the old one first.
          if (captured) reset()
          if (conditionsMet(context)) {
            captured = true
            captureContext = context.applicationContext
            handler.postDelayed(trigger, HOLD_MS)
            true
          } else {
            false
          }
        }
      }
      KeyEvent.ACTION_UP -> {
        if (!captured) {
          false
        } else {
          val giveBackStep = !triggered && !aborted
          val appContext = captureContext
          reset()
          if (giveBackStep && appContext != null) raiseVolume(appContext)
          true
        }
      }
      else -> captured
    }
  }

  /** Ends any press in progress, without toggling or giving back a volume step. */
  @JvmStatic
  fun reset() {
    handler.removeCallbacks(trigger)
    captured = false
    triggered = false
    aborted = false
    captureContext = null
  }

  /** Notes that the phone left the locked context mid-press; the press then does nothing. */
  private fun noteUnlock(context: Context) {
    if (!triggered && !aborted && !isKeyguardLocked(context)) {
      handler.removeCallbacks(trigger)
      aborted = true
    }
  }

  private fun conditionsMet(context: Context): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (!prefs.getBoolean(Stopwatch.KEY_VOLUME_ACTIVATION, true)) return false
    return isKeyguardLocked(context)
  }

  private fun isKeyguardLocked(context: Context): Boolean {
    val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
    return keyguard.isKeyguardLocked
  }

  /** Gives back the volume step a press that turned out to be a tap would have made. */
  private fun raiseVolume(context: Context) {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return
    audioManager.adjustVolume(AudioManager.ADJUST_RAISE, 0)
  }
}
