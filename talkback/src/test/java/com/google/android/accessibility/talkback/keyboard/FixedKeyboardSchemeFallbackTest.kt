package com.google.android.accessibility.talkback.keyboard

import android.view.KeyEvent
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixedKeyboardSchemeFallbackTest {
  @Test fun capsChordAndPlainShortcutWorkWithoutBridgeOrPermission() {
    val context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
    val model = NewKeyComboModel(context, CustomKeymaps.createBlank(context, "Không Shizuku"))
    val plain = KeyCombo(0, 0, KeyEvent.KEYCODE_H, false)
    val caps = KeyCombo(0, 0, KeyEvent.KEYCODE_T, true)
    model.saveKeyCombo("command_shortcut_TEST_PLAIN", plain)
    model.saveKeyCombo("command_shortcut_TEST_CAPS", caps)
    val manager = mock(KeyComboManager::class.java)
    `when`(manager.keyComboModel).thenReturn(model)
    val scheme = FixedKeyboardScheme(context, manager) { true }
    fun key(action: Int, code: Int): Boolean = scheme.onKeyEvent(
      KeyEvent(action, code), EVENT_ID_UNTRACKED, null, true)
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H))
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H))
    verify(manager).performSchemeShortcut(plain)
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK))
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T))
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAPS_LOCK))
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_T))
    verify(manager).performSchemeShortcut(caps)
    `when`(manager.isKeyboardInputEditable).thenReturn(true)
    assertFalse(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H))
    assertFalse(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H))
    verify(manager, times(1)).performSchemeShortcut(plain)
  }
}
