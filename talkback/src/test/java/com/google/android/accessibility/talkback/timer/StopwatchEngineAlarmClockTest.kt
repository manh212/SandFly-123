/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.timer

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.google.android.accessibility.talkback.notifications.AlarmClockIntents
import com.google.android.accessibility.talkback.notifications.SpokenSchedules
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The stopwatch's alarms: an alarm clock at exact times, a windowed listener alarm otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopwatchEngineAlarmClockTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val alarmManager = context.getSystemService(AlarmManager::class.java)
  private val shadowAlarms: ShadowAlarmManager = Shadows.shadowOf(alarmManager)
  private val said = mutableListOf<CharSequence>()
  private var engine: StopwatchEngine? = null

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    said.clear()
  }

  @After
  fun tearDown() {
    engine?.stop()
    engine = null
  }

  @Test
  fun exactOn_setsAnAlarmClockAtTheNextMultiple() {
    val startedAt = System.currentTimeMillis() - 60_000L
    startEngine(exact = true, startedAt = startedAt)

    val info = alarmManager.nextAlarmClock
    assertNotNull(info)
    assertEquals(startedAt + 600_000L, info!!.triggerTime)
    assertNotNull(info.showIntent)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertNotNull(shadowAlarms.scheduledAlarms[0].alarmClockInfo)
  }

  @Test
  fun exactOff_setsAWindowListenerAlarmAsBefore() {
    val startedAt = System.currentTimeMillis() - 60_000L
    startEngine(exact = false, startedAt = startedAt)

    assertNull(alarmManager.nextAlarmClock)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    val alarm = shadowAlarms.scheduledAlarms[0]
    assertEquals(startedAt + 600_000L, alarm.triggerAtMs)
    assertEquals(60_000L, alarm.windowLengthMs)
    assertNotNull(alarm.onAlarmListener)
  }

  @Test
  fun stop_cancelsTheAlarmClock() {
    startEngine(exact = true, startedAt = System.currentTimeMillis() - 60_000L)
    assertNotNull(alarmManager.nextAlarmClock)

    engine!!.stop()
    engine = null

    assertNull(alarmManager.nextAlarmClock)
    assertEquals(0, shadowAlarms.scheduledAlarms.size)
  }

  @Test
  fun togglingExact_reschedulesIntoTheOtherFormLeavingOneAlarm() {
    startEngine(exact = true, startedAt = System.currentTimeMillis() - 60_000L)
    assertNotNull(alarmManager.nextAlarmClock)

    prefs.edit().putBoolean(SpokenSchedules.KEY_CLOCK_EXACT, false).commit()
    assertNull("the alarm clock must be gone once exact times are off", alarmManager.nextAlarmClock)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertNotNull(shadowAlarms.scheduledAlarms[0].onAlarmListener)

    prefs.edit().putBoolean(SpokenSchedules.KEY_CLOCK_EXACT, true).commit()
    assertNotNull(alarmManager.nextAlarmClock)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertNotNull(shadowAlarms.scheduledAlarms[0].alarmClockInfo)
  }

  @Test
  fun alarmBroadcast_runsTheReminderAndReschedules() {
    val startedAt = System.currentTimeMillis() - 605_000L
    startEngine(exact = true, startedAt = startedAt)

    // The alarm clock's operation is the stopwatch broadcast.
    val operation = shadowAlarms.scheduledAlarms.single().operation
    assertNotNull(operation)
    val savedIntent: Intent = Shadows.shadowOf(operation).savedIntent
    assertEquals(
      AlarmClockIntents.action(context, AlarmClockIntents.ACTION_STOPWATCH),
      savedIntent.action,
    )

    context.sendBroadcast(
      Intent(AlarmClockIntents.action(context, AlarmClockIntents.ACTION_STOPWATCH))
        .setPackage(context.packageName)
    )
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertEquals("the reminder says the milestone it reached", 1, said.size)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertEquals(
      startedAt + 1_200_000L,
      shadowAlarms.scheduledAlarms.single().triggerAtMs,
    )
    assertNotNull(alarmManager.nextAlarmClock)
  }

  private fun startEngine(exact: Boolean, startedAt: Long) {
    prefs
      .edit()
      .putLong(Stopwatch.KEY_STARTED_AT, startedAt)
      .putString(Stopwatch.KEY_INTERVAL_MINUTES, "10")
      .putBoolean(SpokenSchedules.KEY_CLOCK_EXACT, exact)
      .putBoolean(Stopwatch.KEY_SOUND, false)
      .putBoolean(Stopwatch.KEY_VIBRATION, false)
      .commit()
    engine = StopwatchEngine(context, { text, _ -> said += text }, {}).also { it.start() }
  }
}
