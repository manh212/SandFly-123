package com.google.android.accessibility.talkback.notifications

import android.content.Context
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
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
class SpokenSchedulesTest {
  private val chicago = TimeZone.getTimeZone("America/Chicago")
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private fun utc(text: String) = Instant.parse(text).toEpochMilli()

  @Before fun reset() { prefs.edit().clear().commit() }

  @Test
  fun legacyDisabledClockStaysDisabled() {
    prefs.edit().putString(SpokenSchedules.KEY_CLOCK_INTERVAL, "0").commit()
    assertEquals(emptySet<Int>(), SpokenSchedules.selectedHours(prefs))
    assertNull(SpokenSchedules.nextClockTime(System.currentTimeMillis(), SpokenSchedules.selectedHours(prefs)))
  }

  @Test
  fun legacyEnabledClockBecomesAllTwentyFourHours() {
    prefs.edit().putString(SpokenSchedules.KEY_CLOCK_INTERVAL, "180").commit()
    assertEquals(SpokenSchedules.ALL_CLOCK_HOURS, SpokenSchedules.selectedHours(prefs))
  }

  @Test
  fun anExplicitEmptySelectionDisablesPreviouslyEnabledClock() {
    prefs.edit().putString(SpokenSchedules.KEY_CLOCK_INTERVAL, "60")
      .putStringSet(SpokenSchedules.KEY_CLOCK_HOURS, emptySet()).commit()
    assertTrue(SpokenSchedules.selectedHours(prefs).isEmpty())
  }

  @Test
  fun chosenHoursAreExactAndOnlyTheseHoursAnnounce() {
    val hours = setOf(1, 3, 5, 7)
    assertEquals(
      utc("2026-02-01T01:00:00Z"),
      SpokenSchedules.nextClockTime(
        utc("2026-02-01T00:30:00Z"), hours, TimeZone.getTimeZone("UTC")
      ),
    )
    assertEquals(
      utc("2026-02-01T09:00:00Z"),
      SpokenSchedules.nextClockTime(utc("2026-02-01T07:00:00Z"), hours, chicago),
    )
  }

  @Test
  fun repeatedHourAfterFallBackIsReadBothTimes() {
    val hours = setOf(1)
    assertEquals(
      utc("2026-11-01T07:00:00Z"),
      SpokenSchedules.nextClockTime(utc("2026-11-01T06:30:00Z"), hours, chicago),
    )
    assertEquals(
      utc("2026-11-02T07:00:00Z"),
      SpokenSchedules.nextClockTime(utc("2026-11-01T07:00:00Z"), hours, chicago),
    )
  }

  @Test
  fun skippedHourInSpringMovesToNextChosenHour() {
    assertEquals(
      utc("2026-03-09T07:00:00Z"),
      SpokenSchedules.nextClockTime(utc("2026-03-08T07:30:00Z"), setOf(2), chicago),
    )
  }

  @Test
  fun fallBackDayHasTwentyFiveHourlyAlarms() {
    var now = utc("2026-11-01T05:00:00Z")
    var count = 0
    while (true) {
      now = SpokenSchedules.nextClockTime(now, SpokenSchedules.ALL_CLOCK_HOURS, chicago)!!
      if (now > utc("2026-11-02T06:00:00Z")) break
      count++
    }
    assertEquals(25, count)
  }

  @Test
  fun selectedHoursAcrossClockChangesAlwaysMoveForward() {
    val zones = listOf(chicago, TimeZone.getTimeZone("Europe/London"), TimeZone.getTimeZone("Australia/Lord_Howe"))
    val days = listOf("2026-03-07T00:00:00Z", "2026-10-31T00:00:00Z")
    for (zone in zones) for (day in days) {
      var now = utc(day)
      val end = now + 2 * 24 * 3_600_000L
      while (now < end) {
        val next = SpokenSchedules.nextClockTime(now, setOf(1, 5, 23), zone)!!
        assertTrue("${zone.id} at $now", next > now)
        assertTrue("${zone.id} at $now", next - now <= 26 * 3_600_000L)
        now = next
      }
    }
  }
}
