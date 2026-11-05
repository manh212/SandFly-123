package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.function.Consumer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditableSelectionKeysTest {
  private val keys = EditableSelectionKeys()
  private val warnings = ArrayList<Int>()
  private val warn = Consumer<Int> { warnings += it }

  private fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, meta: Int = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) =
    KeyEvent(10, 10, action, code, 0, meta, 3, 0)

  private fun editor(granularities: Int) = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain().apply {
    isEditable = true
    isEnabled = true
    isFocused = true
    movementGranularities = granularities
  })

  @Test fun extendsSelectionWithWordAndDisplayedLineActions() {
    val field = editor(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
    val requested = ArrayList<Pair<Int, Int>>()
    shadowOf(field.unwrap()).setOnPerformActionListener { action, arguments ->
      assertTrue(arguments.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN))
      requested += action to arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT)
      true
    }
    assertTrue(keys.handle(event(KeyEvent.KEYCODE_DPAD_RIGHT), field, warn))
    assertTrue(keys.handle(event(KeyEvent.KEYCODE_DPAD_UP), field, warn))
    assertEquals(listOf(
      AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD,
      AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
    ), requested)
    assertTrue(warnings.isEmpty())
    assertTrue(keys.handle(event(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP, 0), null, warn))
    assertFalse(keys.handle(event(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP, 0), null, warn))
  }

  @Test fun unsupportedDisplayedLinesNeverFallThroughToParagraphSelection() {
    assertTrue(keys.handle(event(KeyEvent.KEYCODE_DPAD_DOWN), editor(0), warn))
    assertEquals(1, warnings.size)
    assertTrue(keys.handle(event(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP), null, warn))
  }

  @Test fun plainArrowsAndNonEditableFocusAreNotCaptured() {
    assertFalse(keys.handle(event(KeyEvent.KEYCODE_DPAD_LEFT, meta = 0), editor(7), warn))
    assertFalse(keys.handle(event(KeyEvent.KEYCODE_DPAD_LEFT), null, warn))
    val field = editor(7).apply { isEditable = false }
    assertFalse(keys.handle(event(KeyEvent.KEYCODE_DPAD_LEFT), field, warn))
    assertTrue(warnings.isEmpty())
  }
}
