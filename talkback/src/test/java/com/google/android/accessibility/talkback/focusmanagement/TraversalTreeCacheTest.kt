/*
 * Copyright (C) 2026 The SandFly Authors
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

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * [TraversalTreeCache.onAccessibilityEvent] reports whether it threw the saved order away, which
 * is what makes TalkBackService start a background prefetch of the window's nodes at that moment.
 * The same contract is what makes pruning queued window-content-changed events safe: the cache is
 * cleared synchronously from the event itself, so the order is gone even when the queued copy of
 * the event is later dropped by the queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraversalTreeCacheTest {

  @After
  fun tearDown() {
    TraversalTreeCache.setOrderClearedListener(null)
    TraversalTreeCache.enabled = true
    TraversalTreeCache.regionGraceEnabled = true
    TraversalTreeCache.burstLiveAnswerEnabled = true
    TraversalTreeCache.clear("test")
    TraversalTreeCache.resetOrderCacheStats()
    LogUtils.setEnabled(false)
  }

  /** Registers a listener that records the windows it is told about, and returns the record. */
  private fun recordingListener(): MutableList<Int> {
    val reported = mutableListOf<Int>()
    TraversalTreeCache.setOrderClearedListener { reported.add(it) }
    return reported
  }

  @Test
  fun disabledCache_dropsSavedOrderAndDoesNotKeepNewOrders() {
    val (root, child) = savedOrder()
    TraversalTreeCache.enabled = false
    assertNull(TraversalTreeCache.get(root, child))
    assertFalse(TraversalTreeCache.holds(child))
    assertNull(TraversalTreeCache.rootFor(child))
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    assertNull(TraversalTreeCache.get(root, child))
    TraversalTreeCache.enabled = true
    assertNull(TraversalTreeCache.get(root, child))
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    assertNotNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun buildCount_isSeparateFromLookupMissesAndLiveAnswers() {
    LogUtils.setEnabled(true)
    TraversalTreeCache.resetOrderCacheStats()
    val root = AccessibilityNodeInfoCompat.wrap(node(null))
    TraversalTreeCache.get(root, root)
    TraversalTreeCache.noteLiveAnswer()
    assertEquals(0L, TraversalTreeCache.orderCacheBuilt())
    TraversalTreeCache.noteBuild(37)
    assertEquals(1L, TraversalTreeCache.orderCacheBuilt())
    assertTrue(TraversalTreeCache.buildTimingSummary().contains("mean=37 ms"))
  }

  private fun node(parent: AccessibilityNodeInfo?): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    node.className = "android.widget.Button"
    node.isVisibleToUser = true
    node.isFocusable = true
    node.isClickable = true
    node.setBoundsInScreen(Rect(0, 0, 100, 100))
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }

  /** Saves the order of a root with one child, and returns the root and that child. */
  private fun savedOrder(): Pair<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> {
    val rootInfo = node(null)
    node(rootInfo)
    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    val child = checkNotNull(root.getChild(0))
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    return root to child
  }

  private fun event(type: Int, contentChangeTypes: Int = 0): AccessibilityEvent {
    val event = AccessibilityEvent.obtain(type)
    event.contentChangeTypes = contentChangeTypes
    return event
  }

  @Test
  fun structuralEvent_clearsSavedOrder_andReportsTheClear() {
    val (root, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

    assertTrue(cleared)
    assertFalse(TraversalTreeCache.holds(child))
    assertNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun windowContentChanged_undefinedChange_clearsSavedOrder_andReportsTheClear() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, contentChangeTypes = 0)
      )

    assertTrue(cleared)
    assertFalse(TraversalTreeCache.holds(child))
  }

  @Test
  fun windowContentChanged_textOnlyChange_keepsSavedOrder() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
        )
      )

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun ignoredEventType_keepsSavedOrder() {
    val (_, child) = savedOrder()

    val cleared = TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_ANNOUNCEMENT))

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun structuralEvent_withNoSavedOrder_reportsNoClear() {
    savedOrder()
    TraversalTreeCache.clear("test")

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

    assertFalse(cleared)
  }

  @Test
  fun nodeActionClear_reportsTheWindowToTheListener() {
    val (root, child) = savedOrder()
    val reported = recordingListener()

    TraversalTreeCache.onNodeAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)

    assertFalse(TraversalTreeCache.holds(child))
    assertEquals(listOf(root.windowId), reported)
  }

  @Test
  fun focusOnlyNodeAction_keepsTheOrder_andReportsNothing() {
    val (_, child) = savedOrder()
    val reported = recordingListener()

    TraversalTreeCache.onNodeAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)

    assertTrue(TraversalTreeCache.holds(child))
    assertTrue(reported.isEmpty())
  }

  @Test
  fun operationClear_reportsTheWindowToTheListener() {
    val (root, _) = savedOrder()
    val reported = recordingListener()

    // The clear a resume and a focus retry perform: a direct call, not an event.
    TraversalTreeCache.clear("resume")

    assertEquals(listOf(root.windowId), reported)
  }

  @Test
  fun operationClear_withNoSavedOrder_reportsNothing() {
    val reported = recordingListener()

    TraversalTreeCache.clear("resume")

    assertTrue(reported.isEmpty())
  }

  @Test
  fun eventClear_doesNotReportToTheListener() {
    savedOrder()
    val reported = recordingListener()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

    assertTrue(cleared)
    // The service warms the window from the event itself; the listener is only for the
    // clears no event reports.
    assertTrue(reported.isEmpty())
  }

  @Test
  fun orderExpiredAfterTextChange_doesNotReportToTheListener() {
    val (root, child) = savedOrder()
    val reported = recordingListener()
    TraversalTreeCache.onAccessibilityEvent(
      event(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
      )
    )

    ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
    assertNull(TraversalTreeCache.get(root, child))

    assertTrue(reported.isEmpty())
  }

  @Test
  fun windowContentChanged_contentDescriptionChange_keepsSavedOrderWhenGraceEnabled() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
        )
      )

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun windowContentChanged_contentDescriptionChange_clearsSavedOrderWhenGraceDisabled() {
    val (_, child) = savedOrder()
    TraversalTreeCache.regionGraceEnabled = false

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
        )
      )

    assertTrue(cleared)
    assertFalse(TraversalTreeCache.holds(child))
  }

  @Test
  fun gracedChange_regionNotContainingNode_holdsCurrentStaysTrue() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(200, 200, 300, 300))

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionContainingNode_holdsCurrentIsFalse() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(0, 0, 200, 200))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionInsideNode_holdsCurrentIsFalse() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(10, 10, 50, 50))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionNotContainingNode_holdsCurrentIsFalseWhenGraceDisabled() {
    val (_, child) = savedOrder()
    TraversalTreeCache.regionGraceEnabled = false

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(200, 200, 300, 300))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun burst_singleNavigation_isNotBurst() {
    TraversalTreeCache.noteNavigationActivity(100000L)

    assertFalse(TraversalTreeCache.inNavigationBurst(100100L))
  }

  @Test
  fun burst_twoCloseNavigations_isBurst() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1100L)

    assertTrue(TraversalTreeCache.inNavigationBurst(1200L))
  }

  @Test
  fun burst_firstGapTooLong_isNotBurst() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1400L)

    assertFalse(TraversalTreeCache.inNavigationBurst(1500L))
  }

  @Test
  fun burst_secondGapTooLong_isNotBurst() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1100L)

    assertFalse(TraversalTreeCache.inNavigationBurst(1500L))
  }

  @Test
  fun burst_disabledFlag_doesNotChangeBurstSignal() {
    TraversalTreeCache.noteNavigationActivity(1000L)
    TraversalTreeCache.noteNavigationActivity(1100L)
    TraversalTreeCache.burstLiveAnswerEnabled = false

    assertTrue(TraversalTreeCache.inNavigationBurst(1200L))
  }

  @Test
  fun orderCacheStats_hitCountsAsReused() {
    LogUtils.setEnabled(true)
    val (root, child) = savedOrder()
    TraversalTreeCache.resetOrderCacheStats()

    assertNotNull(TraversalTreeCache.get(root, child))
    assertEquals(1, TraversalTreeCache.orderCacheReused())
    assertEquals(0, TraversalTreeCache.orderCacheMissed())
    assertEquals(0, TraversalTreeCache.orderCacheLiveAnswers())
  }

  @Test
  fun orderCacheStats_missCountsAsMissed() {
    LogUtils.setEnabled(true)
    TraversalTreeCache.resetOrderCacheStats()
    val root = AccessibilityNodeInfoCompat.wrap(node(null))
    val pivot = AccessibilityNodeInfoCompat.wrap(node(null))

    assertNull(TraversalTreeCache.get(root, pivot))
    assertEquals(0, TraversalTreeCache.orderCacheReused())
    assertEquals(1, TraversalTreeCache.orderCacheMissed())
  }

  @Test
  fun orderCacheStats_liveAnswerCountsOnlyWhenLogging() {
    TraversalTreeCache.resetOrderCacheStats()
    TraversalTreeCache.noteLiveAnswer()

    assertEquals(0, TraversalTreeCache.orderCacheLiveAnswers())

    LogUtils.setEnabled(true)
    TraversalTreeCache.noteLiveAnswer()

    assertEquals(1, TraversalTreeCache.orderCacheLiveAnswers())

    TraversalTreeCache.resetOrderCacheStats()
    assertEquals(0, TraversalTreeCache.orderCacheReused())
    assertEquals(0, TraversalTreeCache.orderCacheMissed())
    assertEquals(0, TraversalTreeCache.orderCacheLiveAnswers())
  }
}
