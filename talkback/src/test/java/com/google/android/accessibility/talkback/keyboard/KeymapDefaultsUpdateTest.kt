package com.google.android.accessibility.talkback.keyboard

import android.content.Context
import android.view.KeyEvent
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeymapDefaultsUpdateTest {
  private lateinit var context: Context

  @Before
  fun setup() {
    context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
  }

  @Test
  fun migratesOldDefaultsButPreservesCustomAndExplicitlyUnassigned() {
    val keymap = Keymap.NVDA
    val saved = KeyComboPersister(context, keymap.persisterPrefix(keymap.nativeKey))
    val next = context.getString(R.string.keycombo_shortcut_navigate_next_default)
    val previous = context.getString(R.string.keycombo_shortcut_navigate_previous_default)
    val line = context.getString(R.string.keycombo_shortcut_navigate_next_line)
    val custom = KeyCombo(KeyEvent.META_CTRL_ON, 0, KeyEvent.KEYCODE_F9, true)
    saved.saveKeyCombo(next, KeyCombo(KeyEvent.META_SHIFT_ON, 0, KeyEvent.KEYCODE_DPAD_RIGHT, true))
    saved.saveKeyCombo(previous, custom)
    saved.saveKeyCombo(line, KeyCombo())
    val model = NewKeyComboModel(context, keymap)
    assertEquals(KeyCombo(0, 0, KeyEvent.KEYCODE_DPAD_DOWN, false), model.getKeyComboForKey(next))
    assertEquals(custom, model.getKeyComboForKey(previous))
    assertEquals(KeyCombo(), model.getKeyComboForKey(line))
    assertEquals(custom, NewKeyComboModel(context, keymap).getKeyComboForKey(previous))
  }

  @Test
  fun desktopLayoutsStartWithConfigurableDoubleModifier() {
    val toggle = context.getString(R.string.keycombo_shortcut_other_toggle_browse_mode_extra)
    for (keymap in listOf(Keymap.NVDA, Keymap.JAWS, Keymap.ORCA)) {
      val model = NewKeyComboModel(context, keymap)
      assertTrue(model.getKeyComboForKey(toggle).isSandFlyKeyPressedTwice)
      model.saveKeyCombo(toggle, KeyCombo())
      assertEquals(KeyCombo(), NewKeyComboModel(context, keymap).getKeyComboForKey(toggle))
    }
  }
}
