package com.google.android.accessibility.talkback.keyboard

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseModeAppsTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    SharedPreferencesUtils.getSharedPreferences(context).edit().clear().commit()
  }

  @Test
  fun legacyPerAppPreferenceIsMigrated() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().putBoolean(context.getString(R.string.pref_browse_mode_per_app_key), true).commit()
    assertEquals(BrowseModeApps.PER_APP, BrowseModeApps.mode(context))
    assertEquals(BrowseModeApps.PER_APP, prefs.getString(BrowseModeApps.PREF_MODE, null))
  }

  @Test
  fun globalModeKeepsTheExplicitStateAcrossApplications() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().putString(BrowseModeApps.PREF_MODE, BrowseModeApps.ALWAYS).commit()
    assertNull(BrowseModeApps.rememberedGlobal(context))
    BrowseModeApps.rememberGlobal(context, true)
    assertTrue(BrowseModeApps.rememberedGlobal(context) == true)
    BrowseModeApps.remember(context, "com.example.one", false)
    assertNull(BrowseModeApps.remembered(context, "com.example.one"))
    BrowseModeApps.rememberGlobal(context, false)
    assertFalse(BrowseModeApps.rememberedGlobal(context) == true)
  }

  @Test
  fun perAppModePreservesIndependentChoices() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().putString(BrowseModeApps.PREF_MODE, BrowseModeApps.PER_APP).commit()
    BrowseModeApps.remember(context, "com.example.one", true)
    BrowseModeApps.remember(context, "com.example.two", false)
    assertEquals(true, BrowseModeApps.remembered(context, "com.example.one"))
    assertEquals(false, BrowseModeApps.remembered(context, "com.example.two"))
    prefs.edit().putString(BrowseModeApps.PREF_MODE, BrowseModeApps.NONE).commit()
    BrowseModeApps.remember(context, "com.example.one", false)
    assertEquals(true, BrowseModeApps.remembered(context, "com.example.one"))
    assertFalse(BrowseModeApps.isOn(context))
  }
}
