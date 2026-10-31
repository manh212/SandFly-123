package com.google.android.accessibility.talkback.monitor

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
class FullScreenMonitorTest {
  private val context = RuntimeEnvironment.getApplication()

  @Test
  fun selectedAppsStayMonitoredWhenManualModeTurnsOff() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit()
      .putBoolean(FullScreenMonitor.KEY_MANUAL, false)
      .putStringSet(FullScreenMonitor.KEY_APPS, setOf("com.example.chat"))
      .commit()
    assertTrue(FullScreenMonitor.enabled(prefs, "com.example.chat"))
    assertFalse(FullScreenMonitor.enabled(prefs, "com.example.other"))
    assertTrue(FullScreenMonitor.toggle(context))
    assertTrue(FullScreenMonitor.enabled(prefs, "com.example.other"))
    assertFalse(FullScreenMonitor.toggle(context))
    assertTrue(FullScreenMonitor.enabled(prefs, "com.example.chat"))
    assertFalse(FullScreenMonitor.enabled(prefs, "com.example.other"))
  }
}
