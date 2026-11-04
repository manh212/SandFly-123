package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent

class CapsLockPressState {
  private companion object {
    const val DOUBLE_PRESS_MS = 400L
  }

  data class Release(val undoNativeToggle: Boolean, val announce: Boolean, val capsOn: Boolean)
  private data class Device(
    var held: Boolean = false,
    var used: Boolean = false,
    var capsOn: Boolean = false,
    var lastRelease: Long? = null,
    var doublePress: Boolean = false,
  )
  private val devices = HashMap<Int, Device>()

  fun isHeld(deviceId: Int): Boolean = devices[deviceId]?.held == true

  fun reset() = devices.clear()

  fun onKeyEvent(event: KeyEvent): Release? {
    if (event.keyCode != KeyEvent.KEYCODE_CAPS_LOCK) {
      if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        devices[event.deviceId]?.let {
          if (it.held) it.used = true
          it.lastRelease = null
        }
      }
      return null
    }
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      val state = devices.getOrPut(event.deviceId) { Device(capsOn = event.isCapsLockOn) }
      val previous = state.lastRelease
      state.doublePress = previous != null && event.eventTime - previous in 0 until DOUBLE_PRESS_MS
      if (!state.doublePress) state.capsOn = event.isCapsLockOn
      state.held = true
      state.used = false
      state.lastRelease = null
    }
    if (event.action != KeyEvent.ACTION_UP) return null
    val state = devices[event.deviceId] ?: return null
    if (!state.held) return null
    state.held = false
    val toggle = state.doublePress && !state.used && !event.isCanceled
    if (toggle) state.capsOn = !state.capsOn
    state.lastRelease = if (state.used || toggle || event.isCanceled) null else event.eventTime
    return Release(!toggle, !state.used && !event.isCanceled, state.capsOn)
  }
}
