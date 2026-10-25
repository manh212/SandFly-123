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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The timer settings are one flat screen: the initial setup first, the spoken clock above, the
 * stopwatch below — no exact-times switch and no stopwatch screen inside the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerSettingsStructureTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun timerScreen_isFlat_inOrder_andHasNoExactSwitch() {
    val rows = mutableListOf<Pair<String?, String?>>()
    val parser = context.resources.getXml(R.xml.timer_preferences)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG && parser.name != "PreferenceScreen") {
        val key = parser.getAttributeValue(ANDROID_NS, "key")
        val fragment = parser.getAttributeValue(ANDROID_NS, "fragment")
        if (key != null || fragment != null) rows += key to fragment
      }
      event = parser.next()
    }

    assertEquals(
      listOf(
        "pref_timer_setup_screen",
        "pref_notification_clock_interval",
        "pref_notification_clock_format",
        "pref_stopwatch_sound",
        "pref_stopwatch_vibration",
        "pref_stopwatch_interval_minutes",
        "pref_stopwatch_history_screen",
      ),
      rows.map { it.first },
    )
    assertEquals(
      "com.google.android.accessibility.talkback.timer.TimerSetupFragment",
      rows.first().second,
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

  private companion object {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  }
}
