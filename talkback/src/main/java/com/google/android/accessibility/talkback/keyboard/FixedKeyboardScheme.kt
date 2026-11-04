package com.google.android.accessibility.talkback.keyboard

import android.content.Context
import android.view.KeyEvent
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.gesture.AppGestures
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.ServiceKeyEventListener

class FixedKeyboardScheme @JvmOverloads constructor(
  private val context: Context,
  private val manager: KeyComboManager,
  private val physicalKeyboard: (KeyEvent) -> Boolean = { KeyboardCapsBridge.isPhysical(it) },
) {
  private val bridge = (context as? TalkBackService)?.let { KeyboardCapsBridge(it) }
  private val caps = CapsLockPressState()
  private val passedCaps = HashSet<Int>()
  private val nativeCapsHeld = HashSet<Int>()
  private val consumedCaps = HashSet<Int>()
  private val recognizer = SchemeKeyRecognizer(object : SchemeKeyRecognizer.Callbacks {
    override fun canHandle(combo: KeyCombo): Boolean {
      val app = AppGestures.keys().firstOrNull { it.first == combo }
      return if (app != null) app.second != context.getString(R.string.shortcut_value_unassigned)
      else manager.keyComboModel.keyComboMap.values.any { it == combo }
    }

    override fun isEditingText(): Boolean = manager.isKeyboardInputEditable

    override fun perform(combo: KeyCombo) {
      manager.performSchemeShortcut(combo)
    }
  })

  fun prepare() = bridge?.prepare()

  fun isInjected(event: KeyEvent): Boolean = bridge?.isInjected(event) == true

  fun prepareForCapture(onReady: java.util.function.Consumer<Boolean>) {
    if (bridge == null) onReady.accept(false) else bridge.prepare { onReady.accept(it) }
  }

  fun filter(event: KeyEvent): Boolean {
    if (bridge?.filter(event) == true) return true
    if (physicalKeyboard(event) && event.keyCode != KeyEvent.KEYCODE_CAPS_LOCK) {
      caps.onKeyEvent(event)
    }
    return false
  }

  fun reset() {
    recognizer.cancelPending()
    if (consumedCaps.isEmpty()) caps.reset()
  }

  fun hasHeldCaps(deviceId: Int): Boolean = deviceId in consumedCaps

  fun cancelPending() = recognizer.cancelPending()

  fun close() = bridge?.close()

  fun onKeyEvent(event: KeyEvent, id: EventId?, delegate: ServiceKeyEventListener?, canAct: Boolean): Boolean {
    bridge?.prepare()
    val physical = physicalKeyboard(event)
    val isCaps = event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK
    if (isCaps && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) recognizer.cancelPending()
    if (isCaps) manager.trackSandFlyKey(event)
    if (physical && isCaps && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      if (bridge?.isReady == true) consumedCaps.add(event.deviceId) else passedCaps.add(event.deviceId)
    }
    if (physical && isCaps && event.deviceId in passedCaps) {
      if (event.action == KeyEvent.ACTION_DOWN) nativeCapsHeld.add(event.deviceId)
      if (event.action == KeyEvent.ACTION_UP) {
        passedCaps.remove(event.deviceId)
        nativeCapsHeld.remove(event.deviceId)
      }
      delegate?.onKeyEvent(event, id)
      return true
    }
    val acceptedCaps = physical && isCaps && event.deviceId in consumedCaps
    val release = if (physical && (bridge?.isReady == true || acceptedCaps)) caps.onKeyEvent(event) else null
    val captured = delegate?.onKeyEvent(event, id) == true
    if (isCaps && physical) {
      if (event.action == KeyEvent.ACTION_UP) consumedCaps.remove(event.deviceId)
      if (release != null) {
        bridge?.restore(event.deviceId, release.capsOn, release.undoNativeToggle) { worked ->
          if (!worked) {
            caps.reset()
            (context as? TalkBackService)?.announceKeyboardBridgeFailure()
          } else if (release.announce && !captured) {
            (context as? TalkBackService)?.announceKeyboardScheme(
              context.getString(if (release.capsOn) R.string.scheme_caps_on else R.string.scheme_caps_off)
            )
          }
        }
      }
      return captured || acceptedCaps || release != null
    }
    if (captured) return true
    if (!physical) return false
    val held = caps.isHeld(event.deviceId) || event.deviceId in nativeCapsHeld
    if (!canAct) {
      recognizer.cancelPending()
      return event.action == KeyEvent.ACTION_UP && recognizer.onKeyEvent(event, held)
    }
    return recognizer.onKeyEvent(event, held)
  }
}
