package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent

class SchemeKeyRecognizer(private val callbacks: Callbacks) {
  interface Callbacks {
    fun canHandle(combo: KeyCombo): Boolean
    fun isEditingText(): Boolean
    fun perform(combo: KeyCombo)
  }

  private data class Press(val combo: KeyCombo, val valid: Boolean = true)
  private val pressed = HashMap<Pair<Int, Int>, Press>()

  fun reset() {
    pressed.clear()
  }

  fun cancelPending() {
    pressed.replaceAll { _, press -> press.copy(valid = false) }
  }

  fun onKeyEvent(event: KeyEvent, capsHeld: Boolean): Boolean {
    val id = event.deviceId to event.keyCode
    if (event.action == KeyEvent.ACTION_UP) {
      val press = pressed.remove(id) ?: return false
      if (!press.valid || event.isCanceled) {
        return true
      }
      if (!isPlain(press.combo) || !callbacks.isEditingText()) {
        callbacks.perform(press.combo)
      }
      return true
    }
    if (event.action != KeyEvent.ACTION_DOWN) return id in pressed
    if (event.repeatCount > 0) return id in pressed
    if (KeyEvent.isModifierKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
      return false
    }
    val combo = KeyCombo(event.modifiers and KeyCombo.KEY_EVENT_MODIFIER_MASK, 0, event.keyCode, capsHeld)
    val plain = isPlain(combo)
    if ((plain && callbacks.isEditingText()) || !callbacks.canHandle(combo)) {
      return false
    }
    pressed[id] = Press(combo)
    return true
  }

  companion object {
    @JvmStatic
    fun isPlain(combo: KeyCombo): Boolean = !combo.isTriggerModifierUsed && combo.modifiers == 0
  }
}
