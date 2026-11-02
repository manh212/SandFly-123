package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsLockPressStateTest {
  private val state = CapsLockPressState()
  private fun event(action: Int, time: Long, key: Int = KeyEvent.KEYCODE_CAPS_LOCK, device: Int = 1, caps: Boolean = false, repeat: Int = 0): CapsLockPressState.Release? =
    state.onKeyEvent(KeyEvent(time, time, action, key, repeat, if (caps) KeyEvent.META_CAPS_LOCK_ON else 0, device, 0))

  @Test fun singlePressRestoresNativeStateAndReportsOriginalState() {
    event(KeyEvent.ACTION_DOWN, 100)
    assertTrue(state.isHeld(1))
    assertEquals(CapsLockPressState.Release(true, true, false), event(KeyEvent.ACTION_UP, 110, caps = true))
    assertFalse(state.isHeld(1))
  }

  @Test fun doublePressLeavesSecondNativeToggleAndReportsNewState() {
    event(KeyEvent.ACTION_DOWN, 100)
    event(KeyEvent.ACTION_UP, 110, caps = true)
    event(KeyEvent.ACTION_DOWN, 200)
    assertEquals(CapsLockPressState.Release(false, true, true), event(KeyEvent.ACTION_UP, 210, caps = true))
  }

  @Test fun initiallyEnabledCapsCanBeTurnedOff() {
    event(KeyEvent.ACTION_DOWN, 100, caps = true)
    assertEquals(CapsLockPressState.Release(true, true, true), event(KeyEvent.ACTION_UP, 110))
    event(KeyEvent.ACTION_DOWN, 200, caps = true)
    assertEquals(CapsLockPressState.Release(false, true, false), event(KeyEvent.ACTION_UP, 210))
  }

  @Test fun chordNeverAnnouncesOrTogglesCaps() {
    event(KeyEvent.ACTION_DOWN, 100)
    event(KeyEvent.ACTION_DOWN, 120, KeyEvent.KEYCODE_M)
    event(KeyEvent.ACTION_UP, 130, KeyEvent.KEYCODE_M)
    assertEquals(CapsLockPressState.Release(true, false, false), event(KeyEvent.ACTION_UP, 140, caps = true))
    event(KeyEvent.ACTION_DOWN, 200)
    assertEquals(CapsLockPressState.Release(true, true, false), event(KeyEvent.ACTION_UP, 210, caps = true))
  }

  @Test fun repeatIsNotASecondPress() {
    event(KeyEvent.ACTION_DOWN, 100)
    event(KeyEvent.ACTION_DOWN, 120, repeat = 1)
    assertEquals(CapsLockPressState.Release(true, true, false), event(KeyEvent.ACTION_UP, 130, caps = true))
  }

  @Test fun otherKeyAndDifferentDeviceDoNotFormDoublePress() {
    event(KeyEvent.ACTION_DOWN, 100)
    event(KeyEvent.ACTION_UP, 110)
    event(KeyEvent.ACTION_DOWN, 120, KeyEvent.KEYCODE_A)
    event(KeyEvent.ACTION_DOWN, 200)
    assertTrue(event(KeyEvent.ACTION_UP, 210)!!.undoNativeToggle)
    event(KeyEvent.ACTION_DOWN, 250, device = 2)
    assertTrue(event(KeyEvent.ACTION_UP, 260, device = 2)!!.undoNativeToggle)
  }

  @Test fun timeoutAndThirdTapStartNewPair() {
    event(KeyEvent.ACTION_DOWN, 100)
    event(KeyEvent.ACTION_UP, 110)
    event(KeyEvent.ACTION_DOWN, 510)
    assertTrue(event(KeyEvent.ACTION_UP, 520)!!.undoNativeToggle)
    event(KeyEvent.ACTION_DOWN, 600)
    assertFalse(event(KeyEvent.ACTION_UP, 610)!!.undoNativeToggle)
    event(KeyEvent.ACTION_DOWN, 650, caps = true)
    assertEquals(CapsLockPressState.Release(true, true, true), event(KeyEvent.ACTION_UP, 660))
  }
}
