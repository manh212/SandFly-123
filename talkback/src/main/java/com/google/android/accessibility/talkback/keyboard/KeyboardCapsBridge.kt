package com.google.android.accessibility.talkback.keyboard

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.shizuku.ShizukuShell
import java.util.ArrayDeque

class KeyboardCapsBridge(private val service: TalkBackService) {
  private var held = false
  private var preparing = false
  private var ready = false
  private var busy = false
  private var replaying = false
  private var closed = false
  private val queued = ArrayDeque<KeyEvent>()
  private var logicalCaps: Boolean? = null

  val isReady: Boolean
    get() = ready && ShizukuShell.isAllowed()

  fun prepare() {
    if (closed || preparing || isReady || !ShizukuShell.isAllowed()) return
    if (!held) {
      held = true
      ShizukuShell.hold(service)
    }
    preparing = true
    ShizukuShell.keyboardAvailable(service) {
      preparing = false
      ready = it && !closed
    }
  }

  fun filter(event: KeyEvent): Boolean {
    if (!busy || replaying || !isPhysical(event)) return false
    queued.addLast(KeyEvent(event))
    return true
  }

  fun restore(deviceId: Int, capsOn: Boolean, undo: Boolean, onDone: (Boolean) -> Unit) {
    logicalCaps = capsOn
    if (!undo) {
      onDone(true)
      return
    }
    busy = true
    val now = SystemClock.uptimeMillis()
    val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    val events = arrayOf(
      injected(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, meta, deviceId),
      injected(now, now + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, meta or alt, deviceId),
      injected(now, now + 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, meta, deviceId),
      injected(now, now + 3, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0, deviceId),
    )
    ShizukuShell.injectKeyboardEvents(service, events) { worked ->
      ready = worked && !closed
      busy = false
      onDone(worked)
      drain()
    }
  }

  private fun drain() {
    while (!busy && queued.isNotEmpty()) {
      val original = queued.removeFirst()
      val caps = logicalCaps
      val event = if (caps == null) original else KeyEvent(
        original.downTime, original.eventTime, original.action, original.keyCode,
        original.repeatCount,
        (original.metaState and KeyEvent.META_CAPS_LOCK_ON.inv()) or if (caps) KeyEvent.META_CAPS_LOCK_ON else 0,
        original.deviceId, original.scanCode, original.flags, original.source,
      )
      replaying = true
      val consumed = try {
        service.replayKeyboardSchemeEvent(event)
      } finally {
        replaying = false
      }
      if (!consumed) {
        busy = true
        val forwarded = KeyEvent.changeFlags(event, event.flags or INJECTED_FLAGS)
        ShizukuShell.injectKeyboardEvents(service, arrayOf(forwarded)) { worked ->
          busy = false
          if (!worked && !closed) service.announceKeyboardBridgeFailure()
          drain()
        }
      }
    }
    releaseIfClosed()
  }

  fun close() {
    closed = true
    ready = false
    drain()
    releaseIfClosed()
  }

  private fun releaseIfClosed() {
    if (closed && !busy && queued.isEmpty() && held) {
      held = false
      ShizukuShell.release()
    }
  }

  companion object {
    private const val INJECTED_FLAGS = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE or KeyEvent.FLAG_VIRTUAL_HARD_KEY

    @JvmStatic
    fun isInjected(event: KeyEvent): Boolean = event.flags and INJECTED_FLAGS == INJECTED_FLAGS

    @JvmStatic
    fun isPhysical(event: KeyEvent): Boolean = event.device?.let {
      !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    } == true

    private fun injected(down: Long, time: Long, action: Int, key: Int, meta: Int, device: Int): KeyEvent =
      KeyEvent(down, time, action, key, 0, meta, device, 0, INJECTED_FLAGS, InputDevice.SOURCE_KEYBOARD)
  }
}
