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

package com.google.android.accessibility.talkback.focusmanagement

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.VisibleForTesting
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.function.Supplier

/**
 * Keeps the reading order of the window that the user last swiped in, so that swiping through a
 * screen that has not changed does not ask the app for every node again. Any event that can change
 * that window throws the order away, the same way the framework's own node cache is kept current.
 *
 * Filter logcat by the "BacktalkTreeCache" tag in debug builds to see how often the order is reused.
 */
object TraversalTreeCache {
  private const val TAG = "BacktalkTreeCache"

  /** Events that never change the nodes or their order. */
  internal const val IGNORED_EVENT_TYPES =
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED or
      AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or
      AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
      AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or
      AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
      AccessibilityEvent.TYPE_GESTURE_DETECTION_START or
      AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
      AccessibilityEvent.TYPE_ANNOUNCEMENT or
      AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE or
      AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY or
      AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT or
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

  /**
   * Content changes that leave the nodes and their order as they are, such as a progress bar or a
   * clock that ticks. An app that shows one sends these events all the time, which would otherwise
   * throw the order away before every swipe. A change of description is one of them: the order
   * follows the tree's structure, bounds and flags, never what a node says, so a new description
   * (a "typing…" line, a read tick in a chat app) cannot move a node within it.
   */
  internal const val NON_STRUCTURAL_CHANGES =
    AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
      AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION or
      AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION

  /** How many graced change regions are remembered before their reach is given up as unknown. */
  private const val MAX_GRACED_CHANGE_REGIONS = 8

  /**
   * How long the order is kept after one of those changes. A text change can, rarely, make a node
   * speak that did not before, so the order is not trusted for long after one.
   */
  private const val MAX_AGE_AFTER_TEXT_CHANGE_MS = 1500L

  /** Node actions that only move focus, and so do not change the nodes or their order. */
  internal val FOCUS_ACTIONS =
    setOf(
      AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_CLEAR_FOCUS,
    )

  /**
   * Showing a node on screen only scrolls when the node is near the edge, which on a round watch
   * screen is nearly every swipe, and a scroll sends its own event, which throws the order away if
   * it arrives. Throwing it away for the action as well rebuilt the order on every swipe there.
   */
  private val SHOW_ON_SCREEN = AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id

  private const val NO_WINDOW_ID = -1

  /**
   * Guards the saved order against the one writer that is not on the main thread: a snapshot
   * build publishing its result. Every other use stays on the main thread, as before.
   */
  private val stateLock = Any()

  @Volatile private var root: AccessibilityNodeInfoCompat? = null
  @Volatile private var strategy: OrderedTraversalStrategy? = null
  @Volatile private var lastClearReason = "start"
  @Volatile private var firstIgnoredChangeTime = 0L

  /**
   * Whether a graced change that did not touch a node leaves that node current. When false,
   * [holdsCurrent] behaves as it did before regions were remembered: any graced change in the
   * window makes every node in it stale.
   */
  @JvmStatic var unrelatedChangesSpareNodes: Boolean = true

  /** Regions of the graced changes since the order was saved, as window id to bounds in screen. */
  private val gracedChangeRegions = ArrayList<Pair<Int, Rect>>()

  /** Whether a graced change since the order was saved has a region that is not known. */
  private var gracedChangeUnknown = false
  private var hits = 0
  private var misses = 0

  /**
   * Bumped by every throw-away and every save of the order. A background build captures it when
   * it is asked for and may only publish if it is still current when the build finishes, so an
   * order read from a tree that has since changed is never handed to a swipe.
   */
  @Volatile private var generation = 0L

  /** The window the newest background build was asked for, or [NO_WINDOW_ID] before any. */
  @Volatile private var snapshotBuildWindowId = NO_WINDOW_ID

  /** Where the last navigation started, so a removed node does not send the user to the top. */
  private var lastPivotTop = Int.MIN_VALUE
  private var lastPivotWindowId = NO_WINDOW_ID

