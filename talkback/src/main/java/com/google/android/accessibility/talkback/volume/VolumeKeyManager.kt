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

object VolumeKeyManager {
  const val KEY_MANAGED = "pref_volume_keys_managed"
  const val KEY_MULTI_ENABLED = "pref_volume_keys_multi_enabled"
  const val KEY_BOTH_ACTION = "pref_volume_both_action"
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
  private const val HOLD_MS = 500L
  private const val CHORD_MS = 150L
  private const val MULTI_PRESS_MS = 400L

  private val handler = Handler(Looper.getMainLooper())
  private val upPress = Press(true)
  private val downPress = Press(false)

  private class Press(val isUp: Boolean) {
    var service: TalkBackService? = null
    var active = false
    var aborted = false
    var holdFired = false
    var singleFired = false
    var locked = false
    var touching = false
    var managed = false
    var multi = false
    var pressUse = TalkBackService.VOLUME_KEY_CHANGES_VOLUME
    var pressAction = ACTION_UNASSIGNED
    var holdAction = ACTION_UNASSIGNED
    var holdRunnable: Runnable? = null
    var tapRunnable: Runnable? = null
    var tapCount = 0
  }

  @JvmStatic
  @MainThread
  fun onKeyEvent(
    service: TalkBackService,
    event: KeyEvent,
    triggerModifierPressed: Boolean,
  ): Boolean {
    val press = when (event.keyCode) {
      KeyEvent.KEYCODE_VOLUME_UP -> upPress
      KeyEvent.KEYCODE_VOLUME_DOWN -> downPress
      else -> return false
    }
    if (triggerModifierPressed && !press.active) {
      reset()
      return false
    }
    return when (event.action) {
      KeyEvent.ACTION_DOWN ->
        if (event.repeatCount > 0) onRepeat(press) else onDown(service, press)
      KeyEvent.ACTION_UP -> onUp(press, event.isCanceled)
      else -> press.active
    }
  }

  private fun onDown(service: TalkBackService, press: Press): Boolean {
    if (press.active) abort(press)
    if (service.isPhoneCallActive() || isAlarmRinging(service)) {
      reset()
      return false
    }
    if (press.tapCount > 0 && contextChanged(service, press)) clearTaps(press)
    press.tapRunnable?.let(handler::removeCallbacks)
    press.tapRunnable = null
    val other = if (press === upPress) downPress else upPress
    if (other.tapCount > 0 && !other.active) finishTaps(other)
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    press.service = service
    press.active = true
    press.aborted = false
    press.holdFired = false
    press.singleFired = false
    press.locked = isKeyguardLocked(service)
    press.touching = service.isTouchInteracting()
    press.managed = prefs.getBoolean(KEY_MANAGED, false)
    press.multi = prefs.getBoolean(KEY_MULTI_ENABLED, false)
    if (other.active) {
      val alreadyAborted = other.aborted
      abort(other)
      abort(press)
      if (!alreadyAborted) {
        runAction(service, prefs.getString(KEY_BOTH_ACTION, "TOGGLE_TOUCH_EXPLORATION")
          ?: "TOGGLE_TOUCH_EXPLORATION", press.touching)
      }
      return true
    }
    val holdKeyName = if (press.locked) holdLockedKey(press.isUp) else holdKey(press.isUp)
    press.holdAction = if (press.managed) {
      prefs.getString(holdKeyName, defaultHoldAction(press.isUp, press.locked))
        ?: defaultHoldAction(press.isUp, press.locked)
    } else ACTION_UNASSIGNED
    press.pressAction = if (press.managed) {
      prefs.getString(pressKey(press.isUp), defaultPressAction(press.isUp))
        ?: defaultPressAction(press.isUp)
    } else contextualAction(press.isUp)
    press.pressUse = if (press.locked) TalkBackService.VOLUME_KEY_CHANGES_VOLUME
      else service.resolveVolumeKeyUse(false)
    val runnable = Runnable {
      if (contextChanged(service, press)) abort(press)
      if (press.active && !press.aborted) {
        if (press.multi || press.holdAction != ACTION_UNASSIGNED) fireHold(press)
        else {
          runPressAction(press)
          press.singleFired = true
        }
      }
    }
    press.holdRunnable = runnable
    handler.postDelayed(runnable,
      if (press.multi || press.holdAction != ACTION_UNASSIGNED) HOLD_MS else CHORD_MS)
    return true
  }

  private fun onRepeat(press: Press): Boolean {
    if (!press.active) return false
    val service = press.service ?: return true
    if (contextChanged(service, press)) abort(press)
    if (!press.aborted) {
      if (press.holdFired && press.holdAction == contextualAction(press.isUp)) {
        service.adjustContextualVolume(press.isUp, press.touching)
      } else if ((press.singleFired || press.holdFired) && press.holdAction == ACTION_UNASSIGNED) {
        repeatPressAction(press)
      }
    }
    return true
  }

