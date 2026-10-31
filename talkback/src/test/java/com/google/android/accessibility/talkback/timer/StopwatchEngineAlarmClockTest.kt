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

/**
 * The stopwatch's alarms and milestones: a reminder is always a system alarm clock, and a
 * milestone whose alarm never arrived is said once when the engine next runs, not dropped.
 */
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
  fun start_setsAnAlarmClockAtTheNextMultiple() {
    val startedAt = System.currentTimeMillis() - 60_000L
    startEngine(startedAt)

    val info = alarmManager.nextAlarmClock
    assertNotNull(info)
    assertEquals(startedAt + 600_000L, info!!.triggerTime)
    assertNotNull(info.showIntent)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertNotNull(shadowAlarms.scheduledAlarms[0].alarmClockInfo)
    assertEquals("no milestone has been reached yet", 0, said.size)
  }

  @Test
  fun stop_cancelsTheAlarmClock() {
    startEngine(System.currentTimeMillis() - 60_000L)
    assertNotNull(alarmManager.nextAlarmClock)

    engine!!.stop()
    engine = null

    assertNull(alarmManager.nextAlarmClock)
    assertEquals(0, shadowAlarms.scheduledAlarms.size)
  }

  @Test
  fun missedMilestones_theNewestIsSaidOnceWhenTheEngineStarts() {
    // Twenty-five minutes in, the 10- and 20-minute milestones were never announced.
    val startedAt = System.currentTimeMillis() - 1_500_000L
    startEngine(startedAt)

    assertEquals("only the newest missed milestone is said", 1, said.size)
    assertEquals(Stopwatch.formatElapsed(context, 1_200_000L), said[0].toString())
    assertEquals(1_200_000L, prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, -1))
    // The chain is armed again for the next milestone.
    assertEquals(startedAt + 1_800_000L, alarmManager.nextAlarmClock!!.triggerTime)
  }

  @Test
  fun startingAgain_doesNotSayTheMilestoneAgain() {
    val startedAt = System.currentTimeMillis() - 1_500_000L
    startEngine(startedAt)
    assertEquals(1, said.size)

    engine!!.stop()
    engine = null
    startEngine(startedAt)

    assertEquals(1, said.size)
  }

  @Test
  fun alarmBroadcast_doesNotSayAgain_andReschedules() {
    val startedAt = System.currentTimeMillis() - 605_000L
    startEngine(startedAt)
    assertEquals("the catch-up said the milestone it found", 1, said.size)

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

    assertEquals("the same milestone is not said twice", 1, said.size)
    assertEquals(1, shadowAlarms.scheduledAlarms.size)
    assertEquals(startedAt + 1_200_000L, shadowAlarms.scheduledAlarms.single().triggerAtMs)
    assertNotNull(alarmManager.nextAlarmClock)
  }

  @Test
  fun stoppingTheStopwatch_forgetsTheAnnouncedMilestone() {
    val startedAt = System.currentTimeMillis() - 1_500_000L
    startEngine(startedAt)
    assertEquals(1_200_000L, prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, -1))

    engine!!.toggle()

    assertEquals(0L, Stopwatch.startedAt(context))
    assertEquals(0L, prefs.getLong(Stopwatch.KEY_LAST_ANNOUNCED, -1))
    assertNull(alarmManager.nextAlarmClock)
  }

  @Test
  fun vibrationMilestones_resetAfterEveryHour() {
    assertEquals(1, Stopwatch.pulsesForMilestone(10))
    assertEquals(2, Stopwatch.pulsesForMilestone(20))
    assertEquals(6, Stopwatch.pulsesForMilestone(60))
    assertEquals(1, Stopwatch.pulsesForMilestone(70))
    assertEquals(2, Stopwatch.pulsesForMilestone(80))
    assertEquals(6, Stopwatch.pulsesForMilestone(120))
  }

  private fun startEngine(startedAt: Long) {
    prefs
      .edit()
      .putLong(Stopwatch.KEY_STARTED_AT, startedAt)
      .putString(Stopwatch.KEY_INTERVAL_MINUTES, "10")
      .putBoolean(Stopwatch.KEY_SOUND, false)
      .putBoolean(Stopwatch.KEY_VIBRATION, false)
      .commit()
    engine = StopwatchEngine(context, { text, _ -> said += text }, {}).also { it.start() }
  }
}
