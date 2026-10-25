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
import java.util.concurrent.TimeUnit
import org.junit.After
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
    TraversalTreeCache.clear("test")
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
}
