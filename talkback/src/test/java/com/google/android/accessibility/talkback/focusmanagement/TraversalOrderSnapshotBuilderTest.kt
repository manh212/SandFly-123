/*
 * Copyright 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 (the "License"); you may not
 * use this file except in compliance with the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A snapshot is only worth anything if the order it publishes is the order a swipe would have
 * built itself, and only safe if a build that the window has already moved past can never land
 * in the cache. Both are pinned here against synthetic trees whose reading order differs from
 * their child order, so the comparison cannot pass vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraversalOrderSnapshotBuilderTest {

  @Before
  fun setUp() {
    // Navigations in other test classes share this sandbox, and the test clock does not
    // advance on its own: without this, a navigation remembered from another class would
    // still count as a burst here, and asks would defer that were never meant to.
    TraversalTreeCache.noteNavigationActivity(0L)
  }

  @After
  fun tearDown() {
    TraversalTreeCache.clear("test")
    TraversalTreeCache.noteNavigationActivity(0L)
  }

  private fun taggedNode(
    parent: AccessibilityNodeInfo?,
    tag: String,
    bounds: Rect,
  ): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    node.className = "android.widget.Button"
    node.isVisibleToUser = true
    node.isFocusable = true
    node.isClickable = true
    node.text = tag
    node.setBoundsInScreen(bounds)
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }

  /**
   * A tree whose reading order is not its child order: within the box, "B" is added first, but
   * "A" declares itself before "B", so the builder's reorder pass must move it ahead.
   */
  private fun syntheticTree(): AccessibilityNodeInfo {
    val root = taggedNode(null, "root", Rect(0, 0, 600, 600))
    val box = taggedNode(root, "box", Rect(0, 0, 600, 300))
    val b = taggedNode(box, "B", Rect(300, 0, 600, 300))
    val a = taggedNode(box, "A", Rect(0, 0, 300, 300))
    shadowOf(a).setTraversalBefore(b)
    taggedNode(root, "C", Rect(0, 300, 600, 600))
    return root
  }

  private fun orderOf(strategy: OrderedTraversalStrategy): List<String> =
    strategy.dumpTree().map { it.text.toString() }

  /** A structural content change in [windowId] whose source sits in [sourceBounds]. */
  private fun structuralEvent(windowId: Int, sourceBounds: Rect): AccessibilityEvent {
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
    shadowOf(event).setWindowId(windowId)
    val source = AccessibilityNodeInfo.obtain()
    source.setBoundsInScreen(sourceBounds)
    shadowOf(event).setSourceNode(source)
    return event
  }

  private fun waitUntil(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      if (condition()) {
        return
      }
      Thread.sleep(10)
    }
    fail("Timed out waiting for: $description")
  }

  @Test
  fun buildNow_publishesAnOrderIdenticalToASynchronousBuild() {
    val expected = orderOf(OrderedTraversalStrategy(AccessibilityNodeInfoCompat.wrap(syntheticTree())))

    val snapshotRoot = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(snapshotRoot.getChild(0)).getChild(0)
    TraversalOrderSnapshotBuilder.buildNow(
      snapshotRoot.windowId,
      TraversalTreeCache.currentGeneration(),
      { snapshotRoot },
      ownPackage = "com.example.other",
    )

    val snapshot = TraversalTreeCache.get(snapshotRoot, checkNotNull(pivot))
    assertNotNull(snapshot)
    assertEquals(expected, orderOf(snapshot as OrderedTraversalStrategy))
    // The synthetic tree only proves anything if its order really was re-sorted.
    assertEquals(listOf("root", "box", "A", "B", "C"), expected)
  }

  @Test
  fun request_duringASwipeBurst_isHeld_andTheBuildStillRunsWhenTheBurstPasses() {
    // A swipe just landed: asks are inside the burst window.
    TraversalTreeCache.noteNavigationActivity()
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(root.getChild(0)).getChild(0)

    val outcome =
      TraversalOrderSnapshotBuilder.request(
        root.windowId,
        TraversalTreeCache.currentGeneration(),
        { root },
        ownPackage = "com.example.other",
      )

    assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_DEFERRED, outcome)
    // Held means held: nothing has been built or published for the window yet.
    assertNull(TraversalTreeCache.get(root, pivot))

    // The burst passes, the hold lifts, and the build still runs and lands: the last ask of
    // a burst is deferred, never dropped.
    shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
    waitUntil("the deferred build published") {
      TraversalTreeCache.get(root, pivot) != null
    }
  }

  @Test
  fun buildNow_windowMovedOnDuringTheBuild_publishesNothing() {
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(root.getChild(0)).getChild(0)
    val generation = TraversalTreeCache.currentGeneration()

    TraversalOrderSnapshotBuilder.buildNow(
      root.windowId,
      generation,
      {
        TraversalTreeCache.clear("the window changed while building")
        root
      },
      ownPackage = "com.example.other",
    )

    assertNull(TraversalTreeCache.get(root, checkNotNull(pivot)))
  }

  @Test
  fun request_buildsAndSavesTheOrderWithoutAWaitingSwipe() {
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(root.getChild(0)).getChild(0)

    TraversalOrderSnapshotBuilder.request(
      root.windowId,
      TraversalTreeCache.currentGeneration(),
      { root },
      ownPackage = "com.example.other",
    )

    waitUntil("the snapshot to be saved") { TraversalTreeCache.holds(checkNotNull(pivot)) }
    assertNotNull(TraversalTreeCache.get(root, checkNotNull(pivot)))
  }

  @Test
  fun request_newerRequestSupersedesABuildStillReading() {
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstRoot = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    TraversalOrderSnapshotBuilder.request(
      firstRoot.windowId,
      TraversalTreeCache.currentGeneration(),
      {
        firstStarted.countDown()
        releaseFirst.await(WAIT_MS, TimeUnit.MILLISECONDS)
        firstRoot
      },
      ownPackage = "com.example.other",
    )
    assertTrue(firstStarted.await(WAIT_MS, TimeUnit.MILLISECONDS))

    // The window changes while the first build is still reading: its generation retires, and
    // the new request queues behind it as the only one still worth building.
    TraversalTreeCache.clear("the window changed")
    val secondRoot = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(secondRoot.getChild(0)).getChild(0)
    TraversalOrderSnapshotBuilder.request(
      secondRoot.windowId,
      TraversalTreeCache.currentGeneration(),
      { secondRoot },
      ownPackage = "com.example.other",
    )
    releaseFirst.countDown()

    waitUntil("the second snapshot to be saved") { TraversalTreeCache.holds(checkNotNull(pivot)) }
    assertNotNull(TraversalTreeCache.get(secondRoot, checkNotNull(pivot)))
  }

  @Test
  fun request_withAStaleGeneration_isSkippedWithoutTouchingTheSupplier() {
    val performance = Performance.getInstance()
    performance.setComputeStatsEnabled(true)
    val diedBefore = performance.getOrderBuildDiedCount()
    val supplierCalled = java.util.concurrent.atomic.AtomicBoolean(false)
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())

    TraversalOrderSnapshotBuilder.request(
      root.windowId,
      TraversalTreeCache.currentGeneration() + 999,
      {
        supplierCalled.set(true)
        root
      },
      ownPackage = "com.example.other",
    )

    waitUntil("the stale request to be counted as died") {
      performance.getOrderBuildDiedCount() > diedBefore
    }
    assertTrue(!supplierCalled.get())
  }

  @Test
  fun request_listenerAsksWhileABuildRuns_areFolded_andTheLastBuildIsNotLost() {
    val performance = Performance.getInstance()
    performance.setComputeStatsEnabled(true)
    val coalescedBefore = performance.getListenerBuildsCoalescedCount()

    // A listener ask whose build stays busy reading while the window keeps changing.
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstRoot = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val firstOutcome =
      TraversalOrderSnapshotBuilder.request(
        firstRoot.windowId,
        TraversalTreeCache.currentGeneration(),
        {
          firstStarted.countDown()
          releaseFirst.await(WAIT_MS, TimeUnit.MILLISECONDS)
          firstRoot
        },
        ownPackage = "com.example.other",
        source = Performance.ORDER_BUILD_SOURCE_LISTENER,
      )
    assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_ACCEPTED, firstOutcome)
    assertTrue(firstStarted.await(WAIT_MS, TimeUnit.MILLISECONDS))

    // The window changes and asks again while the first build is still running: that ask
    // folds into the build on its way too — a build for this window is already owed, and the
    // fold is what keeps the follow-up to exactly one.
    TraversalTreeCache.clear("the window changed")
    val secondRoot = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val secondOutcome =
      TraversalOrderSnapshotBuilder.request(
        secondRoot.windowId,
        TraversalTreeCache.currentGeneration(),
        { secondRoot },
        ownPackage = "com.example.other",
        source = Performance.ORDER_BUILD_SOURCE_LISTENER,
      )
    assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_COALESCED, secondOutcome)

    // The window changes twice more while the build is still running: those asks fold as
    // well, each replacing the folded ask before it, and only the newest folded ask's
    // follow-up build is owed.
    TraversalTreeCache.clear("the window changed again")
    val thirdOutcome =
      TraversalOrderSnapshotBuilder.request(
        secondRoot.windowId,
        TraversalTreeCache.currentGeneration(),
        { secondRoot },
        ownPackage = "com.example.other",
        source = Performance.ORDER_BUILD_SOURCE_LISTENER,
      )
    TraversalTreeCache.clear("the window changed once more")
    val fourthRoot = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val fourthPivot = checkNotNull(fourthRoot.getChild(0)).getChild(0)
    val fourthOutcome =
      TraversalOrderSnapshotBuilder.request(
        fourthRoot.windowId,
        TraversalTreeCache.currentGeneration(),
        { fourthRoot },
        ownPackage = "com.example.other",
        source = Performance.ORDER_BUILD_SOURCE_LISTENER,
      )
    assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_COALESCED, thirdOutcome)
    assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_COALESCED, fourthOutcome)
    assertEquals(coalescedBefore + 3, performance.getListenerBuildsCoalescedCount())

    releaseFirst.countDown()

    // The running build is stale by now and dies without building; the folded asks still
    // get their build, from the newest tree, and it publishes.
    waitUntil("the follow-up build to be saved") {
      TraversalTreeCache.holds(checkNotNull(fourthPivot))
    }
    assertNotNull(TraversalTreeCache.get(fourthRoot, checkNotNull(fourthPivot)))
  }

  @Test
  fun buildNow_forced_publishesHoweverTheWindowMovedDuringTheBuild() {
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(root.getChild(0)).getChild(0)
    val generation = TraversalTreeCache.currentGeneration()

    // The same build that publishes nothing unforced (see
    // buildNow_windowMovedOnDuringTheBuild_publishesNothing): under escalation it lands,
    // because a window moving faster than a build can finish is the starvation it answers.
    val published =
      TraversalOrderSnapshotBuilder.buildNow(
        root.windowId,
        generation,
        {
          TraversalTreeCache.clear("the window changed while building")
          root
        },
        ownPackage = "com.example.other",
        forcePublish = true,
      )

    assertTrue(published)
    assertNotNull(TraversalTreeCache.get(root, checkNotNull(pivot)))
  }

  @Test
  fun repeatedBuildDeaths_armTheEscalation_andSpendingItDisarms() {
    val performance = Performance.getInstance()
    performance.setComputeStatsEnabled(true)
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val windowId = root.windowId
    // A clean run for this window, whatever earlier tests in the sandbox left behind.
    TraversalOrderSnapshotBuilder.consumeEscalation(windowId)
    assertFalse(TraversalOrderSnapshotBuilder.escalationArmed(windowId))

    // Asks carrying a generation the window has already moved past die in the drain without
    // ever reading the tree; three in a row for the same window arm the escalation.
    val diedBefore = performance.getOrderBuildDiedCount()
    repeat(3) { deaths ->
      TraversalOrderSnapshotBuilder.request(
        windowId,
        // A different moved-past generation each time, so no ask can fold into the last.
        TraversalTreeCache.currentGeneration() + 999 - deaths,
        { root },
        ownPackage = "com.example.other",
      )
      waitUntil("death ${deaths + 1} to be counted") {
        performance.getOrderBuildDiedCount() >= diedBefore + deaths + 1
      }
    }
    waitUntil("the escalation to arm") {
      TraversalOrderSnapshotBuilder.escalationArmed(windowId)
    }

    // One forced build per arming: spending it disarms until deaths pile up again.
    TraversalOrderSnapshotBuilder.consumeEscalation(windowId)
    assertFalse(TraversalOrderSnapshotBuilder.escalationArmed(windowId))
  }

  @Test
  fun request_firstAskAfterAStructuralExpiry_goesAheadInsideABurst() {
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(root.getChild(0)).getChild(0)
    // A published order, set aside by one structural change and expired by a second one:
    // the state the cache arms its deferral exemption from.
    TraversalTreeCache.put(root, OrderedTraversalStrategy(root))
    TraversalTreeCache.onAccessibilityEvent(
      structuralEvent(root.windowId, Rect(500, 500, 600, 600))
    )
    TraversalTreeCache.onAccessibilityEvent(
      structuralEvent(root.windowId, Rect(700, 700, 800, 800))
    )

    // A swipe just landed, so asks are inside the burst window — where they are held by
    // default (see request_duringASwipeBurst_isHeld...). The first ask for the expired
    // window goes ahead instead, and its build lands.
    TraversalTreeCache.noteNavigationActivity()
    val outcome =
      TraversalOrderSnapshotBuilder.request(
        root.windowId,
        TraversalTreeCache.currentGeneration(),
        { root },
        ownPackage = "com.example.other",
      )

    assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_ACCEPTED, outcome)
    waitUntil("the exempted build to be saved") { TraversalTreeCache.holds(checkNotNull(pivot)) }
  }

  @Test
  fun buildNow_withAStaleGeneration_publishesNothing_andCountsTheDeath() {
    val performance = Performance.getInstance()
    performance.setComputeStatsEnabled(true)
    val diedBefore = performance.getOrderBuildDiedCount()
    val root = AccessibilityNodeInfoCompat.wrap(syntheticTree())
    val pivot = checkNotNull(root.getChild(0)).getChild(0)

    TraversalOrderSnapshotBuilder.buildNow(
      root.windowId,
      TraversalTreeCache.currentGeneration() + 999,
      { root },
      ownPackage = "com.example.other",
    )

    assertNull(TraversalTreeCache.get(root, checkNotNull(pivot)))
    assertEquals(diedBefore + 1, performance.getOrderBuildDiedCount())
  }

  private companion object {
    const val WAIT_MS = 5_000L
  }
}
