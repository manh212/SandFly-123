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
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    TraversalTreeCache.orderClearedListener = null
    TraversalTreeCache.settleBuildRequester = null
    TraversalTreeCache.clearCompletely("test")
    TraversalTreeCache.unrelatedChangesSpareNodes = true
    TraversalTreeCache.serveStaleOrdersEnabled = true
    TraversalTreeCache.settleRetryEnabled = true
    TraversalTreeCache.leanOrderBuilderEnabled = true
    TraversalTreeCache.fastNavigatorEnabled = true
    TraversalTreeCache.listenerBuildCoalescingEnabled = true
    TraversalTreeCache.cheapPivotCheckEnabled = true
    TraversalTreeCache.noteNavigationActivity(0L)
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

  private fun eventInWindow(windowId: Int, type: Int, contentChangeTypes: Int = 0): AccessibilityEvent {
    val event = event(type, contentChangeTypes)
    shadowOf(event).setWindowId(windowId)
    return event
  }

  /** A structural content change in [windowId] whose source sits in [sourceBounds]. */
  private fun structuralEvent(windowId: Int, sourceBounds: Rect?): AccessibilityEvent {
    val event =
      eventInWindow(
        windowId,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
      )
    if (sourceBounds != null) {
      val source = AccessibilityNodeInfo.obtain()
      source.setBoundsInScreen(sourceBounds)
      shadowOf(event).setSourceNode(source)
    }
    return event
  }

  /** A graced content change in [windowId] whose source sits in [sourceBounds]. */
  private fun gracedEvent(windowId: Int, sourceBounds: Rect): AccessibilityEvent {
    val event =
      eventInWindow(
        windowId,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT,
      )
    val source = AccessibilityNodeInfo.obtain()
    source.setBoundsInScreen(sourceBounds)
    shadowOf(event).setSourceNode(source)
    return event
  }

  /** A root with one child, not saved anywhere, for handing to a background build. */
  private fun unsavedOrder(): Pair<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> {
    val rootInfo = node(null)
    node(rootInfo)
    val root = AccessibilityNodeInfoCompat.wrap(rootInfo)
    return root to checkNotNull(root.getChild(0))
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
  fun windowContentChanged_descriptionOnlyChange_keepsSavedOrder() {
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
  fun windowContentChanged_textAndDescriptionChange_keepsSavedOrder() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes =
            AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
              AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
        )
      )

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun windowContentChanged_descriptionAndSubtreeChange_clearsSavedOrder() {
    val (_, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        event(
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          contentChangeTypes =
            AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION or
              AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
        )
      )

    assertTrue(cleared)
    assertFalse(TraversalTreeCache.holds(child))
  }

  @Test
  fun windowContentChanged_descriptionChange_orderExpiresAfterTheGrace() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(
      event(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
      )
    )
    assertNotNull(TraversalTreeCache.get(root, child))

    ShadowSystemClock.advanceBy(1600, TimeUnit.MILLISECONDS)

    assertNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun gracedChange_regionNotContainingPivot_holdsCurrentStaysTrue_viaEvent() {
    val (root, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(gracedEvent(root.windowId, Rect(200, 200, 300, 300)))

    assertFalse(cleared)
    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionContainingPivot_holdsCurrentIsFalse_viaEvent() {
    val (root, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(gracedEvent(root.windowId, Rect(0, 0, 200, 200)))

    assertFalse(cleared)
    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionNotContainingPivot_holdsCurrentStaysTrue_viaSeam() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(200, 200, 300, 300))

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionContainingPivot_holdsCurrentIsFalse_viaSeam() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(0, 0, 200, 200))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionInsidePivot_holdsCurrentIsFalse_viaSeam() {
    val (_, child) = savedOrder()

    // A change inside the node (a child's text, say) changes what the node announces.
    TraversalTreeCache.noteGracedChange(child.windowId, Rect(10, 10, 50, 50))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_regionUnknown_holdsCurrentIsFalse() {
    val (_, child) = savedOrder()

    TraversalTreeCache.noteGracedChange(child.windowId, null)

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun gracedChange_sparingOff_holdsCurrentIsFalseEvenForAnUntouchedPivot() {
    val (_, child) = savedOrder()
    TraversalTreeCache.unrelatedChangesSpareNodes = false

    TraversalTreeCache.noteGracedChange(child.windowId, Rect(200, 200, 300, 300))

    assertFalse(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun noGracedChange_holdsCurrentIsTrue() {
    val (_, child) = savedOrder()

    assertTrue(TraversalTreeCache.holdsCurrent(child))
  }

  @Test
  fun publishSnapshot_currentGeneration_savesTheOrder() {
    val (root, child) = unsavedOrder()
    val generation = TraversalTreeCache.currentGeneration()

    val published = TraversalTreeCache.publishSnapshot(root, OrderedTraversalStrategy(root), generation)

    assertTrue(published)
    assertTrue(TraversalTreeCache.holds(child))
    assertNotNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun publishSnapshot_generationMovedOnByAClear_isRefused() {
    val (root, child) = unsavedOrder()
    val generation = TraversalTreeCache.currentGeneration()
    TraversalTreeCache.clear("the window changed")

    val published = TraversalTreeCache.publishSnapshot(root, OrderedTraversalStrategy(root), generation)

    assertFalse(published)
    assertFalse(TraversalTreeCache.holds(child))
    assertNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun publishSnapshot_generationMovedOnByASavedOrder_isRefused() {
    val (root, child) = unsavedOrder()
    val generation = TraversalTreeCache.currentGeneration()
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))

    val published = TraversalTreeCache.publishSnapshot(root, OrderedTraversalStrategy(root), generation)

    assertFalse(published)
    // The refused snapshot did not disturb the order the swipe saved.
    assertTrue(TraversalTreeCache.holds(child))
    assertNotNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun structuralEvent_withNoSavedOrder_retiresAnInFlightBuildOfThatWindow() {
    TraversalTreeCache.buildOrderInBackground(7, { null }, "com.example")
    val generation = TraversalTreeCache.currentGeneration()
    val (root, child) = unsavedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        eventInWindow(
          7,
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
          AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
        )
      )

    assertFalse(cleared)
    val published = TraversalTreeCache.publishSnapshot(root, OrderedTraversalStrategy(root), generation)
    assertFalse(published)
    assertFalse(TraversalTreeCache.holds(child))
  }

  @Test
  fun structuralEvent_withNoSavedOrder_keepsAnInFlightBuildOfAnotherWindow() {
    TraversalTreeCache.buildOrderInBackground(7, { null }, "com.example")
    val generation = TraversalTreeCache.currentGeneration()
    val (root, child) = unsavedOrder()

    TraversalTreeCache.onAccessibilityEvent(
      eventInWindow(
        9,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
      )
    )

    val published = TraversalTreeCache.publishSnapshot(root, OrderedTraversalStrategy(root), generation)
    assertTrue(published)
    assertTrue(TraversalTreeCache.holds(child))
  }

  @Test
  fun descriptionChange_withNoSavedOrder_keepsAnInFlightBuild() {
    TraversalTreeCache.buildOrderInBackground(7, { null }, "com.example")
    val generation = TraversalTreeCache.currentGeneration()
    val (root, child) = unsavedOrder()

    TraversalTreeCache.onAccessibilityEvent(
      eventInWindow(
        7,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION,
      )
    )

    val published = TraversalTreeCache.publishSnapshot(root, OrderedTraversalStrategy(root), generation)
    assertTrue(published)
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
  fun nodeActionClear_tellsTheListenerTheWindow() {
    val (root, _) = savedOrder()
    val told = ArrayList<Int>()
    TraversalTreeCache.orderClearedListener =
      TraversalTreeCache.OrderClearedListener { windowId -> told += windowId }
    try {
      TraversalTreeCache.onNodeAction(
        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
        null,
      )
    } finally {
      TraversalTreeCache.orderClearedListener = null
    }

    assertEquals(listOf(root.windowId), told)
  }

  @Test
  fun eventRetiringAnInFlightBuild_tellsTheListenerTheWindow() {
    // No order is saved, but a background build is reading the window: a structural change
    // retires that build, and until this fix nothing asked again until the screen had been
    // quiet for the settle retry — the gap in which a swipe built its order on the spot.
    TraversalTreeCache.noteNavigationActivity(0L)
    val (root, _) = unsavedOrder()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    TraversalTreeCache.buildOrderInBackground(
      root.windowId,
      {
        started.countDown()
        release.await(5_000L, TimeUnit.MILLISECONDS)
        root
      },
      "com.example.other",
      Performance.ORDER_BUILD_SOURCE_EVENT_CLEAR,
    )
    val told = ArrayList<Int>()
    TraversalTreeCache.orderClearedListener =
      TraversalTreeCache.OrderClearedListener { windowId -> told += windowId }
    try {
      assertTrue(started.await(5_000L, TimeUnit.MILLISECONDS))

      TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, null))

      assertEquals(listOf(root.windowId), told)
    } finally {
      TraversalTreeCache.orderClearedListener = null
      release.countDown()
    }
    // The retired build publishes nothing once it is let go.
    val deadline = System.currentTimeMillis() + 5_000L
    while (
      TraversalOrderSnapshotBuilder.isBuildOnItsWay(root.windowId) &&
        System.currentTimeMillis() < deadline
    ) {
      Thread.sleep(10)
    }
    assertFalse(TraversalTreeCache.hasSavedOrder())
  }

  @Test
  fun eventWithNoBuildOnItsWay_doesNotTellTheListener() {
    // The cache remembers the window of the newest build asked for long after it landed;
    // a change in that window must not ask again when nothing was lost.
    val (root, _) = unsavedOrder()
    val told = ArrayList<Int>()
    TraversalTreeCache.orderClearedListener =
      TraversalTreeCache.OrderClearedListener { windowId -> told += windowId }
    try {
      TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, null))

      assertTrue(told.isEmpty())
    } finally {
      TraversalTreeCache.orderClearedListener = null
    }
  }

  @Test
  fun eventClear_doesNotTellTheListener() {
    savedOrder()
    val told = ArrayList<Int>()
    TraversalTreeCache.orderClearedListener =
      TraversalTreeCache.OrderClearedListener { windowId -> told += windowId }
    try {
      val cleared =
        TraversalTreeCache.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

      assertTrue(cleared)
      assertTrue(told.isEmpty())
    } finally {
      TraversalTreeCache.orderClearedListener = null
    }
  }

  @Test
  fun serveStaleOrder_changeAwayFromThePivot_servesThePreviousOrder() {
    val (root, child) = savedOrder()

    val cleared =
      TraversalTreeCache.onAccessibilityEvent(
        structuralEvent(root.windowId, Rect(500, 500, 600, 600))
      )

    assertTrue(cleared)
    assertNull(TraversalTreeCache.get(root, child))
    assertNotNull(TraversalTreeCache.serveStaleOrder(root, child))
  }

  @Test
  fun serveStaleOrder_changeOverThePivot_refuses() {
    val (root, child) = savedOrder()

    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(0, 0, 200, 200)))

    assertNull(TraversalTreeCache.serveStaleOrder(root, child))
  }

  @Test
  fun serveStaleOrder_changeWithUnknownRegion_refuses() {
    val (root, child) = savedOrder()

    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, null))

    assertNull(TraversalTreeCache.serveStaleOrder(root, child))
  }

  @Test
  fun serveStaleOrder_secondChangeOverThePivot_refuses() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))
    assertNotNull(TraversalTreeCache.serveStaleOrder(root, child))

    // No order is saved any more, so this change is remembered against the stale copy itself.
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(0, 0, 200, 200)))

    assertNull(TraversalTreeCache.serveStaleOrder(root, child))
  }

  @Test
  fun serveStaleOrder_disabled_refuses() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))
    TraversalTreeCache.serveStaleOrdersEnabled = false

    assertNull(TraversalTreeCache.serveStaleOrder(root, child))
  }

  @Test
  fun serveStaleOrder_otherWindow_refuses() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))
    val (otherRoot, _) = unsavedOrder()

    assertNull(TraversalTreeCache.serveStaleOrder(otherRoot, child))
  }

  @Test
  fun clearCompletely_dropsTheStaleCopy() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))

    TraversalTreeCache.clearCompletely("test")

    assertNull(TraversalTreeCache.serveStaleOrder(root, child))
  }

  @Test
  fun freshOrderLanding_dropsTheStaleCopy() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))
    val (newRoot, newChild) = unsavedOrder()
    TraversalTreeCache.put(newRoot, OrderedTraversalStrategy(newRoot))

    assertNull(TraversalTreeCache.serveStaleOrder(root, child))
    assertNotNull(TraversalTreeCache.get(newRoot, newChild))
  }

  @Test
  fun validateStaleTarget_untouchedTarget_validates() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))

    assertTrue(TraversalTreeCache.validateStaleTarget(child))
  }

  @Test
  fun validateStaleTarget_changeOverTheTarget_rejects() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(0, 0, 200, 200)))

    assertFalse(TraversalTreeCache.validateStaleTarget(child))
  }

  @Test
  fun validateStaleTarget_nodeOutsideTheStaleOrder_needsNoCheck() {
    val (root, _) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, null))
    val outsider = AccessibilityNodeInfoCompat.wrap(node(null))

    assertTrue(TraversalTreeCache.validateStaleTarget(outsider))
  }

  @Test
  fun staleOrderForPrediction_changeAwayFromThePivot_returnsThePreviousOrder() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))

    assertNotNull(TraversalTreeCache.staleOrderForPrediction(child))
  }

  @Test
  fun staleOrderForPrediction_changeOverThePivot_refuses() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(0, 0, 200, 200)))

    assertNull(TraversalTreeCache.staleOrderForPrediction(child))
  }

  @Test
  fun staleOrderForPrediction_changeWithUnknownRegion_refuses() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, null))

    assertNull(TraversalTreeCache.staleOrderForPrediction(child))
  }

  @Test
  fun staleOrderForPrediction_disabled_refuses() {
    val (root, child) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))
    TraversalTreeCache.serveStaleOrdersEnabled = false

    assertNull(TraversalTreeCache.staleOrderForPrediction(child))
  }

  @Test
  fun staleOrderForPrediction_noPreviousOrder_returnsNull() {
    val (_, child) = savedOrder()

    // The order is still the current one; there is no previous order to predict from.
    assertNull(TraversalTreeCache.staleOrderForPrediction(child))
  }

  @Test
  fun staleOrderForPrediction_pivotOutsideThePreviousOrder_refuses() {
    val (root, _) = savedOrder()
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(root.windowId, Rect(500, 500, 600, 600)))
    val outsider = AccessibilityNodeInfoCompat.wrap(node(null))

    assertNull(TraversalTreeCache.staleOrderForPrediction(outsider))
  }

  @Test
  fun hasSavedOrder_reflectsTheCache() {
    assertFalse(TraversalTreeCache.hasSavedOrder())

    savedOrder()

    assertTrue(TraversalTreeCache.hasSavedOrder())
  }

  @Test
  fun settleBuild_firesAfterTheDelay_whenTheCacheIsEmpty() {
    val requested = mutableListOf<Int>()
    TraversalTreeCache.settleBuildRequester =
      TraversalTreeCache.SettleBuildRequester { requested.add(it) }

    TraversalTreeCache.onAccessibilityEvent(structuralEvent(7, Rect(0, 0, 10, 10)))
    shadowOf(Looper.getMainLooper()).idleFor(299, TimeUnit.MILLISECONDS)
    assertTrue(requested.isEmpty())

    shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MILLISECONDS)
    assertEquals(listOf(7), requested)
  }

  @Test
  fun settleBuild_doesNotFire_whenAnOrderWasSavedMeanwhile() {
    val requested = mutableListOf<Int>()
    TraversalTreeCache.settleBuildRequester =
      TraversalTreeCache.SettleBuildRequester { requested.add(it) }

    TraversalTreeCache.onAccessibilityEvent(structuralEvent(7, Rect(0, 0, 10, 10)))
    savedOrder()
    shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    assertTrue(requested.isEmpty())
  }

  @Test
  fun settleBuild_reArmsOnEachStructuralChange_whileTheCacheIsEmpty() {
    val requested = mutableListOf<Int>()
    TraversalTreeCache.settleBuildRequester =
      TraversalTreeCache.SettleBuildRequester { requested.add(it) }

    TraversalTreeCache.onAccessibilityEvent(structuralEvent(7, Rect(0, 0, 10, 10)))
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    TraversalTreeCache.onAccessibilityEvent(structuralEvent(7, Rect(20, 20, 30, 30)))
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    assertTrue(requested.isEmpty())

    shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS)
    assertEquals(listOf(7), requested)
  }

  @Test
  fun settleBuild_switchOff_neverFires() {
    val requested = mutableListOf<Int>()
    TraversalTreeCache.settleBuildRequester =
      TraversalTreeCache.SettleBuildRequester { requested.add(it) }
    TraversalTreeCache.settleRetryEnabled = false

    TraversalTreeCache.onAccessibilityEvent(structuralEvent(7, Rect(0, 0, 10, 10)))
    shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    assertTrue(requested.isEmpty())
  }

  @Test
  fun fastNavigatorSwitch_defaultsOn_andFollowsTheSetting() {
    assertTrue(TraversalTreeCache.fastNavigatorEnabled)
    TraversalTreeCache.fastNavigatorEnabled = false
    assertFalse(TraversalTreeCache.fastNavigatorEnabled)
  }

  @Test
  fun buildOrderOnTheSpot_buildsAndSavesTheOrder() {
    val (root, child) = savedOrder()
    TraversalTreeCache.clearCompletely("no order saved")

    assertTrue(TraversalTreeCache.buildOrderOnTheSpot(root))
    assertNotNull(TraversalTreeCache.get(root, child))
  }

  @Test
  fun focusNodeForNavigation_rememberedFocusInTheSavedOrder_isServed() {
    val (_, child) = savedOrder()
    child.unwrap().isAccessibilityFocused = true
    TraversalTreeCache.rememberFocus(child)

    assertEquals(child, TraversalTreeCache.focusNodeForNavigation())
  }

  @Test
  fun focusNodeForNavigation_noOrderSaved_servesNothing() {
    val rootInfo = node(null)
    val child = AccessibilityNodeInfoCompat.wrap(node(rootInfo))
    child.unwrap().isAccessibilityFocused = true
    TraversalTreeCache.rememberFocus(child)

    // Nothing was ever saved, so the remembered node belongs to no order the cache can vouch
    // for: a navigation must ask the app where the focus is, as it always did.
    assertNull(TraversalTreeCache.focusNodeForNavigation())
  }

  @Test
  fun focusNodeForNavigation_focusHasMovedOn_servesNothing() {
    val (_, child) = savedOrder()
    // The node is in the saved order, but it no longer reports itself as the focus.
    TraversalTreeCache.rememberFocus(child)

    assertNull(TraversalTreeCache.focusNodeForNavigation())
  }

  @Test
  fun focusNodeForNavigation_orderThrownAwaySince_servesNothing() {
    val (_, child) = savedOrder()
    child.unwrap().isAccessibilityFocused = true
    TraversalTreeCache.rememberFocus(child)
    TraversalTreeCache.clearCompletely("the window changed")

    assertNull(TraversalTreeCache.focusNodeForNavigation())
  }

  @Test
  fun focusNodeForNavigation_cheapCheck_servesTheRememberedFocusWithoutRefreshing() {
    val (_, child) = savedOrder()
    child.unwrap().isAccessibilityFocused = true
    TraversalTreeCache.rememberFocus(child)
    // The refresh would report the node gone; the cheap check never asks it, and serves the
    // focus on the local evidence: order not thrown away since, node held, flag still set.
    shadowOf(child.unwrap()).setRefreshReturnValue(false)

    assertEquals(child, TraversalTreeCache.focusNodeForNavigation())

    // With the cheap check off, the same node is put to the refresh, and refused.
    TraversalTreeCache.cheapPivotCheckEnabled = false
    assertNull(TraversalTreeCache.focusNodeForNavigation())
  }

  @Test
  fun focusNodeForNavigation_focusClearedSince_forgetsTheRememberedFocus() {
    val (root, child) = savedOrder()
    child.unwrap().isAccessibilityFocused = true
    TraversalTreeCache.rememberFocus(child)
    // The app clears the focus without landing it anywhere else. The event is addressed to
    // another window so the saved order itself is untouched: only the forgetting can stop
    // the remembered focus from serving.
    val event =
      eventInWindow(
        root.windowId + 1,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
      )
    shadowOf(event).setSourceNode(child.unwrap())
    TraversalTreeCache.onAccessibilityEvent(event)

    assertNull(TraversalTreeCache.focusNodeForNavigation())
  }
}
