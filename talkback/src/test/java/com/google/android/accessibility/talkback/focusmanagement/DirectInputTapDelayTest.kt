/*
 * Copyright 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.focusmanagement

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Interpretation
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.action.TouchExplorationAction
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the Direct key input tap delay end to end: the value of the setting — and nothing else —
 * decides how long after the finger leaves a key the early touch end runs, which is what types
 * the key. The end is observed as the touch-interaction-end interpretation the processor emits
 * when the delayed message fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectInputTapDelayTest {

  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
  }

  private class RecordingReceiver : Pipeline.InterpretationReceiver {
    val inputs = mutableListOf<Interpretation?>()

    override fun input(
      eventId: EventId?,
      event: AccessibilityEvent?,
      interpretation: Interpretation?,
      eventSourceNode: AccessibilityNodeInfoCompat?,
    ): Boolean {
      inputs += interpretation
      return false
    }

    fun touchEnds(): Int = inputs.count { it is Interpretation.TouchInteraction }
  }

  private lateinit var service: TestService
  private lateinit var prefs: SharedPreferences
  private lateinit var receiver: RecordingReceiver
  private lateinit var processor: FocusProcessorForTapAndTouchExploration

  @Before
  fun setUp() {
    service = Robolectric.setupService(TestService::class.java)
    prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit().remove(delayKey()).commit()
    receiver = RecordingReceiver()
    processor = FocusProcessorForTapAndTouchExploration(service, object : TalkBackAnalytics {})
    processor.setInterpretationReceiver(receiver)
    processor.setTypingMethod(SINGLE_TAP_TO_TYPE)
  }

  private fun delayKey(): String = service.getString(R.string.pref_direct_input_tap_delay_key)

  private fun setDelay(ms: Int) {
    prefs.edit().putInt(delayKey(), ms).commit()
  }

  private fun keyNode(): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo.obtain()
    info.packageName = "com.example.keyboard"
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  /** Hovers onto a key and off it again, as a lift after sliding onto the key arrives. */
  private fun hoverOntoAndOff(node: AccessibilityNodeInfoCompat) {
    processor.onTouchExplorationAction(
      TouchExplorationAction(TouchExplorationAction.HOVER_ENTER, node),
      /* eventId= */ null,
    )
    processor.onTouchExplorationAction(
      TouchExplorationAction(TouchExplorationAction.HOVER_EXIT, node),
      /* eventId= */ null,
    )
  }

  private fun runMainQueueFor(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
  }

  @Test
  fun noSetting_typesAfterTheDefault100ms() {
    hoverOntoAndOff(keyNode())

    runMainQueueFor(99)
    assertEquals(0, receiver.touchEnds())
    runMainQueueFor(2)
    assertEquals(1, receiver.touchEnds())
  }

  @Test
  fun setting200_typesAfter200ms_notTheOld100() {
    setDelay(200)

    hoverOntoAndOff(keyNode())

    runMainQueueFor(199)
    assertEquals(0, receiver.touchEnds())
    runMainQueueFor(2)
    assertEquals(1, receiver.touchEnds())
  }

  @Test
  fun settingAboveTheRange_isCappedAt300ms() {
    setDelay(10_000)

    hoverOntoAndOff(keyNode())

    runMainQueueFor(299)
    assertEquals(0, receiver.touchEnds())
    runMainQueueFor(2)
    assertEquals(1, receiver.touchEnds())
  }

  @Test
  fun anotherTypingMethod_neverEndsEarly() {
    setDelay(50)
    processor.setTypingMethod(DOUBLE_TAP)

    hoverOntoAndOff(keyNode())

    runMainQueueFor(1_000)
    assertEquals(0, receiver.touchEnds())
  }
}
