package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemeKeyRecognizerTest {
  private var editing = false
  private val actions = ArrayList<KeyCombo>()
  private val recognizer = SchemeKeyRecognizer(object : SchemeKeyRecognizer.Callbacks {
    override fun canHandle(combo: KeyCombo) = combo.keyCode != KeyEvent.KEYCODE_Z
    override fun isEditingText() = editing
    override fun perform(combo: KeyCombo) { actions += combo }
  })

  private fun key(action: Int, code: Int, time: Long, modifiers: Int = 0, repeat: Int = 0, device: Int = 1, flags: Int = 0, caps: Boolean = false): Boolean =
    recognizer.onKeyEvent(KeyEvent(time, time, action, code, repeat, modifiers, device, 0, flags), caps)

  private fun tap(code: Int, time: Long, device: Int = 1) {
    assertTrue(key(KeyEvent.ACTION_DOWN, code, time, device = device))
    assertTrue(key(KeyEvent.ACTION_UP, code, time + 10, device = device))
  }

  @Test fun plainKeyRunsOnceOnEachRelease() {
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H, 100))
    assertTrue(actions.isEmpty())
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H, 110))
    assertEquals(listOf(KeyCombo(0, 0, KeyEvent.KEYCODE_H, false)), actions)
    tap(KeyEvent.KEYCODE_H, 300)
    assertEquals(2, actions.size)
  }

  @Test fun longIntervalDoesNotRequireAnotherPress() {
    tap(KeyEvent.KEYCODE_H, 100)
    tap(KeyEvent.KEYCODE_H, 2000)
    assertEquals(2, actions.size)
  }

  @Test fun repeatsRunOnceAndCancelledPressDoesNotRun() {
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H, 100)
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H, 120, repeat = 1)
    key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H, 150)
    assertEquals(1, actions.size)
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H, 200)
    key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H, 210, flags = KeyEvent.FLAG_CANCELED)
    assertEquals(1, actions.size)
  }

  @Test fun telexDoubleLettersPassWhileEditing() {
    editing = true
    for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_E)) {
      assertFalse(key(KeyEvent.ACTION_DOWN, code, 100))
      assertFalse(key(KeyEvent.ACTION_UP, code, 110))
      assertFalse(key(KeyEvent.ACTION_DOWN, code, 200))
      assertFalse(key(KeyEvent.ACTION_UP, code, 210))
    }
    assertTrue(actions.isEmpty())
  }

  @Test fun modifierChordRunsOnReleaseEvenIfModifierWasReleasedFirst() {
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T, 100, KeyEvent.META_CTRL_ON))
    assertTrue(actions.isEmpty())
    assertFalse(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 110))
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T, 120))
    assertEquals(listOf(KeyCombo(KeyEvent.META_CTRL_ON, 0, KeyEvent.KEYCODE_T, false)), actions)
  }

  @Test fun capsIsHeldRatherThanLockedState() {
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 100, caps = true)
    key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 110, caps = false)
    assertTrue(actions.single().isTriggerModifierUsed)
  }

  @Test fun releaseFromAnotherDeviceCannotRunHeldAssignment() {
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H, 100, device = 1)
    assertFalse(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H, 110, device = 2))
    assertTrue(actions.isEmpty())
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H, 120, device = 1))
    assertEquals(1, actions.size)
  }

  @Test fun focusChangeCancelsWithoutLeakingConsumedRelease() {
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H, 100)
    recognizer.cancelPending()
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H, 110))
    assertTrue(actions.isEmpty())
    tap(KeyEvent.KEYCODE_H, 200)
    assertEquals(1, actions.size)
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T, 250, KeyEvent.META_CTRL_ON)
    recognizer.cancelPending()
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T, 260))
    assertEquals(1, actions.size)
  }

  @Test fun enteringEditorBeforeReleaseCancelsPlainShortcut() {
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H, 100)
    editing = true
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H, 110))
    assertTrue(actions.isEmpty())
  }

  @Test fun modifierAloneCannotBecomeAnAssignment() {
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 100, KeyEvent.META_CTRL_ON)
    key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 110)
    key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK, 200)
    key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAPS_LOCK, 210)
    assertTrue(actions.isEmpty())
  }
}
