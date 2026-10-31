package com.google.android.accessibility.talkback.preference.base

import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.ItemOrder
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuControlsResetTest {
  @Test
  fun resettingMenusRestoresMembershipAndOrderWithoutClearingUnrelatedSettings() {
    val context = RuntimeEnvironment.getApplication()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val menuKey = context.getString(R.string.pref_show_context_menu_read_from_top_setting_key)
    val selectorKey = context.getString(R.string.pref_selector_actions_key)
    val unrelatedKey = "preserve_unrelated_setting"
    prefs.edit()
      .putBoolean(menuKey, false)
      .putBoolean(selectorKey, false)
      .putString(ItemOrder.SANDFLY_MENU_KEY, "custom")
      .putString(ItemOrder.READING_CONTROLS_KEY, "custom")
      .putString(unrelatedKey, "keep")
      .commit()

    MenuControlsReset.reset(context)

    assertFalse(prefs.contains(menuKey))
    assertFalse(prefs.contains(selectorKey))
    assertFalse(prefs.contains(ItemOrder.SANDFLY_MENU_KEY))
    assertFalse(prefs.contains(ItemOrder.READING_CONTROLS_KEY))
    assertTrue(prefs.getString(unrelatedKey, null) == "keep")
  }
}