  /** Returns the saved order of [root] if it has one and it contains [pivot]. */
  @JvmStatic
  fun get(
    root: AccessibilityNodeInfoCompat,
    pivot: AccessibilityNodeInfoCompat,
  ): OrderedTraversalStrategy? {
    rememberPivot(pivot)
    var graceExpired = false
    if (
      firstIgnoredChangeTime != 0L &&
        SystemClock.uptimeMillis() - firstIgnoredChangeTime > MAX_AGE_AFTER_TEXT_CHANGE_MS
    ) {
      clear("text changes")
      graceExpired = true
    }
    val (savedRoot, saved) = synchronized(stateLock) { this.root to strategy }
    val result = saved?.takeIf { root == savedRoot && it.containsNode(pivot) }
    if (result == null) {
      val reason =
        when {
          graceExpired -> Performance.ORDER_MISS_GRACE_EXPIRED
          saved == null -> Performance.ORDER_MISS_NO_SAVED_ORDER
          root != savedRoot -> Performance.ORDER_MISS_DIFFERENT_WINDOW
          else -> Performance.ORDER_MISS_PIVOT_NOT_IN_ORDER
        }
      Performance.getInstance().onOrderCacheMiss(reason)
      if (LogUtils.shouldLog(Log.DEBUG)) {
        misses++
        val detail = if (saved == null) "cleared by $lastClearReason" else reason
        Log.d(TAG, "Building order, $detail ($hits reused, $misses built)")
      }
    } else if (LogUtils.shouldLog(Log.DEBUG)) {
      hits++
      Log.d(TAG, "Reused order ($hits reused, $misses built)")
    }
    return result
  }

  /**
   * Whether the saved order holds [node]. Nothing that could remove a node from the window has
   * happened since the order was saved, so the node is still there.
   */
  @JvmStatic
  fun holds(node: AccessibilityNodeInfoCompat): Boolean =
    synchronized(stateLock) { strategy }?.containsNode(node) == true

  /**
   * Whether the saved order holds [node] as the app has it now: no change since the order was
   * read from the app touched [node], not even a text or state change, so the node needs no
   * second read. A graced change elsewhere in the window does not touch it: only a change whose
   * region overlaps the node does — the node's announcement is worked out from its own subtree
   * and its container, so a change inside it or around it both count — and a change whose region
   * is not known touches every node.
   */
  @JvmStatic
  fun holdsCurrent(node: AccessibilityNodeInfoCompat): Boolean =
    holds(node) && !gracedChangesTouch(node)

  /** Whether any graced change since the order was saved touched [node]. */
  private fun gracedChangesTouch(node: AccessibilityNodeInfoCompat): Boolean {
    if (firstIgnoredChangeTime == 0L) {
      return false
    }
    if (!unrelatedChangesSpareNodes || gracedChangeUnknown) {
      return true
    }
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return gracedChangeRegions.any { (windowId, region) ->
      windowId == node.windowId && Rect.intersects(region, bounds)
    }
  }

  /**
   * Remembers where a graced change happened, so [holdsCurrent] can tell the nodes it touched
   * from the ones it did not. A null [sourceBounds] means the region is not known, which touches
   * every node; more than [MAX_GRACED_CHANGE_REGIONS] regions are not remembered either, and
   * their reach is given up as unknown in the same way.
   */
  @VisibleForTesting
  internal fun noteGracedChange(windowId: Int, sourceBounds: Rect?) {
    if (firstIgnoredChangeTime == 0L) {
      firstIgnoredChangeTime = SystemClock.uptimeMillis()
    }
    if (sourceBounds == null || sourceBounds.isEmpty || gracedChangeUnknown) {
      gracedChangeUnknown = true
      return
    }
    if (gracedChangeRegions.size >= MAX_GRACED_CHANGE_REGIONS) {
      gracedChangeUnknown = true
      return
    }
    gracedChangeRegions += windowId to Rect(sourceBounds)
  }

