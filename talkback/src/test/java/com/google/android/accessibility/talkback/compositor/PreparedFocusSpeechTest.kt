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

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

  @Test
  fun peek_returnsTheEntryWithoutSpendingIt() {
    val node = node()
    val prepared = feedback()

    PreparedFocusSpeech.put(node, prepared, "")

    assertSame(prepared, PreparedFocusSpeech.peek(node)?.feedback)
    // Looking did not spend it: the swipe that focuses the node can still take it.
    ShadowSystemClock.advanceBy(Duration.ofMillis(PreparedFocusSpeech.MIN_AGE_MS + 1))
    assertSame(prepared, PreparedFocusSpeech.take(node)?.feedback)
  }

  @Test
  fun peek_respectsTheKindAndTheNode() {
    // Nodes obtained the framework's way, with identities of their own: comparing two
    // different nodes is what this test is about, and a node built by the raw constructor
    // has no identity a comparison could use.
    fun obtainedNode(): AccessibilityNodeInfoCompat {
      val info = AccessibilityNodeInfo.obtain()
      info.packageName = "com.example"
      return AccessibilityNodeInfoCompat.wrap(info)
    }
    val node = obtainedNode()
    PreparedFocusSpeech.put(node, feedback(), "", NavigationTarget.TARGET_HEADING)

    assertNull(PreparedFocusSpeech.peek(node))
    assertNotNull(PreparedFocusSpeech.peek(node, NavigationTarget.TARGET_HEADING))
    assertNull(PreparedFocusSpeech.peek(obtainedNode()))
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

/**
 * Tests that a content change throws away only the prepared announcements its region overlaps,
 * so an unrelated change in a chat window neither kills the preparations nor counts as waste
 * toward the suppression pause. That holds for changes of the graced kind (text, state or
 * content description only) and for structural changes alike, as long as the region the change
 * came from is known. Each test uses its own window ids, since the waste state under test is
 * kept per window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedFocusSpeechUnrelatedChangeTest {
  private fun node(windowId: Int, bounds: Rect): AccessibilityNodeInfoCompat {
    // Obtained, not constructed: the shadow gives obtained nodes an origin id, which is what
    // its equals tells nodes apart by - a constructed info has none and makes equals throw.
    val info = AccessibilityNodeInfo.obtain()
    info.packageName = "com.example"
    info.setBoundsInScreen(bounds)
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(windowId)
    shadowOf(info).setAccessibilityWindowInfo(window)
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  private fun feedback(): EventFeedback = EventFeedback.builder().build()

  /**
   * A window-content change in [windowId] with [changes], whose source sits in [sourceBounds],
   * or has no source at all when [sourceBounds] is null, so its region is not known.
   */
  private fun changeEvent(windowId: Int, sourceBounds: Rect?, changes: Int): AccessibilityEvent {
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    event.contentChangeTypes = changes
    shadowOf(event).setWindowId(windowId)
    if (sourceBounds != null) {
      val source = AccessibilityNodeInfo()
      source.setBoundsInScreen(sourceBounds)
      shadowOf(event).setSourceNode(source)
    }
    return event
  }

  private fun gracedEvent(windowId: Int, sourceBounds: Rect?): AccessibilityEvent =
    changeEvent(windowId, sourceBounds, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)

  private fun agePastMinimum() {
    ShadowSystemClock.advanceBy(Duration.ofMillis(PreparedFocusSpeech.MIN_AGE_MS + 1))
  }

  @Before
  fun setUp() {
    PreparedFocusSpeech.clear()
    PreparedFocusSpeech.keepAcrossUnrelatedChanges = true
  }

  @After
  fun tearDown() {
    PreparedFocusSpeech.clear()
    PreparedFocusSpeech.keepAcrossUnrelatedChanges = true
  }

  @Test
  fun gracedChangeOutsideThePreparedNode_takeStillReturnsIt() {
    val node = node(301, Rect(0, 0, 100, 100))
    val kept = feedback()
    PreparedFocusSpeech.put(node, kept, "")

    PreparedFocusSpeech.onAccessibilityEvent(gracedEvent(301, Rect(200, 200, 300, 300)))
    agePastMinimum()

    assertTrue(PreparedFocusSpeech.has(node))
    assertSame(kept, PreparedFocusSpeech.take(node)?.feedback)
  }

  @Test
  fun gracedChangeContainingThePreparedNode_takeReturnsNull() {
    val node = node(302, Rect(0, 0, 100, 100))
    PreparedFocusSpeech.put(node, feedback(), "")

    PreparedFocusSpeech.onAccessibilityEvent(gracedEvent(302, Rect(0, 0, 200, 200)))
    agePastMinimum()

    assertFalse(PreparedFocusSpeech.has(node))
    assertNull(PreparedFocusSpeech.take(node))
  }

  @Test
  fun gracedChangeWithUnknownRegion_takeReturnsNull() {
    val node = node(303, Rect(0, 0, 100, 100))
    PreparedFocusSpeech.put(node, feedback(), "")

    PreparedFocusSpeech.onAccessibilityEvent(gracedEvent(303, null))
    agePastMinimum()

    assertNull(PreparedFocusSpeech.take(node))
  }

  @Test
  fun gracedChangeInsideTheNode_takeReturnsNull() {
    val node = node(308, Rect(0, 0, 100, 100))
    PreparedFocusSpeech.put(node, feedback(), "")

    // A change inside the node (a child's text, say) changes what the node announces.
    PreparedFocusSpeech.onAccessibilityEvent(gracedEvent(308, Rect(10, 10, 50, 50)))
    agePastMinimum()

    assertNull(PreparedFocusSpeech.take(node))
  }

  @Test
  fun gracedChangeTouchingOnlyOneEntry_keepsTheOther() {
    val touched = node(304, Rect(0, 0, 100, 100))
    val spared = node(304, Rect(200, 200, 300, 300))
    val sparedFeedback = feedback()
    PreparedFocusSpeech.put(touched, feedback(), "")
    PreparedFocusSpeech.put(spared, sparedFeedback, "")

    PreparedFocusSpeech.onAccessibilityEvent(gracedEvent(304, Rect(0, 0, 150, 150)))
    agePastMinimum()

    assertFalse(PreparedFocusSpeech.has(touched))
    assertSame(sparedFeedback, PreparedFocusSpeech.take(spared)?.feedback)
  }

  @Test
  fun repeatedUnrelatedChanges_doNotPausePreparation() {
    val node = node(305, Rect(0, 0, 100, 100))

    repeat(5) {
      PreparedFocusSpeech.put(node, feedback(), "")
      PreparedFocusSpeech.onAccessibilityEvent(gracedEvent(305, Rect(200, 200, 300, 300)))
    }

    assertTrue(PreparedFocusSpeech.shouldPrepare(node))
    assertTrue(PreparedFocusSpeech.has(node))
  }

  @Test
  fun keepingOff_anyGracedChangeClearsAsBefore() {
    val node = node(306, Rect(0, 0, 100, 100))
    PreparedFocusSpeech.keepAcrossUnrelatedChanges = false
    PreparedFocusSpeech.put(node, feedback(), "")

    PreparedFocusSpeech.onAccessibilityEvent(gracedEvent(306, Rect(200, 200, 300, 300)))
    agePastMinimum()

    assertNull(PreparedFocusSpeech.take(node))
  }

  @Test
  fun subtreeChangeTouchingOnlyOneEntry_keepsTheOther() {
    val near = node(307, Rect(0, 0, 100, 100))
    val far = node(307, Rect(200, 200, 300, 300))
    val nearFeedback = feedback()
    PreparedFocusSpeech.put(near, nearFeedback, "")
    PreparedFocusSpeech.put(far, feedback(), "")

    PreparedFocusSpeech.onAccessibilityEvent(
      changeEvent(
        307,
        Rect(200, 200, 300, 300),
        AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
          AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
      )
    )
    agePastMinimum()

    assertFalse(PreparedFocusSpeech.has(far))
    assertSame(nearFeedback, PreparedFocusSpeech.take(near)?.feedback)
  }

  @Test
  fun subtreeChangeWithUnknownRegion_clearsEverything() {
    val node = node(309, Rect(0, 0, 100, 100))
    PreparedFocusSpeech.put(node, feedback(), "")

    PreparedFocusSpeech.onAccessibilityEvent(
      changeEvent(309, null, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
    )
    agePastMinimum()

    assertNull(PreparedFocusSpeech.take(node))
  }

  @Test
  fun keepingOff_subtreeChangeClearsAsBefore_evenOutsideItsRegion() {
    val node = node(310, Rect(0, 0, 100, 100))
    PreparedFocusSpeech.keepAcrossUnrelatedChanges = false
    PreparedFocusSpeech.put(node, feedback(), "")

    PreparedFocusSpeech.onAccessibilityEvent(
      changeEvent(
        310,
        Rect(200, 200, 300, 300),
        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
      )
    )
    agePastMinimum()

    assertNull(PreparedFocusSpeech.take(node))
  }
}

/**
 * Tests that taking one announcement keeps the ones beside it for the next swipe (and that
 * turning that off restores forgetting them all), that a preparation keeps what set it off,
 * and that the settle listener hears about changes to the window announcements were prepared
 * for, even after the announcements themselves are gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedFocusSpeechKeepingTest {
  private fun node(windowId: Int, bounds: Rect): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo.obtain()
    info.packageName = "com.example"
    info.setBoundsInScreen(bounds)
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(windowId)
    shadowOf(info).setAccessibilityWindowInfo(window)
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  private fun feedback(): EventFeedback = EventFeedback.builder().build()

  private fun agePastMinimum() {
    ShadowSystemClock.advanceBy(Duration.ofMillis(PreparedFocusSpeech.MIN_AGE_MS + 1))
  }

  @Before
  fun setUp() {
    PreparedFocusSpeech.clear()
    PreparedFocusSpeech.keepUnusedOnTake = true
    PreparedFocusSpeech.settleListener = null
  }

  @After
  fun tearDown() {
    PreparedFocusSpeech.clear()
    PreparedFocusSpeech.keepUnusedOnTake = true
    PreparedFocusSpeech.settleListener = null
  }

  @Test
  fun take_keepsTheAnnouncementsBesideTheTakenOne() {
    val taken = node(401, Rect(0, 0, 100, 100))
    val beside = node(401, Rect(200, 0, 300, 100))
    val besideFeedback = feedback()
    PreparedFocusSpeech.put(taken, feedback(), "")
    PreparedFocusSpeech.put(beside, besideFeedback, "")
    agePastMinimum()

    assertNotNull(PreparedFocusSpeech.take(taken))

    assertTrue(PreparedFocusSpeech.has(beside))
    assertSame(besideFeedback, PreparedFocusSpeech.take(beside)?.feedback)
  }

  @Test
  fun take_forgetsEverything_whenKeepingIsOff() {
    val taken = node(402, Rect(0, 0, 100, 100))
    val beside = node(402, Rect(200, 0, 300, 100))
    PreparedFocusSpeech.keepUnusedOnTake = false
    PreparedFocusSpeech.put(taken, feedback(), "")
    PreparedFocusSpeech.put(beside, feedback(), "")
    agePastMinimum()

    assertNotNull(PreparedFocusSpeech.take(taken))

    assertFalse(PreparedFocusSpeech.has(beside))
  }

  @Test
  fun put_keepsWhatSetThePreparationOff() {
    val node = node(403, Rect(0, 0, 100, 100))
    PreparedFocusSpeech.put(
      node,
      feedback(),
      "",
      NavigationTarget.TARGET_DEFAULT,
      PreparedFocusSpeech.SOURCE_SETTLE,
      true,
      2,
    )
    agePastMinimum()

    val prepared = PreparedFocusSpeech.take(node)

    assertNotNull(prepared)
    assertTrue(prepared!!.source == PreparedFocusSpeech.SOURCE_SETTLE)
    assertTrue(prepared.fromStaleOrder)
    assertTrue(prepared.depth == 2)
  }

  @Test
  fun settleListener_hearsChangesToThePreparedWindow_evenAfterTheyAreGone() {
    val node = node(404, Rect(0, 0, 100, 100))
    var heard = 0
    PreparedFocusSpeech.settleListener = Runnable { heard++ }
    PreparedFocusSpeech.put(node, feedback(), "")

    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    shadowOf(event).setWindowId(404)
    PreparedFocusSpeech.onAccessibilityEvent(event)
    // The announcements are gone now; a further change to the same window is still heard,
    // which is what lets preparation be offered again once the window goes quiet.
    PreparedFocusSpeech.onAccessibilityEvent(event)
    event.recycle()

    assertTrue(heard == 2)
  }

  @Test
  fun settleListener_ignoresChangesToAnotherWindow() {
    val node = node(405, Rect(0, 0, 100, 100))
    var heard = 0
    PreparedFocusSpeech.settleListener = Runnable { heard++ }
    PreparedFocusSpeech.put(node, feedback(), "")

    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    shadowOf(event).setWindowId(406)
    PreparedFocusSpeech.onAccessibilityEvent(event)
    event.recycle()

    assertTrue(heard == 0)
  }
}
