package com.google.android.accessibility.talkback.keyboard

import android.content.Context
import android.view.KeyEvent
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.commands.Commands
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlankKeyboardSchemeTest {
  private lateinit var context: Context
  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
  }

  @Test fun newSchemeHasNoAssignmentsAndCapsIsFixed() {
    val scheme = CustomKeymaps.createBlank(context, "Trống")
    val model = NewKeyComboModel(context, scheme)
    assertTrue(scheme.fixedKeys)
    assertEquals(SandFlyKey.CAPS_LOCK, model.sandFlyKey)
    assertTrue(model.keyComboMap.values.all { it.keyComboCode == 0L })
    SharedPreferencesUtils.getSharedPreferences(context).edit()
      .putString(scheme.sandFlyKeyPref, context.getString(SandFlyKey.META.valueRes)).commit()
    assertEquals(SandFlyKey.CAPS_LOCK, scheme.sandFlyKey(context))
  }

  @Test fun assignmentsSurviveReloadAndCanBeRemoved() {
    val scheme = CustomKeymaps.createBlank(context, "Ứng dụng")
    val key = Commands.shortcutKey("OPEN_APP:com.example/com.example.Main")
    val combo = KeyCombo(KeyEvent.META_CTRL_ON, 0, KeyEvent.KEYCODE_T, false)
    NewKeyComboModel(context, scheme).saveKeyCombo(key, combo)
    val loaded = CustomKeymaps.of(context, scheme.value)!!
    assertTrue(loaded.fixedKeys)
    val model = NewKeyComboModel(context, loaded)
    assertEquals(combo, model.getKeyComboForKey(key))
    model.clearKeyCombo(key)
    assertEquals(0L, NewKeyComboModel(context, loaded).getKeyComboForKey(key).keyComboCode)
    CustomKeymaps.delete(context, loaded)
    assertNull(CustomKeymaps.of(context, loaded.value))
  }

  @Test fun ctrlAndCapsAloneCannotBeOverwritten() {
    val model = NewKeyComboModel(context, CustomKeymaps.createBlank(context, "Mới"))
    assertFalse(model.isEligibleKeyCombo(KeyCombo(0, 0, KeyEvent.KEYCODE_CTRL_LEFT, false)))
    assertFalse(model.isEligibleKeyCombo(KeyCombo(0, 0, KeyEvent.KEYCODE_CAPS_LOCK, false)))
    assertTrue(model.isEligibleKeyCombo(KeyCombo(0, 0, KeyEvent.KEYCODE_DPAD_UP, true)))
    assertTrue(model.isEligibleKeyCombo(KeyCombo(KeyEvent.META_CTRL_ON, 0, KeyEvent.KEYCODE_T, false)))
  }

  @Test fun legacyCustomSchemeRetainsItsAssignmentsAndModifier() {
    val key = context.getString(R.string.keycombo_shortcut_perform_click)
    val combo = KeyCombo(0, 0, KeyEvent.KEYCODE_F2, false)
    val legacy = CustomKeymaps.create(context, "Cũ", false, SandFlyKey.INSERT, mapOf(key to combo))
    val loaded = CustomKeymaps.of(context, legacy.value)!!
    assertFalse(loaded.fixedKeys)
    assertEquals(SandFlyKey.INSERT, loaded.sandFlyKey(context))
    assertEquals(combo, NewKeyComboModel(context, loaded).getKeyComboForKey(key))
  }
}