  private fun onUp(press: Press, cancelled: Boolean): Boolean {
    if (!press.active) return false
    val service = press.service
    if (cancelled || service == null || contextChanged(service, press)) abort(press)
    press.holdRunnable?.let(handler::removeCallbacks)
    press.holdRunnable = null
    press.active = false
    if (!press.aborted && !press.holdFired && !press.singleFired) {
      if (press.multi) {
        press.tapCount++
        if (press.tapCount == 4) finishTaps(press)
        else {
          val runnable = Runnable { finishTaps(press) }
          press.tapRunnable = runnable
          handler.postDelayed(runnable, MULTI_PRESS_MS)
        }
      } else runPressAction(press)
    }
    return true
  }

  private fun fireHold(press: Press) {
    val service = press.service ?: return
    if (!press.active || press.aborted || press.holdFired) return
    clearTaps(press)
    press.holdFired = true
    if (press.holdAction == ACTION_UNASSIGNED) runPressAction(press)
    else runAction(service, press.holdAction, press.touching)
  }

  private fun finishTaps(press: Press) {
    val count = press.tapCount
    clearTaps(press)
    val service = press.service ?: return
    if (count == 0 || contextChanged(service, press)) return
    if (count == 1) runPressAction(press)
    else {
      val prefs = SharedPreferencesUtils.getSharedPreferences(service)
      val action = prefs.getString(multiKey(press.isUp, count), ACTION_UNASSIGNED) ?: ACTION_UNASSIGNED
      runAction(service, action, press.touching)
    }
  }

  private fun runPressAction(press: Press) {
    val service = press.service ?: return
    if (press.pressUse != TalkBackService.VOLUME_KEY_CHANGES_VOLUME) {
      service.performVolumeKeyUse(press.pressUse, press.isUp)
    } else if (!press.managed) {
      service.adjustUnmanagedVolumeKey(press.isUp, press.touching)
    } else runAction(service, press.pressAction, press.touching)
  }

  private fun repeatPressAction(press: Press) {
    val service = press.service ?: return
    if (press.pressUse != TalkBackService.VOLUME_KEY_CHANGES_VOLUME) {
      service.performVolumeKeyUse(press.pressUse, press.isUp)
    } else if (!press.managed) {
      service.adjustUnmanagedVolumeKey(press.isUp, press.touching)
    } else if (press.pressAction == ACTION_INCREASE_CONTEXTUAL_VOLUME ||
      press.pressAction == ACTION_DECREASE_CONTEXTUAL_VOLUME) {
      service.adjustContextualVolume(press.pressAction == ACTION_INCREASE_CONTEXTUAL_VOLUME, press.touching)
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

  private fun clearTaps(press: Press) {
    press.tapRunnable?.let(handler::removeCallbacks)
    press.tapRunnable = null
    press.tapCount = 0
  }

  private fun abort(press: Press) {
    press.aborted = true
    press.holdRunnable?.let(handler::removeCallbacks)
    press.holdRunnable = null
    clearTaps(press)
  }

  @JvmStatic
  fun reset() {
    listOf(upPress, downPress).forEach {
      abort(it)
      it.active = false
      it.service = null
    }
  }

  private fun contextChanged(service: TalkBackService, press: Press): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    return prefs.getBoolean(KEY_MANAGED, false) != press.managed ||
      prefs.getBoolean(KEY_MULTI_ENABLED, false) != press.multi ||
      service.isPhoneCallActive() || isAlarmRinging(service) ||
      isKeyguardLocked(service) != press.locked
  }

  private fun isKeyguardLocked(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

  private fun isAlarmRinging(context: Context): Boolean =
    AudioPlaybackMonitor(context).isPlaybackSourceActive(AudioPlaybackMonitor.PlaybackSource.USAGE_ALARM)

  fun multiKey(isUp: Boolean, count: Int): String =
    "pref_volume_${if (isUp) "up" else "down"}_${count}_press_action"

  private fun pressKey(isUp: Boolean): String = if (isUp) KEY_UP_PRESS_ACTION else KEY_DOWN_PRESS_ACTION
  private fun holdKey(isUp: Boolean): String = if (isUp) KEY_UP_HOLD_ACTION else KEY_DOWN_HOLD_ACTION
  private fun holdLockedKey(isUp: Boolean): String =
    if (isUp) KEY_UP_HOLD_LOCKED_ACTION else KEY_DOWN_HOLD_LOCKED_ACTION
  private fun contextualAction(isUp: Boolean): String =
    if (isUp) ACTION_INCREASE_CONTEXTUAL_VOLUME else ACTION_DECREASE_CONTEXTUAL_VOLUME
  private fun defaultPressAction(isUp: Boolean): String = contextualAction(isUp)
  private fun defaultHoldAction(isUp: Boolean, locked: Boolean): String =
    if (locked && isUp) ACTION_TOGGLE_STOPWATCH else ACTION_UNASSIGNED

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
