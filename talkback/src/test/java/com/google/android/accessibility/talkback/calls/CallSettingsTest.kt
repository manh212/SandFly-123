package com.google.android.accessibility.talkback.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSettingsTest {
  @Test fun overnightQuietHoursIncludeStartAndExcludeEnd() {
    val settings = CallSettings(quietHours = true)
    assertFalse(settings.isQuiet(22 * 60 + 59))
    assertTrue(settings.isQuiet(23 * 60))
    assertTrue(settings.isQuiet(0))
    assertTrue(settings.isQuiet(5 * 60 + 59))
    assertFalse(settings.isQuiet(6 * 60))
  }

  @Test fun daytimeAndAllDayQuietHoursWork() {
    val settings = CallSettings(quietHours = true, quietStart = 9 * 60, quietEnd = 17 * 60)
    assertFalse(settings.isQuiet(8 * 60))
    assertTrue(settings.isQuiet(12 * 60))
    assertFalse(settings.isQuiet(17 * 60))
    assertTrue(settings.copy(quietEnd = 9 * 60).isQuiet(0))
    assertFalse(settings.copy(quietHours = false).isQuiet(12 * 60))
  }

  @Test fun callSettingsAreIndependentOfNotificationChoices() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().clear().putBoolean("pref_speak_notifications", false)
      .putBoolean("pref_notification_quiet_hours", true)
      .putString("pref_notification_voice_profile", "notification")
      .putBoolean(CallSettings.ANNOUNCE, true)
      .putString(CallSettings.VOICE, "calls").commit()
    val settings = CallSettings.read(prefs)
    assertTrue(settings.announce)
    assertFalse(settings.quietHours)
    assertEquals("calls", settings.voiceProfile)
    assertEquals(0, settings.repeats)
    assertEquals(0, settings.intervalSeconds)
    prefs.edit().clear().commit()
  }

  @Test fun storedNumbersAreBoundedAndBothStorageTypesAreAccepted() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().clear().putString(CallSettings.REPEATS, "200")
      .putInt(CallSettings.INTERVAL, 30).putString(CallSettings.START, "-10").commit()
    val settings = CallSettings.read(prefs)
    assertEquals(10, settings.repeats)
    assertEquals(30, settings.intervalSeconds)
    assertEquals(0, settings.quietStart)
    prefs.edit().clear().commit()
  }
}
