package com.google.android.accessibility.talkback.calls

import android.app.Notification
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.SpeechController
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CallAnnouncementsTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val spoken = mutableListOf<String>()
  private val stopped = mutableListOf<SpeechController.UtteranceCompleteRunnable>()
  private val completions = mutableListOf<SpeechController.UtteranceCompleteRunnable>()

  @Before fun setUp() {
    prefs.edit().clear().commit()
    CallAnnouncements.attach(context, { text, completion ->
      spoken += text.toString()
      completions += completion
    }, { stopped += it }, { true }, { false })
  }

  @After fun tearDown() {
    CallAnnouncements.detach()
    prefs.edit().clear().commit()
  }

  private fun incoming() = Notification.Builder(context, "calls")
    .setCategory(Notification.CATEGORY_CALL).setContentTitle("Người gọi").build()

  private fun waitForReading() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

  @Test fun duplicateEventsFromListenerAndAccessibilityAreReadOnce() {
    val notification = incoming()
    CallAnnouncements.onCallNotification("phone", notification)
    CallAnnouncements.onCallNotification("phone", notification)
    waitForReading()
    assertEquals(1, spoken.size)
    assertTrue(spoken.single().contains("Người gọi"))
  }

  @Test fun configuredRepeatsWaitForCompletionAndStopAtTheChosenCount() {
    prefs.edit().putString(CallSettings.REPEATS, "2").commit()
    CallAnnouncements.onCallNotification("phone", incoming())
    waitForReading()
    repeat(3) { index ->
      assertEquals(index + 1, spoken.size)
      completions.last().run(SpeechController.STATUS_SPOKEN)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
    }
    assertEquals(3, spoken.size)
  }

  @Test fun removingCallCancelsCurrentReadingAndFurtherRepeats() {
    prefs.edit().putString(CallSettings.REPEATS, "3").commit()
    CallAnnouncements.onCallNotification("phone", incoming())
    waitForReading()
    CallAnnouncements.onCallNotificationRemoved("phone")
    assertEquals(completions, stopped)
    completions.last().run(SpeechController.STATUS_SPOKEN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
    assertEquals(1, spoken.size)
  }

  @Test fun quietHoursAndDisabledCallAnnouncementsAreIndependentOfNotifications() {
    prefs.edit().putBoolean(CallSettings.QUIET, true)
      .putInt(CallSettings.START, 0).putInt(CallSettings.END, 0).commit()
    CallAnnouncements.onCallNotification("quiet", incoming())
    waitForReading()
    assertTrue(spoken.isEmpty())
    prefs.edit().putBoolean(CallSettings.QUIET, false)
      .putBoolean(CallSettings.ANNOUNCE, false).commit()
    CallAnnouncements.onCallNotification("disabled", incoming())
    waitForReading()
    assertTrue(spoken.isEmpty())
    prefs.edit().putBoolean(CallSettings.ANNOUNCE, true)
      .putBoolean("pref_speak_notifications", false).commit()
    CallAnnouncements.onCallNotification("enabled", incoming())
    waitForReading()
    assertEquals(1, spoken.size)
  }

  @Test fun switchingOffAnnouncementsCancelsSpeechAlreadyQueued() {
    CallAnnouncements.onCallNotification("phone", incoming())
    waitForReading()
    prefs.edit().putBoolean(CallSettings.ANNOUNCE, false).commit()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(completions, stopped)
  }

  @Test fun ongoingCallNotificationStopsRinging() {
    CallAnnouncements.onCallNotification("phone", incoming())
    val ongoing = incoming().apply { extras.putInt("android.callType", 2) }
    CallAnnouncements.onCallNotification("phone", ongoing)
    waitForReading()
    assertTrue(spoken.isEmpty())
  }
}
