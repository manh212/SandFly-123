package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent

class SchemeKeyRecognizer(private val callbacks: Callbacks) {
  interface Callbacks {
    fun canHandle(combo: KeyCombo): Boolean
    fun isEditingText(): Boolean
    fun perform(combo: KeyCombo)
  }

  private data class Press(val combo: KeyCombo, val confirmed: Boolean, val valid: Boolean = true)
  private val pressed = HashMap<Pair<Int, Int>, Press>()
  private var lastTap: Pair<Int, KeyCombo>? = null
  private var lastRelease = Long.MIN_VALUE

  fun reset() {
    pressed.clear()
    clearTap()
  }

  fun cancelPending() {
    pressed.replaceAll { _, press -> press.copy(valid = false) }
    clearTap()
  }

  fun onKeyEvent(event: KeyEvent, capsHeld: Boolean): Boolean {
    val id = event.deviceId to event.keyCode
    if (event.action == KeyEvent.ACTION_UP) {
      val press = pressed.remove(id) ?: return false
      if (!press.valid || event.isCanceled) {
        clearTap()
        return true
      }
      val plain = isPlain(press.combo)
      if (plain && callbacks.isEditingText()) {
        clearTap()
      } else if (press.confirmed) {
        clearTap()
        callbacks.perform(press.combo)
      } else if (plain) {
        lastTap = event.deviceId to press.combo
        lastRelease = event.eventTime
      }
      return true
    }
    if (event.action != KeyEvent.ACTION_DOWN) return id in pressed
    if (event.repeatCount > 0) return id in pressed
    if (KeyEvent.isModifierKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
      clearTap()
      return false
    }
    val combo = KeyCombo(event.modifiers and KeyCombo.KEY_EVENT_MODIFIER_MASK, 0, event.keyCode, capsHeld)
    val plain = isPlain(combo)
    if ((plain && callbacks.isEditingText()) || !callbacks.canHandle(combo)) {
      clearTap()
      return false
    }
    val elapsed = if (lastTap == null) Long.MAX_VALUE else event.eventTime - lastRelease
    val confirmed = !plain || (lastTap == (event.deviceId to combo) && elapsed in 0 until DOUBLE_PRESS_MS)
    clearTap()
    pressed[id] = Press(combo, confirmed)
    return true
  }

  private fun clearTap() {
    lastTap = null
    lastRelease = Long.MIN_VALUE
  }

  companion object {
    const val DOUBLE_PRESS_MS = 400L

    @JvmStatic
    fun isPlain(combo: KeyCombo): Boolean = !combo.isTriggerModifierUsed && combo.modifiers == 0
  }
}
