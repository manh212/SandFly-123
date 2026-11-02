package com.google.android.accessibility.talkback.keyboard

import android.view.InputDevice
import android.view.KeyEvent
import com.google.android.accessibility.talkback.TalkBackService
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardCapsBridgeTest {
  private class RecordingService : TalkBackService() {
    val received = ArrayList<KeyEvent>()
    var consume = false
    override fun replayKeyboardSchemeEvent(event: KeyEvent): Boolean {
      received += KeyEvent(event)
      return consume
    }
    override fun announceKeyboardBridgeFailure() {}
  }

  private class Backend : KeyboardCapsBridge.Backend {
    override var isAllowed = true
    var holders = 0
    var deferAvailability = false
    var availability: ((Boolean) -> Unit)? = null
    val injections = ArrayList<List<KeyEvent>>()
    val pending = ArrayDeque<(Boolean) -> Unit>()
    override fun hold() { holders++ }
    override fun release() { holders-- }
    override fun available(onResult: (Boolean) -> Unit) {
      if (deferAvailability) availability = onResult else onResult(true)
    }
    override fun inject(events: Array<KeyEvent>, onResult: (Boolean) -> Unit) {
      injections += events.toList()
      pending.addLast(onResult)
    }
    fun complete(worked: Boolean = true) = pending.removeFirst()(worked)
  }

  private val service = RecordingService()
  private val backend = Backend()
  private val bridge = KeyboardCapsBridge(service, backend) { true }

  private fun key(action: Int, code: Int, time: Long, caps: Boolean = true): KeyEvent =
    KeyEvent(time, time, action, code, 0, if (caps) KeyEvent.META_CAPS_LOCK_ON else 0, 3, 0, 0, InputDevice.SOURCE_KEYBOARD)

  @Test fun correctionUsesTheSameKeyboardAndBypassesScreenReaderProcessing() {
    bridge.prepare()
    bridge.restore(3, false, true) {}
    val chord = backend.injections.single()
    assertEquals(listOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT), chord.map { it.keyCode })
    assertTrue(chord.all { it.deviceId == 3 && KeyboardCapsBridge.isInjected(it) })
    backend.complete()
    assertTrue(bridge.isReady)
  }

  @Test fun queuedTypingIsReplayedInOrderWithRestoredCapsMetadata() {
    bridge.prepare()
    bridge.restore(3, false, true) {}
    assertTrue(bridge.filter(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 100)))
    assertTrue(bridge.filter(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 110)))
    assertEquals(1, backend.injections.size)
    backend.complete()
    val down = backend.injections[1].single()
    assertEquals(KeyEvent.ACTION_DOWN, down.action)
    assertFalse(down.isCapsLockOn)
    assertTrue(KeyboardCapsBridge.isInjected(down))
    backend.complete()
    val up = backend.injections[2].single()
    assertEquals(KeyEvent.ACTION_UP, up.action)
    assertFalse(up.isCapsLockOn)
    backend.complete()
    assertFalse(bridge.filter(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 200)))
    assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), service.received.map { it.action })
  }

  @Test fun consumedQueuedShortcutsAreNotInjectedIntoTheApplication() {
    service.consume = true
    bridge.prepare()
    bridge.restore(3, false, true) {}
    bridge.filter(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T, 100))
    bridge.filter(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T, 110))
    backend.complete()
    assertEquals(1, backend.injections.size)
    assertEquals(2, service.received.size)
  }

  @Test fun shutdownWaitsForQueuedKeysBeforeReleasingTheBackend() {
    bridge.prepare()
    bridge.restore(3, false, true) {}
    bridge.filter(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 100))
    bridge.close()
    assertEquals(1, backend.holders)
    backend.complete()
    assertEquals(1, backend.holders)
    backend.complete()
    assertEquals(0, backend.holders)
    assertFalse(bridge.isReady)
  }

  @Test fun failureDoesNotReportACapsChangeOrKeepSwallowingKeys() {
    var result = true
    bridge.prepare()
    bridge.restore(3, false, true) { result = it }
    backend.complete(false)
    assertFalse(result)
    assertFalse(bridge.isReady)
    assertFalse(bridge.filter(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 100)))
  }

  @Test fun failedCorrectionPreservesOriginalTypingMetadata() {
    bridge.prepare()
    bridge.restore(3, false, true) {}
    bridge.filter(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 100, caps = true))
    backend.complete(false)
    assertTrue(backend.injections[1].single().isCapsLockOn)
    backend.complete()
  }

  @Test fun captureWaitsUntilTheBridgeIsReady() {
    backend.deferAvailability = true
    var result: Boolean? = null
    bridge.prepare { result = it }
    assertNull(result)
    assertFalse(bridge.isReady)
    backend.availability!!(true)
    assertEquals(true, result)
    assertTrue(bridge.isReady)
  }
}
