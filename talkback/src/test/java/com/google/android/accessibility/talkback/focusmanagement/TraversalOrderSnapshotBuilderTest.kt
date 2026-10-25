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

  private companion object {
    const val WAIT_MS = 5_000L
  }
}
