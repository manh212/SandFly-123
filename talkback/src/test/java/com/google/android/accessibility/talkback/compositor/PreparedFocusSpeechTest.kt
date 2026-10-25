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

package com.google.android.accessibility.talkback.compositor

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

class PreparedFocusSpeechTest {
  private val window = 7
  private val otherWindow = 8

  @Test
  fun anyContentChangeInTheWindowCounts_evenTextOrStateAlone() {
    assertTrue(
      PreparedFocusSpeech.canChange(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        window,
        window,
      )
    )
    assertTrue(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_VIEW_SCROLLED, window, window))
    assertTrue(
      PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, window, window)
    )
  }

  @Test
  fun changesInAnotherWindowDoNotCount() {
    assertFalse(
      PreparedFocusSpeech.canChange(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        otherWindow,
        window,
      )
    )
  }

  @Test
  fun windowChangesAndEventsWithoutAWindowCount() {
    assertTrue(
      PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_WINDOWS_CHANGED, otherWindow, window)
    )
    assertTrue(
      PreparedFocusSpeech.canChange(
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        otherWindow,
        window,
      )
    )
    assertTrue(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_VIEW_CLICKED, -1, window))
  }

  @Test
  fun focusAndTouchEventsDoNotCount() {
    for (type in
      listOf(
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
        AccessibilityEvent.TYPE_ANNOUNCEMENT,
      )) {
      assertFalse(PreparedFocusSpeech.canChange(type, window, window))
    }
  }

  @Test
  fun announcementPreparedJustBeforeTheSwipeIsNotUsed() {
    val prepared = 1_000L
    assertFalse(PreparedFocusSpeech.isOldEnough(prepared, prepared))
    assertFalse(
      PreparedFocusSpeech.isOldEnough(prepared, prepared + PreparedFocusSpeech.MIN_AGE_MS - 1)
    )
    assertTrue(PreparedFocusSpeech.isOldEnough(prepared, prepared + PreparedFocusSpeech.MIN_AGE_MS))
    assertTrue(PreparedFocusSpeech.isOldEnough(prepared, prepared + 2_000L))
  }
}

/** Tests that a prepared announcement is kept per node and per kind of swipe target. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedFocusSpeechKeyingTest {
  private fun node(): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo()
    info.packageName = "com.example"
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  private fun feedback(): EventFeedback = EventFeedback.builder().build()

  @Before
  fun setUp() {
    PreparedFocusSpeech.clear()
  }

  @Test
  fun headingEntry_doesNotStandInForTheDefaultEntry() {
    val node = node()

    PreparedFocusSpeech.put(node, feedback(), "", NavigationTarget.TARGET_HEADING)

    assertTrue(PreparedFocusSpeech.has(node, NavigationTarget.TARGET_HEADING))
    assertFalse(PreparedFocusSpeech.has(node))
  }

  @Test
  fun defaultEntry_doesNotStandInForTheHeadingEntry() {
    val node = node()

    PreparedFocusSpeech.put(node, feedback(), "")

    assertTrue(PreparedFocusSpeech.has(node))
    assertFalse(PreparedFocusSpeech.has(node, NavigationTarget.TARGET_HEADING))
  }

  @Test
  fun sameNodeAndKind_replacesTheEarlierEntry() {
    val node = node()
    val latest = feedback()

    PreparedFocusSpeech.put(node, feedback(), "", NavigationTarget.TARGET_LINK)
    PreparedFocusSpeech.put(node, latest, "", NavigationTarget.TARGET_LINK)
    ShadowSystemClock.advanceBy(Duration.ofMillis(PreparedFocusSpeech.MIN_AGE_MS + 1))

    assertSame(latest, PreparedFocusSpeech.take(node)?.feedback)
  }

  @Test
  fun take_matchesByNodeAcrossKinds() {
    val node = node()
    val headingFeedback = feedback()

    PreparedFocusSpeech.put(node, headingFeedback, "", NavigationTarget.TARGET_HEADING)
    ShadowSystemClock.advanceBy(Duration.ofMillis(PreparedFocusSpeech.MIN_AGE_MS + 1))

    assertSame(headingFeedback, PreparedFocusSpeech.take(node)?.feedback)
  }
}

/**
 * Tests the adaptive pause: a window whose preparations keep dying unused stops being prepared
 * for a while, while a window that uses them, or never wastes them, is prepared as before. Each
 * test uses its own window ids, since the state under test is kept per window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedFocusSpeechSuppressionTest {
  private fun node(windowId: Int): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo()
    info.packageName = "com.example"
    // A node reports the id of the window it is in; there is no public setter for it.
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(windowId)
    shadowOf(info).setAccessibilityWindowInfo(window)
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  private fun feedback(): EventFeedback = EventFeedback.builder().build()

  /** Prepares for [windowId], then has a content change throw the preparation away unused. */
  private fun waste(windowId: Int) {
    PreparedFocusSpeech.put(node(windowId), feedback(), "")
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    shadowOf(event).setWindowId(windowId)
    PreparedFocusSpeech.onAccessibilityEvent(event)
    event.recycle()
  }

  @Before
  fun setUp() {
    PreparedFocusSpeech.clear()
  }

  @Test
  fun threeWastedRounds_pausePreparationForThatWindowOnly() {
    repeat(3) { waste(101) }

    assertFalse(PreparedFocusSpeech.shouldPrepare(node(101)))
    assertTrue(PreparedFocusSpeech.shouldPrepare(node(201)))
  }

  @Test
  fun twoWastedRounds_stillPrepares() {
    repeat(2) { waste(102) }

    assertTrue(PreparedFocusSpeech.shouldPrepare(node(102)))
  }

  @Test
  fun usedPreparation_forgivesTheStreak() {
    repeat(2) { waste(103) }
    val node = node(103)
    PreparedFocusSpeech.put(node, feedback(), "")
    ShadowSystemClock.advanceBy(Duration.ofMillis(PreparedFocusSpeech.MIN_AGE_MS + 1))
    assertNotNull(PreparedFocusSpeech.take(node))

    repeat(2) { waste(103) }

    // Only two wastes since the hit: the streak the hit forgave does not count.
    assertTrue(PreparedFocusSpeech.shouldPrepare(node(103)))
  }

  @Test
  fun pauseExpires_onePreparationIsLetThrough_andOneMoreWastePausesAgain() {
    repeat(3) { waste(104) }
    assertFalse(PreparedFocusSpeech.shouldPrepare(node(104)))

    ShadowSystemClock.advanceBy(Duration.ofSeconds(31))

    assertTrue(PreparedFocusSpeech.shouldPrepare(node(104)))

    waste(104)

    assertFalse(PreparedFocusSpeech.shouldPrepare(node(104)))
  }

  @Test
  fun wasteInAnotherWindow_doesNotCountTowardThisOne() {
    repeat(2) { waste(105) }
    repeat(2) { waste(106) }

    assertTrue(PreparedFocusSpeech.shouldPrepare(node(105)))
    assertTrue(PreparedFocusSpeech.shouldPrepare(node(106)))
  }
}
