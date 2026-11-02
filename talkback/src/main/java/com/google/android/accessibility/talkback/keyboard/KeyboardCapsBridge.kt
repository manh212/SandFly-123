package com.google.android.accessibility.talkback.keyboard

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.shizuku.ShizukuShell
import java.util.ArrayDeque

class KeyboardCapsBridge @JvmOverloads constructor(
  private val service: TalkBackService,
  private val backend: Backend = ShizukuBackend(service),
  private val physicalKeyboard: (KeyEvent) -> Boolean = { isPhysical(it) },
) {
  interface Backend {
    val isAllowed: Boolean
    fun hold()
    fun release()
    fun available(onResult: (Boolean) -> Unit)
    fun inject(events: Array<KeyEvent>, onResult: (Boolean) -> Unit)
  }

  private class ShizukuBackend(private val service: TalkBackService) : Backend {
    override val isAllowed: Boolean get() = ShizukuShell.isAllowed()
    override fun hold() = ShizukuShell.hold(service)
    override fun release() = ShizukuShell.release()
    override fun available(onResult: (Boolean) -> Unit) = ShizukuShell.keyboardAvailable(service, onResult)
    override fun inject(events: Array<KeyEvent>, onResult: (Boolean) -> Unit) =
      ShizukuShell.injectKeyboardEvents(service, events, onResult)
  }
  private var held = false
  private var preparing = false
  private var ready = false
  private var busy = false
  private var replaying = false
  private var draining = false
  private var closed = false
  private val queued = ArrayDeque<KeyEvent>()
  private val readyCallbacks = ArrayList<(Boolean) -> Unit>()
  private data class InjectionKey(
    val downTime: Long,
    val time: Long,
    val action: Int,
    val key: Int,
    val repeat: Int,
    val device: Int,
    val scanCode: Int,
  )
  private val injectedKeys = HashSet<InjectionKey>()
  private var logicalCaps: Boolean? = null

  private fun injectionKey(event: KeyEvent) = InjectionKey(
    event.downTime, event.eventTime, event.action, event.keyCode, event.repeatCount,
    event.deviceId, event.scanCode,
  )

  fun isInjected(event: KeyEvent): Boolean = injectedKeys.remove(injectionKey(event))

  private fun inject(events: Array<KeyEvent>, onResult: (Boolean) -> Unit) {
    val keys = events.map(::injectionKey).toSet()
    injectedKeys.addAll(keys)
    backend.inject(events) { worked ->
      injectedKeys.removeAll(keys)
      onResult(worked)
    }
  }

  val isReady: Boolean
    get() = ready && backend.isAllowed

  fun prepare() {
    if (!preparing && !isReady) prepare {}
  }

  fun prepare(onResult: (Boolean) -> Unit) {
    if (closed || !backend.isAllowed) {
      onResult(false)
      return
    }
    if (isReady) {
      onResult(true)
      return
    }
    readyCallbacks += onResult
    if (preparing) return
    if (!held) {
      held = true
      backend.hold()
    }
    preparing = true
    backend.available {
      preparing = false
      ready = it && !closed
      val callbacks = ArrayList(readyCallbacks)
      readyCallbacks.clear()
      callbacks.forEach { callback -> callback(isReady) }
    }
  }

  fun filter(event: KeyEvent): Boolean {
    if (!busy || replaying || !physicalKeyboard(event)) return false
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
    inject(events) { worked ->
      ready = worked && !closed
      if (!worked) logicalCaps = null
      busy = false
      onDone(worked)
      drain()
    }
  }

  private fun drain() {
    if (draining) return
    draining = true
    try {
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
          inject(arrayOf(event)) { worked ->
            busy = false
            if (!worked && !closed) service.announceKeyboardBridgeFailure()
            drain()
          }
        }
      }
    } finally {
      draining = false
    }
    releaseIfClosed()
  }

  fun close() {
    closed = true
    ready = false
    val callbacks = ArrayList(readyCallbacks)
    readyCallbacks.clear()
    callbacks.forEach { it(false) }
    drain()
    releaseIfClosed()
  }

  private fun releaseIfClosed() {
    if (closed && !busy && queued.isEmpty() && held) {
      held = false
      backend.release()
    }
  }

  companion object {
    @JvmStatic
    fun isPhysical(event: KeyEvent): Boolean = event.device?.let {
      !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    } == true

    private fun injected(down: Long, time: Long, action: Int, key: Int, meta: Int, device: Int): KeyEvent =
      KeyEvent(down, time, action, key, 0, meta, device, 0, 0, InputDevice.SOURCE_KEYBOARD)
  }
}