  /** Forgets the graced changes remembered since the order was saved. */
  private fun resetGracedChanges() {
    gracedChangeRegions.clear()
    gracedChangeUnknown = false
  }

  /** The bounds in screen of [event]'s source, or null when its region is not known. */
  private fun sourceBounds(event: AccessibilityEvent): Rect? {
    val source = runCatching { event.source }.getOrNull() ?: return null
    try {
      val bounds = Rect()
      source.getBoundsInScreen(bounds)
      return bounds.takeIf { !it.isEmpty }
    } catch (e: Exception) {
      return null
    } finally {
      source.recycle()
    }
  }

  /**
   * The root of the window that [node] is in, if the saved order holds [node]: the root the order
   * was read from, as nothing that could change the window has happened since. Asking the window
   * for its root instead always waits for the app, because Android never answers that from its
   * node cache.
   */
  @JvmStatic
  fun rootFor(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? =
    synchronized(stateLock) { root }?.takeIf { it.windowId == node.windowId && holds(node) }

  /**
   * Remembers where a navigation started. When the focused node is later removed — an app
   * re-laying-out its content, such as a blocked ad slot collapsing, does this — navigation carries
   * on from the node nearest this place instead of falling back to the top of the window.
   */
  @JvmStatic
  fun rememberPivot(pivot: AccessibilityNodeInfoCompat) {
    val bounds = Rect()
    pivot.getBoundsInScreen(bounds)
    lastPivotTop = bounds.top
    lastPivotWindowId = pivot.windowId
  }

  /**
   * The node to search on from when the focused node has been removed: the last node above the
   * place the last navigation started, so a swipe forward lands on whatever now occupies that
   * place, rather than on the top of the page. Falls back to the first node when the removal was
   * above everything. Null when nothing was remembered, or the window has changed.
   *
   * The content change that removes a node usually throws the saved order away as well — an ad slot
   * collapsing sends a content change of the undefined type, which is structural here — so a fresh
   * order is built when there is none. That build is on the swiping thread, but it happens only on
   * the rare path where the focused node disappeared, and a swipe would have built an order anyway.
   */
  @JvmStatic
  fun pivotAfterRemoval(root: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
    if (lastPivotWindowId == NO_WINDOW_ID || root.windowId != lastPivotWindowId) {
      return null
    }
    val saved = synchronized(stateLock) { strategy?.takeIf { root == this.root } }
    val order =
      saved
        ?: runCatching {
              OrderedTraversalStrategy(root).also {
                Performance.getInstance().onOrderBuildOnSwipePath()
              }
            }
            .getOrNull()
        ?: return null
    val bounds = Rect()
    var above: AccessibilityNodeInfoCompat? = null
    for (node in order.dumpTree()) {
      if (node.windowId != lastPivotWindowId) {
        continue
      }
      node.getBoundsInScreen(bounds)
      if (bounds.top >= lastPivotTop) {
        return above ?: node
      }
      above = node
    }
    return above
  }

  /** Saves the order of [root], replacing any saved order. */
  @JvmStatic
  fun put(root: AccessibilityNodeInfoCompat, strategy: OrderedTraversalStrategy) {
    synchronized(stateLock) {
      generation++
      this.root = root
      this.strategy = strategy
      firstIgnoredChangeTime = 0L
      resetGracedChanges()
    }
  }

  /** Throws away the saved order. */
  @JvmStatic
  fun clear(reason: String) {
    synchronized(stateLock) {
      generation++
      root = null
      strategy = null
      firstIgnoredChangeTime = 0L
      resetGracedChanges()
      lastClearReason = reason
    }
  }

  /** The current order generation, captured by a background build when it is asked for. */
  internal fun currentGeneration(): Long = generation

  /**
   * Asks for the order of the window [windowId] to be built in the background, so that the next
   * swipe finds it saved here instead of building it on the swiping thread. The root is resolved
   * by [rootSupplier] on the builder's thread, never here. Below Android 13 the background read
   * of the nodes is all that is offered, exactly as before this mechanism existed.
   */
  @JvmStatic
  fun buildOrderInBackground(
    windowId: Int,
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      WindowNodePrefetcher.prefetchInBackground(rootSupplier, ownPackage)
      return
    }
    snapshotBuildWindowId = windowId
    TraversalOrderSnapshotBuilder.request(windowId, generation, rootSupplier, ownPackage)
  }

