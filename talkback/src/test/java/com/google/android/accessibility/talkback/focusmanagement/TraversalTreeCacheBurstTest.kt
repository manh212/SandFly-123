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

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.Performance
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The burst signal and the burst build guard: while swipes arrive in quick succession, no
 * reading order is built synchronously on the swipe's own thread — the work that made
 * "on" slower than "off" on the owner's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraversalTreeCacheBurstTest {

  @Before
  fun setUp() {
    Performance.getInstance().setComputeStatsEnabled(true)
    Performance.getInstance().clearAllStats()
  }

  @After
  fun tearDown() {
    TraversalTreeCache.clearCompletely("test")
    TraversalTreeCache.noteNavigationActivity(0L)
    TraversalTreeCache.noteNavigationActivity(0L)
    Performance.getInstance().setComputeStatsEnabled(false)
    Performance.getInstance().clearAllStats()
  }

  @Test
  fun burst_twoCloseNavigationsInsideTheWindow_isBurst() {
    assertTrue(TraversalTreeCache.isNavigationBurst(1_000, 1_100, 1_200))
    assertTrue(TraversalTreeCache.isNavigationBurst(1_000, 1_299, 1_400))
  }

  @Test
  fun burst_aSingleQuickSwipeAfterAPause_isNotBurst() {
    // No navigation before the last one: the cadence is not established yet.
    assertFalse(TraversalTreeCache.isNavigationBurst(0, 1_000, 1_100))
    // A gap of exactly the window already counts as reading pace.
    assertFalse(TraversalTreeCache.isNavigationBurst(1_000, 1_300, 1_400))
    assertFalse(TraversalTreeCache.isNavigationBurst(1_000, 6_000, 6_100))
  }

  @Test
  fun burst_hasPassedOnceTheLastNavigationAgesOut() {
    assertFalse(TraversalTreeCache.isNavigationBurst(1_000, 1_100, 1_400))
    assertFalse(TraversalTreeCache.isNavigationBurst(1_000, 1_100, 5_000))
  }

  @Test
  fun burst_outOfOrderTimestamps_areNotBurst() {
    assertFalse(TraversalTreeCache.isNavigationBurst(2_000, 1_000, 1_100))
    assertFalse(TraversalTreeCache.isNavigationBurst(1_000, 1_000, 1_100))
  }

  @Test
  fun noteNavigationActivity_shiftsThePair() {
    TraversalTreeCache.noteNavigationActivity(1_000)
    TraversalTreeCache.noteNavigationActivity(1_150)

    assertEquals(1_000L, TraversalTreeCache.previousNavigationTimeMs())
    assertEquals(1_150L, TraversalTreeCache.lastNavigationTimeMs())
    assertTrue(TraversalTreeCache.inNavigationBurst(1_200))
    assertFalse(TraversalTreeCache.inNavigationBurst(1_500))
  }

  @Test
  fun pivotAfterRemoval_inBurst_buildsNothingAndAnswersNull() {
    val (root, pivot) = treeWithRemovedPivot()
    TraversalTreeCache.rememberPivot(pivot)
    // A live burst on the real clock: two navigations close together, just now.
    ShadowSystemClock.advanceBy(Duration.ofMillis(5_000))
    val now = SystemClock.uptimeMillis()
    TraversalTreeCache.noteNavigationActivity(now - 100)
    TraversalTreeCache.noteNavigationActivity(now - 10)
    assertTrue(TraversalTreeCache.inNavigationBurst())

    assertNull(TraversalTreeCache.pivotAfterRemoval(root))

    // The refusal is the point: no synchronous build was paid for on the swipe path.
    val performance = Performance.getInstance()
    assertEquals(
      0,
      performance.getOrderBuildsOnSwipePathByBucket(Performance.SWIPE_BUCKET_BURST) +
        performance.getOrderBuildsOnSwipePathByBucket(Performance.SWIPE_BUCKET_SINGLE),
    )
  }

  @Test
  fun pivotAfterRemoval_atReadingPace_buildsAsBefore() {
    val (root, pivot) = treeWithRemovedPivot()
    TraversalTreeCache.rememberPivot(pivot)
    // Navigations a full second apart: no burst, whatever the clock says.
    TraversalTreeCache.noteNavigationActivity(1_000)
    TraversalTreeCache.noteNavigationActivity(2_000)
    assertFalse(TraversalTreeCache.inNavigationBurst())

    val above = TraversalTreeCache.pivotAfterRemoval(root)

    assertNotNull(above)
    val bounds = Rect()
    above!!.getBoundsInScreen(bounds)
    assertTrue(bounds.top < 100)
    assertEquals(
      1,
      Performance.getInstance()
        .getOrderBuildsOnSwipePathByBucket(Performance.SWIPE_BUCKET_SINGLE),
    )
  }

  /**
   * A root in window 777 with children above and below the remembered pivot's place; the
   * pivot itself is a node of the same window that is no longer in the tree, as after a
   * removal. No order is saved anywhere, so [TraversalTreeCache.pivotAfterRemoval] can
   * only answer by building one — the build the burst guard exists to refuse.
   */
  private fun treeWithRemovedPivot():
    Pair<AccessibilityNodeInfoCompat, AccessibilityNodeInfoCompat> {
    val window = AccessibilityWindowInfo()
    shadowOf(window).setId(777)
    val rootInfo = node(null, Rect(0, 0, 100, 400), window)
    node(rootInfo, Rect(0, 0, 100, 100), window)
    node(rootInfo, Rect(0, 200, 100, 300), window)
    val pivotInfo = node(null, Rect(0, 100, 100, 200), window)
    return AccessibilityNodeInfoCompat.wrap(rootInfo) to
      AccessibilityNodeInfoCompat.wrap(pivotInfo)
  }

  private fun node(
    parent: AccessibilityNodeInfo?,
    bounds: Rect,
    window: AccessibilityWindowInfo,
  ): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    node.className = "android.widget.Button"
    node.isVisibleToUser = true
    node.isFocusable = true
    node.isClickable = true
    node.setBoundsInScreen(bounds)
    shadowOf(node).setAccessibilityWindowInfo(window)
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }
}
