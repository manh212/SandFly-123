/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.timer

import android.content.Context
import com.google.android.accessibility.talkback.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The timer settings are one flat screen: the spoken clock above, the stopwatch below — no
 * exact-times switch and no stopwatch screen inside the screen. The initial setup is not here:
 * it stands second on the main settings screen, right after Updates, and nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun timerScreen_isFlat_inOrder_andHasNoExactSwitch() {
    val rows = rowsOf(R.xml.timer_preferences)

    assertEquals(
      listOf(
        "pref_notification_clock_interval",
        "pref_notification_clock_format",
        "pref_stopwatch_sound",
        "pref_stopwatch_vibration",
        "pref_stopwatch_interval_minutes",
        "pref_stopwatch_history_screen",
      ),
      rows.map { it.first },
    )
    assertFalse(
      "the initial setup moved off this screen",
      rows.any { it.first == SETUP_KEY || it.second?.contains("SetupFragment") == true },
    )
    assertFalse(
      "the exact-times switch is gone",
      rows.any { it.first == "pref_notification_clock_exact" },
    )
    assertFalse(
      "no nested stopwatch screen",
      rows.any { it.second?.endsWith("StopwatchFragment") == true },
    )
  }

  @Test
  fun mainScreen_startsWithUpdates_thenTheInitialSetup_andHasItOnce() {
    val topLevel = mutableListOf<Pair<String?, String?>>()
    val parser = context.resources.getXml(R.xml.preferences)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.depth == 2) {
        topLevel +=
          parser.getAttributeValue(ANDROID_NS, "key") to
            parser.getAttributeValue(ANDROID_NS, "fragment")
      }
      event = parser.next()
    }

    assertEquals("pref_updates_screen", topLevel[0].first)
    assertTrue(topLevel[0].second?.contains("UpdatesFragment") == true)
    assertEquals(SETUP_KEY, topLevel[1].first)
    assertTrue(topLevel[1].second?.contains("SetupFragment") == true)
    assertEquals(
      "one initial setup entry point in the whole main screen",
      1,
      rowsOf(R.xml.preferences).count { it.first == SETUP_KEY },
    )
  }

  /** Every row of an XML preference screen, in document order, as key to fragment. */
  private fun rowsOf(xml: Int): List<Pair<String?, String?>> {
    val rows = mutableListOf<Pair<String?, String?>>()
    val parser = context.resources.getXml(xml)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = parser.getAttributeValue(ANDROID_NS, "key")
        val fragment = parser.getAttributeValue(ANDROID_NS, "fragment")
        if (key != null || fragment != null) rows += key to fragment
      }
      event = parser.next()
    }
    return rows
  }

  private companion object {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val SETUP_KEY = "pref_initial_setup_screen"
  }
}
