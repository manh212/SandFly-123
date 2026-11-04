package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixedKeyboardSchemeFallbackTest {
  class KeyboardService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
  }

  private class RecordingManager(service: AccessibilityService, private val model: KeyComboModel) :
      KeyComboManager(
        service, Pipeline.FeedbackReturner { false }, null, null, null, null, null,
        null, null, null, null, AccessibilityFocusMonitor(service, null, null),
        null, null, null,
      ) {
    val performed = ArrayList<KeyCombo>()
    var editing = false
    override fun getKeyComboModel() = model
    override fun isKeyboardInputEditable() = editing
    override fun performSchemeShortcut(combo: KeyCombo) { performed += combo }
    override fun trackSandFlyKey(event: KeyEvent) {}
  }

  @Test fun capsChordAndPlainShortcutWorkWithoutBridgeOrPermission() {
    val context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
    val model = NewKeyComboModel(context, CustomKeymaps.createBlank(context, "Không Shizuku"))
    val plain = KeyCombo(0, 0, KeyEvent.KEYCODE_H, false)
    val caps = KeyCombo(0, 0, KeyEvent.KEYCODE_T, true)
    model.saveKeyCombo("command_shortcut_TEST_PLAIN", plain)
    model.saveKeyCombo("command_shortcut_TEST_CAPS", caps)
    val controller = Robolectric.buildService(KeyboardService::class.java).create()
    val manager = RecordingManager(controller.get(), model)
    val scheme = FixedKeyboardScheme(context, manager) { true }
    fun key(action: Int, code: Int): Boolean = scheme.onKeyEvent(
      KeyEvent(action, code), EVENT_ID_UNTRACKED, null, true)
    try {
      assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H))
      assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H))
      assertEquals(listOf(plain), manager.performed)
      assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK))
      assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T))
      assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAPS_LOCK))
      assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T))
      assertEquals(listOf(plain, caps), manager.performed)
      manager.editing = true
      assertFalse(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H))
      assertFalse(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H))
      assertEquals(listOf(plain, caps), manager.performed)
    } finally {
      manager.shutdown()
      controller.destroy()
    }
  }
}
