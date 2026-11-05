package com.google.android.accessibility.talkback.keyboard

import android.os.Bundle
import android.view.KeyEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import java.util.function.Consumer

class EditableSelectionKeys {
  private val consumed = HashSet<Pair<Int, Int>>()

  fun reset() = consumed.clear()

  fun handle(
    event: KeyEvent,
    input: AccessibilityNodeInfoCompat?,
    unsupported: Consumer<Int>,
  ): Boolean {
    val key = event.deviceId to event.keyCode
    if (event.action == KeyEvent.ACTION_UP) return consumed.remove(key)
    if (event.action != KeyEvent.ACTION_DOWN) return false
    if (!event.isCtrlPressed || !event.isShiftPressed || event.isAltPressed || event.isMetaPressed) {
      return key in consumed
    }
    val granularity = when (event.keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD
      KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE
      else -> return false
    }
    if (input == null || !input.isFocused || !input.isEditable || !input.isEnabled) return key in consumed
    val next = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
    val arguments = Bundle().apply {
      putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
      putBoolean(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
    }
    val action = if (next) AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
      else AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
    val supported = input.movementGranularities and granularity != 0
    if (supported && input.performAction(action, arguments)) {
      consumed += key
      return true
    }
    if (granularity == AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD && key !in consumed) return false
    if (!supported && event.repeatCount == 0) unsupported.accept(R.string.selection_line_unavailable)
    consumed += key
    return true
  }
}