  /**
   * Saves an order built in the background, if the window has not changed since the build was
   * asked for: [generation] must still be current. A swipe verifies the order again when it
   * takes it (the root must be this window's and the order must hold the focused node), and
   * falls back to building on its own thread whenever this returns false or no snapshot has
   * landed yet. Returns whether the order was saved.
   */
  @JvmStatic
  fun publishSnapshot(
    root: AccessibilityNodeInfoCompat,
    strategy: OrderedTraversalStrategy,
    generation: Long,
  ): Boolean {
    synchronized(stateLock) {
      if (generation != this.generation) {
        return false
      }
      this.root = root
      this.strategy = strategy
      firstIgnoredChangeTime = 0L
      resetGracedChanges()
    }
    if (LogUtils.shouldLog(Log.DEBUG)) {
      Log.d(TAG, "Saved order built in the background")
    }
    return true
  }

  /**
   * Throws away the saved order before TalkBack performs [actionId] on a node, unless the action
   * only moves focus. A scroll or click changes the screen before the app's change event reaches
   * us, and TalkBack can navigate again in between, such as right after it scrolls a list.
   */
  @JvmStatic
  fun onNodeAction(actionId: Int) {
    if (changesNodes(actionId)) {
      clear(if (LogUtils.shouldLog(Log.DEBUG)) AccessibilityNodeInfoUtils.actionToString(actionId) else "")
    }
  }

  /** Whether [actionId] can change the nodes before the app's event about it arrives. */
  @JvmStatic
  fun changesNodes(actionId: Int): Boolean = actionId !in FOCUS_ACTIONS && actionId != SHOW_ON_SCREEN

  /** Throws away the saved order if [event] can change its window. Returns whether it did. */
  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
    val type = event.eventType
    if (type and IGNORED_EVENT_TYPES != 0) {
      return false
    }
    val savedRoot = root
    if (savedRoot == null) {
      invalidateSnapshotBuild(event)
      return false
    }
    val windowId = event.windowId
    if (
      type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        windowId == NO_WINDOW_ID ||
        windowId == savedRoot.windowId
    ) {
      val changes = event.contentChangeTypes
      if (
        type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
          changes != 0 &&
          changes and NON_STRUCTURAL_CHANGES.inv() == 0
      ) {
        noteGracedChange(windowId, sourceBounds(event))
        return false
      }
      clear(
        if (LogUtils.shouldLog(Log.DEBUG)) {
          AccessibilityEvent.eventTypeToString(type) + " 0x" + Integer.toHexString(changes)
        } else {
          ""
        }
      )
      return true
    }
    return false
  }

  /**
   * No order is saved, but a background build may be reading the tree for the window one was
   * asked for: an event that would have thrown that window's order away must also retire the
   * build, or it would publish an order read from a tree that has already changed. Changes of
   * the graced kind leave the order as it is, so they retire nothing.
   */
  private fun invalidateSnapshotBuild(event: AccessibilityEvent) {
    val type = event.eventType
    if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      val changes = event.contentChangeTypes
      if (changes != 0 && changes and NON_STRUCTURAL_CHANGES.inv() == 0) {
        return
      }
    }
    val windowId = event.windowId
    val buildWindowId = snapshotBuildWindowId
    if (
      type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        windowId == NO_WINDOW_ID ||
        (buildWindowId != NO_WINDOW_ID && windowId == buildWindowId)
    ) {
      synchronized(stateLock) { generation++ }
    }
  }
}
