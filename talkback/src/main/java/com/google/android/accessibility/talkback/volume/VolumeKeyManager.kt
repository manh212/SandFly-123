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

package com.google.android.accessibility.talkback.volume

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.MainThread
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.monitor.AudioPlaybackMonitor

/**
 * Handles the volume keys for SandFly when the volume key settings say SandFly manages them. In
 * that mode no volume key event reaches the system: each key has an action for a press and an
 * action for a hold, on the lock screen and off it, and a press on a slider or in a text field
 * keeps adjusting the slider or moving the cursor when those settings are on. While a call is
 * going on, or an alarm is ringing, the keys are left to the system.
 */
object VolumeKeyManager {
  const val KEY_MANAGED = "pref_volume_keys_managed"
  const val KEY_UP_PRESS_ACTION = "pref_volume_up_press_action"
  const val KEY_UP_HOLD_ACTION = "pref_volume_up_hold_action"
  const val KEY_UP_HOLD_LOCKED_ACTION = "pref_volume_up_hold_locked_action"
  const val KEY_DOWN_PRESS_ACTION = "pref_volume_down_press_action"
  const val KEY_DOWN_HOLD_ACTION = "pref_volume_down_hold_action"
  const val KEY_DOWN_HOLD_LOCKED_ACTION = "pref_volume_down_hold_locked_action"

  private const val KEY_MIGRATED = "pref_volume_keys_migrated"
  private const val OLD_KEY_VOLUME_ACTIVATION = "pref_stopwatch_volume_activation"

  private const val ACTION_UNASSIGNED = "UNASSIGNED"
  private const val ACTION_INCREASE_CONTEXTUAL_VOLUME = "INCREASE_CONTEXTUAL_VOLUME"
  private const val ACTION_DECREASE_CONTEXTUAL_VOLUME = "DECREASE_CONTEXTUAL_VOLUME"
  private const val ACTION_TOGGLE_STOPWATCH = "TOGGLE_STOPWATCH"

  /** How long a key must be held for its hold action. */
  private const val HOLD_MS = 500L

  private val handler = Handler(Looper.getMainLooper())
  private val upPress = Press(isUp = true)
  private val downPress = Press(isUp = false)

  /** One key's press in progress, with the context it started in. */
  private class Press(val isUp: Boolean) {
    var service: TalkBackService? = null
    var active = false
    var aborted = false
    var holdFired = false
    var locked = false
    var touching = false
    var pressUse = TalkBackService.VOLUME_KEY_CHANGES_VOLUME
    var pressAction = ACTION_UNASSIGNED
    var holdAction = ACTION_UNASSIGNED
    var holdRunnable: Runnable? = null
  }

