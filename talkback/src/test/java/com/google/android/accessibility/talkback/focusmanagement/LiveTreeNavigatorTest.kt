/*
 * Copyright 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.focusmanagement

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The live-tree pattern engine, pinned against synthetic trees: the next and previous
 * stop in reading order, geometric ordering across rows (with the row tolerance, and
 * reversed within a row for RTL), the walk ending at the first stop after the pivot
 * without reading further, web content handed back untouched, and list items stepped
 * through one by one with the list's edge handed back for the original navigation —
 * and its scrolling — to answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveTreeNavigatorTest {

  private val navigator = LiveTreeNavigator()

  /** A stop in these trees: a visible, focusable node. Texts name the stops. */
  private val stopFilter =
    Filter.node { node: AccessibilityNodeInfoCompat -> node.isFocusable && node.isVisibleToUser }

  private fun node(
    parent: AccessibilityNodeInfo?,
    text: String,
    rect: Rect,
    className: String = "android.widget.Button",
    focusable: Boolean = true,
  ): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo.obtain()
    node.className = className
    node.packageName = "com.example.test"
    node.isVisibleToUser = true
    node.isFocusable = focusable
    node.isClickable = focusable
    node.text = text
    node.setBoundsInScreen(rect)
    parent?.let { shadowOf(it).addChild(node) }
    return node
  }

  private fun root(): AccessibilityNodeInfo =
    node(null, "", Rect(0, 0, 600, 1000), "android.widget.FrameLayout", focusable = false)

  private fun find(
    pivot: AccessibilityNodeInfo,
    forward: Boolean,
    rtl: Boolean = false,
    filter: Filter<AccessibilityNodeInfoCompat> = stopFilter,
  ): String? =
    navigator
      .findTarget(AccessibilityNodeInfoCompat.wrap(pivot), forward, rtl, filter)
      ?.text
      ?.toString()

  /** A plain column of three buttons a, b, c under one root. */
  private fun column(): List<AccessibilityNodeInfo> {
    val root = root()
    return listOf(
      node(root, "a", Rect(0, 0, 600, 100)),
      node(root, "b", Rect(0, 100, 600, 200)),
      node(root, "c", Rect(0, 200, 600, 300)),
    )
  }

  @Test
  fun forwardFindsNextStop() {
    val (a, b, _) = column()
    assertEquals("b", find(a, forward = true))
    assertEquals("c", find(b, forward = true))
  }

  @Test
  fun backwardFindsPreviousStop() {
    val (_, b, c) = column()
    assertEquals("b", find(c, forward = false))
    assertEquals("a", find(b, forward = false))
  }

  @Test
  fun atTheEndsTheSwipeIsHandedBack() {
    val (a, _, c) = column()
    assertNull(find(c, forward = true))
    assertNull(find(a, forward = false))
  }

  @Test
  fun geometricOrderGroupsRowsWithinTheTolerance() {
    val root = root()
    // Added out of order on purpose. rowB and rowC share a row (their tops differ by
    // 10 px, inside the tolerance), so reading order is rowC, rowB despite rowC's
    // later top; nextA and nextD form the row below.
    node(root, "nextD", Rect(250, 200, 450, 300))
    val rowB = node(root, "rowB", Rect(200, 0, 400, 90))
    node(root, "nextA", Rect(0, 200, 200, 300))
    val rowC = node(root, "rowC", Rect(0, 10, 200, 100))
    assertEquals("rowB", find(rowC, forward = true))
    assertEquals("nextA", find(rowB, forward = true))
  }

  @Test
  fun rtlReversesTheOrderWithinARow() {
    val root = root()
    val rowB = node(root, "rowB", Rect(200, 0, 400, 90))
    val rowC = node(root, "rowC", Rect(0, 10, 200, 100))
    node(root, "nextA", Rect(0, 200, 200, 300))
    node(root, "nextD", Rect(250, 200, 450, 300))
    // Rightmost first within each row; the rows still run top to bottom.
    assertEquals("rowC", find(rowB, forward = true, rtl = true))
    assertEquals("nextD", find(rowC, forward = true, rtl = true))
  }

  @Test
  fun theWalkStopsAtTheAnswer() {
    val guardingFilter =
      Filter.node { node: AccessibilityNodeInfoCompat ->
        if (node.text?.toString() == "poison") {
          throw AssertionError("the walk read a node it did not need")
        }
        node.isFocusable && node.isVisibleToUser
      }
    val root = root()
    node(root, "poison", Rect(0, 0, 600, 100))
    val pivot = node(root, "pivot", Rect(0, 100, 600, 200))
    node(root, "target", Rect(0, 200, 600, 300))
    node(root, "poison", Rect(0, 300, 600, 400))
    // Nodes before the pivot are passed without the filter, and the first stop after
    // the pivot ends the walk: neither poison node is ever read.
    assertEquals("target", find(pivot, forward = true, filter = guardingFilter))
  }

  @Test
  fun webPivotIsHandedBack() {
    val root = root()
    val web = node(root, "web", Rect(0, 0, 600, 100), "android.webkit.WebView")
    node(root, "b", Rect(0, 100, 600, 200))
    assertNull(find(web, forward = true))
  }

  @Test
  fun webSubtreeIsSkipped() {
    val root = root()
    val a = node(root, "a", Rect(0, 0, 600, 100))
    val web =
      node(root, "", Rect(0, 100, 600, 300), "android.webkit.WebView", focusable = false)
    node(web, "webInner", Rect(0, 100, 600, 200))
    node(root, "b", Rect(0, 300, 600, 400))
    assertEquals("b", find(a, forward = true))
  }

  @Test
  fun stopsInsideContainersAreFoundAcrossTheBoundary() {
    val root = root()
    val box =
      node(root, "", Rect(0, 0, 600, 200), "android.widget.FrameLayout", focusable = false)
    val deep = node(box, "deep", Rect(0, 0, 600, 100))
    val after = node(root, "after", Rect(0, 200, 600, 300))
    assertEquals("after", find(deep, forward = true))
    assertEquals("deep", find(after, forward = false))
  }

  /** A two-item list (one button per item) with a trailing button after the list. */
  private fun listTree(): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
    val root = root()
    val list =
      node(root, "", Rect(0, 0, 600, 200), "android.widget.ListView", focusable = false)
    val item0 =
      node(list, "", Rect(0, 0, 600, 100), "android.widget.FrameLayout", focusable = false)
    val a = node(item0, "a", Rect(0, 0, 600, 100))
    val item1 =
      node(list, "", Rect(0, 100, 600, 200), "android.widget.FrameLayout", focusable = false)
    val b = node(item1, "b", Rect(0, 100, 600, 200))
    node(root, "trailing", Rect(0, 200, 600, 300))
    return a to b
  }

  @Test
  fun listStepsFromItemToItem() {
    val (a, _) = listTree()
    assertEquals("b", find(a, forward = true))
  }

  @Test
  fun listEdgeIsHandedBackNotAnsweredWithWhatFollowsTheList() {
    val (a, b) = listTree()
    // At the last item the engine hands back: the original navigation decides whether
    // the list scrolls or the trailing stop is next, exactly as with the switch off.
    assertNull(find(b, forward = true))
    assertNull(find(a, forward = false))
  }

  @Test
  fun listRemainderOfTheOwnItemComesBeforeTheNextItem() {
    val root = root()
    val list =
      node(root, "", Rect(0, 0, 600, 200), "android.widget.ListView", focusable = false)
    val item0 =
      node(list, "", Rect(0, 0, 600, 100), "android.widget.FrameLayout", focusable = false)
    val a1 = node(item0, "a1", Rect(0, 0, 600, 50))
    node(item0, "a2", Rect(0, 50, 600, 100))
    val item1 =
      node(list, "", Rect(0, 100, 600, 200), "android.widget.FrameLayout", focusable = false)
    node(item1, "b", Rect(0, 100, 600, 200))
    assertEquals("a2", find(a1, forward = true))
  }

  @Test
  fun listBackwardTakesTheLastStopOfThePreviousItem() {
    val root = root()
    val list =
      node(root, "", Rect(0, 0, 600, 200), "android.widget.ListView", focusable = false)
    val item0 =
      node(list, "", Rect(0, 0, 600, 100), "android.widget.FrameLayout", focusable = false)
    node(item0, "a1", Rect(0, 0, 600, 50))
    node(item0, "a2", Rect(0, 50, 600, 100))
    val item1 =
      node(list, "", Rect(0, 100, 600, 200), "android.widget.FrameLayout", focusable = false)
    val b = node(item1, "b", Rect(0, 100, 600, 200))
    assertEquals("a2", find(b, forward = false))
  }

  @Test
  fun navigatorSwitchDefaultsOn() {
    // The owner's standing order for the merged build: the live-tree navigator is on unless
    // the user turns its switch off. The gate's field starts on, and so does the default
    // the service reads for the switch's preference.
    assertTrue(TraversalTreeCache.fastNavigatorEnabled)
    assertTrue(
      RuntimeEnvironment.getApplication().resources.getBoolean(R.bool.pref_fast_navigator_default)
    )
  }
}
