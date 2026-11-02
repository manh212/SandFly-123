package com.google.android.accessibility.talkback.keyboard

import android.content.Context
import android.view.KeyEvent
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.gesture.AppGestures
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.ServiceKeyEventListener

class FixedKeyboardScheme(private val context: Context, private val manager: KeyComboManager) {
  private val bridge = (context as? TalkBackService)?.let { KeyboardCapsBridge(it) }
  private val caps = CapsLockPressState()
  private val passedCaps = HashSet<Int>()
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

  fun filter(event: KeyEvent): Boolean {
    if (bridge?.filter(event) == true) return true
    if (KeyboardCapsBridge.isPhysical(event) && event.keyCode != KeyEvent.KEYCODE_CAPS_LOCK) {
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
    val physical = KeyboardCapsBridge.isPhysical(event)
    val isCaps = event.keyCode == KeyEvent.KEYCODE_CAPS_LOCK
    if (isCaps && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) recognizer.cancelPending()
    if (isCaps) manager.trackSandFlyKey(event)
    if (physical && isCaps && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      if (bridge?.isReady == true) consumedCaps.add(event.deviceId) else passedCaps.add(event.deviceId)
    }
    if (physical && isCaps && event.deviceId in passedCaps) {
      if (event.action == KeyEvent.ACTION_UP) {
        passedCaps.remove(event.deviceId)
        (context as? TalkBackService)?.announceKeyboardBridgeFailure()
      }
      return false
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
      if (bridge?.isReady != true && release == null && event.action == KeyEvent.ACTION_UP) {
        (context as? TalkBackService)?.announceKeyboardBridgeFailure()
      }
      return captured || acceptedCaps || release != null
    }
    if (captured) return true
    if (!physical) return false
    if (!canAct) {
      recognizer.cancelPending()
      return event.action == KeyEvent.ACTION_UP && recognizer.onKeyEvent(event, caps.isHeld(event.deviceId))
    }
    return recognizer.onKeyEvent(event, caps.isHeld(event.deviceId))
  }
}
