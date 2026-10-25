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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

  @After
  fun tearDown() {
    TraversalTreeCache.clear("test")
    TraversalTreeCache.listenerBuildCoalescingEnabled = true
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
    TraversalTreeCache.listenerBuildCoalescingEnabled = true

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

    // The window changes and asks again: that ask waits behind the running build.
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
    assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_ACCEPTED, secondOutcome)

    // The window changes twice more while both builds are still owed: those asks fold into
    // the build on its way instead of replacing the waiting one, and only the newest folded
    // ask's follow-up build is owed.
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
    assertEquals(coalescedBefore + 2, performance.getListenerBuildsCoalescedCount())

    releaseFirst.countDown()

    // The running build and the waiting one are both stale by now and die without building;
    // the folded asks still get their build, from the newest tree, and it publishes.
    waitUntil("the follow-up build to be saved") {
      TraversalTreeCache.holds(checkNotNull(fourthPivot))
    }
    assertNotNull(TraversalTreeCache.get(fourthRoot, checkNotNull(fourthPivot)))
  }

  @Test
  fun request_coalescingOff_listenerAsksReplaceTheWaitingBuildAsBefore() {
    TraversalTreeCache.listenerBuildCoalescingEnabled = false
    try {
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
        source = Performance.ORDER_BUILD_SOURCE_LISTENER,
      )
      assertTrue(firstStarted.await(WAIT_MS, TimeUnit.MILLISECONDS))

      TraversalTreeCache.clear("the window changed")
      val secondRoot = AccessibilityNodeInfoCompat.wrap(syntheticTree())
      TraversalOrderSnapshotBuilder.request(
        secondRoot.windowId,
        TraversalTreeCache.currentGeneration(),
        { secondRoot },
        ownPackage = "com.example.other",
        source = Performance.ORDER_BUILD_SOURCE_LISTENER,
      )
      TraversalTreeCache.clear("the window changed again")
      val thirdOutcome =
        TraversalOrderSnapshotBuilder.request(
          secondRoot.windowId,
          TraversalTreeCache.currentGeneration(),
          { secondRoot },
          ownPackage = "com.example.other",
          source = Performance.ORDER_BUILD_SOURCE_LISTENER,
        )
      assertEquals(TraversalOrderSnapshotBuilder.OUTCOME_ACCEPTED, thirdOutcome)
      releaseFirst.countDown()

      // Let the builder finish before the next test asks it for anything.
      val pivot = checkNotNull(secondRoot.getChild(0)).getChild(0)
      waitUntil("the last build to be saved") { TraversalTreeCache.holds(checkNotNull(pivot)) }
    } finally {
      TraversalTreeCache.listenerBuildCoalescingEnabled = true
    }
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