  /** Returns whether [event] was taken by the volume key manager. */
  @JvmStatic
  @MainThread
  fun onKeyEvent(
    service: TalkBackService,
    event: KeyEvent,
    triggerModifierPressed: Boolean,
  ): Boolean {
    val press =
      when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> upPress
        KeyEvent.KEYCODE_VOLUME_DOWN -> downPress
        else -> return false
      }
    // Volume keys in a keyboard shortcut combination stay with the key combo manager.
    if (triggerModifierPressed && !press.active) return false
    // Key events have no cancel action; reset() covers the screen turning off and unbinding.
    return when (event.action) {
      KeyEvent.ACTION_DOWN ->
        if (event.repeatCount > 0) onRepeat(press) else onDown(service, press)
      KeyEvent.ACTION_UP -> onUp(press)
      else -> press.active
    }
  }

  private fun onDown(service: TalkBackService, press: Press): Boolean {
    // A fresh press while a capture is somehow still open ends the old one first.
    if (press.active) endPress(press)
    if (!isManaged(service)) return false
    if (service.isPhoneCallActive()) return false
    if (isAlarmRinging(service)) return false
    val other = if (press === upPress) downPress else upPress
    press.service = service
    press.active = true
    press.holdFired = false
    press.locked = isKeyguardLocked(service)
    press.touching = service.isTouchInteracting()
    if (other.active) {
      // Both keys together are the system's own combination: neither press does anything,
      // and both stay taken until they are let go.
      abort(other)
      press.aborted = true
      return true
    }
    press.aborted = false
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    val holdKeyName = if (press.locked) holdLockedKey(press.isUp) else holdKey(press.isUp)
    press.holdAction =
      prefs.getString(holdKeyName, defaultHoldAction(press.isUp, press.locked))
        ?: defaultHoldAction(press.isUp, press.locked)
    press.pressAction =
      prefs.getString(pressKey(press.isUp), defaultPressAction(press.isUp))
        ?: defaultPressAction(press.isUp)
    press.pressUse =
      if (press.locked) {
        TalkBackService.VOLUME_KEY_CHANGES_VOLUME
      } else {
        service.resolveVolumeKeyUse(/* triggerModifierPressed= */ false)
      }
    if (press.holdAction == ACTION_UNASSIGNED) {
      runPressAction(press)
    } else {
      val runnable = Runnable { fireHold(press) }
      press.holdRunnable = runnable
      handler.postDelayed(runnable, HOLD_MS)
    }
    return true
  }

  private fun onRepeat(press: Press): Boolean {
    if (!press.active) return false
    val service = press.service ?: return true
    if (!press.aborted && contextChanged(service, press)) abort(press)
    if (press.aborted) return true
    if (press.holdFired) {
      // A hold on the contextual volume action in the key's own direction keeps stepping.
      if (press.holdAction == contextualAction(press.isUp)) {
        service.adjustContextualVolume(press.isUp, press.touching)
      }
      return true
    }
    if (press.holdAction == ACTION_UNASSIGNED) repeatPressAction(press)
    return true
  }

  private fun onUp(press: Press): Boolean {
    if (!press.active) return false
    if (!press.aborted && !press.holdFired && press.holdAction != ACTION_UNASSIGNED) {
      // Let go before the hold threshold: the press was a tap, and runs once.
      runPressAction(press)
    }
    endPress(press)
    return true
  }

  private fun fireHold(press: Press) {
    val service = press.service ?: return
    if (!press.active || press.aborted || press.holdFired) return
    if (contextChanged(service, press)) {
      press.aborted = true
      return
    }
    press.holdFired = true
    runAction(service, press.holdAction, press.touching)
  }

  /** Runs the press action once: a slider or cursor step, or the assigned action. */
  private fun runPressAction(press: Press) {
    val service = press.service ?: return
    if (press.pressUse != TalkBackService.VOLUME_KEY_CHANGES_VOLUME) {
      service.performVolumeKeyUse(press.pressUse, press.isUp)
    } else {
      runAction(service, press.pressAction, press.touching)
    }
  }

  /** Repeats a held press. Only the volume, slider and cursor steps repeat; actions run once. */
  private fun repeatPressAction(press: Press) {
    val service = press.service ?: return
    if (press.pressUse != TalkBackService.VOLUME_KEY_CHANGES_VOLUME) {
      service.performVolumeKeyUse(press.pressUse, press.isUp)
    } else if (
      press.pressAction == ACTION_INCREASE_CONTEXTUAL_VOLUME ||
        press.pressAction == ACTION_DECREASE_CONTEXTUAL_VOLUME
    ) {
      service.adjustContextualVolume(
        press.pressAction == ACTION_INCREASE_CONTEXTUAL_VOLUME,
        press.touching,
      )
    }
  }

  private fun runAction(service: TalkBackService, action: String, touching: Boolean) {
    when (action) {
      ACTION_UNASSIGNED -> Unit
      ACTION_INCREASE_CONTEXTUAL_VOLUME -> service.adjustContextualVolume(true, touching)
      ACTION_DECREASE_CONTEXTUAL_VOLUME -> service.adjustContextualVolume(false, touching)
      else -> service.performCatalogAction(action)
    }
  }

  /** The press does nothing any more, but its events stay taken until the key is let go. */
  private fun abort(press: Press) {
    press.aborted = true
    press.holdRunnable?.let { handler.removeCallbacks(it) }
    press.holdRunnable = null
  }

  private fun endPress(press: Press) {
    press.holdRunnable?.let { handler.removeCallbacks(it) }
    press.holdRunnable = null
    press.service = null
    press.active = false
    press.aborted = false
    press.holdFired = false
    press.pressUse = TalkBackService.VOLUME_KEY_CHANGES_VOLUME
    press.pressAction = ACTION_UNASSIGNED
    press.holdAction = ACTION_UNASSIGNED
  }

  /** Ends any press in progress, without running its press or hold action. */
  @JvmStatic
  fun reset() {
    endPress(upPress)
    endPress(downPress)
  }

  /** Whether the context a press started in no longer holds, so the press must do nothing. */
  private fun contextChanged(service: TalkBackService, press: Press): Boolean =
    !isManaged(service) ||
      service.isPhoneCallActive() ||
      isKeyguardLocked(service) != press.locked

  private fun isManaged(context: Context): Boolean =
    SharedPreferencesUtils.getSharedPreferences(context).getBoolean(KEY_MANAGED, false)

  private fun isKeyguardLocked(context: Context): Boolean {
    val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
    return keyguard.isKeyguardLocked
  }

  /**
   * Whether an alarm is ringing now, best effort: the active playback configurations are
   * asked, and not every alarm app shows up in them on every phone.
   */
  private fun isAlarmRinging(context: Context): Boolean =
    AudioPlaybackMonitor(context)
      .isPlaybackSourceActive(AudioPlaybackMonitor.PlaybackSource.USAGE_ALARM)

  private fun pressKey(isUp: Boolean): String =
    if (isUp) KEY_UP_PRESS_ACTION else KEY_DOWN_PRESS_ACTION

  private fun holdKey(isUp: Boolean): String =
    if (isUp) KEY_UP_HOLD_ACTION else KEY_DOWN_HOLD_ACTION

  private fun holdLockedKey(isUp: Boolean): String =
    if (isUp) KEY_UP_HOLD_LOCKED_ACTION else KEY_DOWN_HOLD_LOCKED_ACTION

  private fun contextualAction(isUp: Boolean): String =
    if (isUp) ACTION_INCREASE_CONTEXTUAL_VOLUME else ACTION_DECREASE_CONTEXTUAL_VOLUME

  private fun defaultPressAction(isUp: Boolean): String = contextualAction(isUp)

  private fun defaultHoldAction(isUp: Boolean, locked: Boolean): String =
    if (locked && isUp) ACTION_TOGGLE_STOPWATCH else ACTION_UNASSIGNED

  /**
   * Moves once from the stopwatch's own volume key switch, which the volume key settings
   * replace. Before, holding volume up on the lock screen toggled the stopwatch unless the
   * user had turned that switch off; SandFly keeps managing the keys on upgrade, and keeps the
   * lock screen hold of volume up unassigned for a user who had turned it off. A fresh install
   * starts with the keys unmanaged. An install counts as an upgrade when it was updated after
   * it was first installed, or when the old switch left a value behind.
   */
  @JvmStatic
  fun migrate(context: Context) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (prefs.getBoolean(KEY_MIGRATED, false)) return
    val editor = prefs.edit()
    editor.putBoolean(KEY_MIGRATED, true)
    if (prefs.contains(OLD_KEY_VOLUME_ACTIVATION)) {
      if (prefs.getBoolean(OLD_KEY_VOLUME_ACTIVATION, true)) {
        editor.putBoolean(KEY_MANAGED, true)
      } else {
        editor.putBoolean(KEY_MANAGED, false)
        editor.putString(KEY_UP_HOLD_LOCKED_ACTION, ACTION_UNASSIGNED)
      }
      editor.remove(OLD_KEY_VOLUME_ACTIVATION)
    } else if (isUpgrade(context)) {
      editor.putBoolean(KEY_MANAGED, true)
    }
    editor.apply()
  }

  @Suppress("DEPRECATION") // The flags overload of getPackageInfo needs API 33.
  private fun isUpgrade(context: Context): Boolean =
    try {
      val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
          )
        } else {
          context.packageManager.getPackageInfo(context.packageName, 0)
        }
      info.lastUpdateTime > info.firstInstallTime
    } catch (e: PackageManager.NameNotFoundException) {
      false
    }
}
