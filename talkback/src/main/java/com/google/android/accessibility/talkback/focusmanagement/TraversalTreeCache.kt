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
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.function.IntConsumer
import java.util.concurrent.atomic.AtomicLong
import com.google.android.accessibility.utils.Statistics
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.WindowCachePolicy

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
   * throw the order away before every swipe.
   */
  private const val NON_STRUCTURAL_CHANGES =
    AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
      AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION

  private const val NON_STRUCTURAL_CHANGES_WITH_DESCRIPTION =
    NON_STRUCTURAL_CHANGES or AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION

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

  private val revisionCounter = AtomicLong()
  @Volatile private var buildingWindowId = NO_WINDOW_ID

  @JvmStatic fun revision(): Long = revisionCounter.get()
  @JvmStatic fun setBuildingWindow(windowId: Int) { buildingWindowId = windowId }

  private var root: AccessibilityNodeInfoCompat? = null
  private var strategy: OrderedTraversalStrategy? = null

  /**
   * Who is told the window id when an operation throws the saved order away, so that window's
   * nodes can be read in the background before the next swipe, as they are when an event throws
   * the order away. An event's own clear is reported by [onAccessibilityEvent] instead, and the
   * order expiring inside [get] is not reported either: an order is built at once there anyway.
   */
  @Volatile private var orderClearedListener: IntConsumer? = null

  private var lastClearReason = "start"
  private var clearedWindowId = NO_WINDOW_ID
  @JvmStatic fun lastClearedWindowId(): Int = clearedWindowId
  private var firstIgnoredChangeTime = 0L
  private var hits = 0
  private var misses = 0
  private var liveAnswers = 0
  private val buildTimes = Statistics()
  // Keep churn detection independent of the user-selected order cache.
  private val structuralChurn = StructuralChurnTracker()

  @JvmStatic
  var enabled: Boolean = true
    set(value) {
      if (field == value) return
      field = value
      clear("setting changed", notifyListener = false)
    }

  @JvmStatic
  fun noteBuild(durationMs: Long) {
    if (LogUtils.shouldLog(Log.DEBUG)) buildTimes.increment(durationMs)
  }

  @JvmStatic
  fun buildTimingSummary(): String =
    synchronized(buildTimes) {
      "Order build time: count=${buildTimes.count}, mean=${buildTimes.mean} ms, " +
        "p90=${buildTimes.getPercentile(90)} ms, p99=${buildTimes.getPercentile(99)} ms"
    }

  @JvmStatic
  fun noteLiveAnswer() {
    if (LogUtils.shouldLog(Log.DEBUG)) {
      liveAnswers++
    }
  }

  @JvmStatic
  fun shouldUseLiveTraversal(windowId: Int): Boolean =
    structuralChurn.isChurning(windowId, SystemClock.uptimeMillis())

  @JvmStatic
  fun noteSlowBuild(windowId: Int, durationMs: Long) {
    structuralChurn.noteSlowBuild(windowId, SystemClock.uptimeMillis(), durationMs)
  }

  @JvmStatic
  fun notePassiveScroll(windowId: Int) {
    structuralChurn.record(windowId, SystemClock.uptimeMillis())
  }

  @JvmStatic
  fun orderCacheReused(): Int = hits

  @JvmStatic
  fun orderCacheMissed(): Int = misses

  @JvmStatic
  fun orderCacheBuilt(): Long = synchronized(buildTimes) { buildTimes.count }

  @JvmStatic
  fun orderCacheLiveAnswers(): Int = liveAnswers

  @JvmStatic
  fun resetOrderCacheStats() {
    hits = 0
    misses = 0
    liveAnswers = 0
    buildTimes.clear()
  }

  @JvmStatic
  var regionGraceEnabled: Boolean = true
    set(value) {
      if (field == value) {
        return
      }
      field = value
      resetGracedChanges()
      firstIgnoredChangeTime = 0L
    }

  @JvmStatic
  var burstLiveAnswerEnabled: Boolean = true
    get() = field && enabled

  @Volatile private var lastNavigationMs: Long = 0L
  @Volatile private var previousNavigationMs: Long = 0L

  private const val NAVIGATION_BURST_WINDOW_MS = 300L

  private val gracedChangeRegions = ArrayList<Pair<Int, Rect>>()
  private var gracedChangeUnknown = false

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
    if (!enabled) {
      if (LogUtils.shouldLog(Log.DEBUG)) misses++
      return null
    }
    if (
      firstIgnoredChangeTime != 0L &&
        SystemClock.uptimeMillis() - firstIgnoredChangeTime > MAX_AGE_AFTER_TEXT_CHANGE_MS
    ) {
      clear("text changes", notifyListener = false)
    }
    val saved = strategy
    val result = saved?.takeIf { root == this.root && it.containsNode(pivot) }
    if (LogUtils.shouldLog(Log.DEBUG)) {
      if (result != null) {
        hits++
      } else {
        misses++
      }
    }
    return result
  }

  /**
   * Whether the saved order holds [node]. Nothing that could remove a node from the window has
   * happened since the order was saved, so the node is still there.
   */
  @JvmStatic
  fun holds(node: AccessibilityNodeInfoCompat): Boolean =
    enabled && strategy?.containsNode(node) == true

  /**
   * Whether the saved order holds [node] as the app has it now: nothing in its window has changed
   * since the order was read from the app, not even a text or state, so the node needs no second
   * read.
   */
  @JvmStatic
  fun holdsCurrent(node: AccessibilityNodeInfoCompat): Boolean =
    if (regionGraceEnabled) {
      holds(node) && !gracedChangesTouch(node)
    } else {
      firstIgnoredChangeTime == 0L && holds(node)
    }

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

  private fun resetGracedChanges() {
    gracedChangeRegions.clear()
    gracedChangeUnknown = false
  }

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

  private fun gracedChangesTouch(node: AccessibilityNodeInfoCompat): Boolean {
    if (firstIgnoredChangeTime == 0L) {
      return false
    }
    if (gracedChangeUnknown) {
      return true
    }
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return gracedChangeRegions.any { (windowId, region) ->
      windowId == node.windowId && Rect.intersects(region, bounds)
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
    root?.takeIf { it.windowId == node.windowId && holds(node) }

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

  @JvmStatic
  @JvmOverloads
  fun noteNavigationActivity(nowMs: Long = SystemClock.uptimeMillis()) {
    previousNavigationMs = lastNavigationMs
    lastNavigationMs = nowMs
  }

  @JvmStatic
  fun lastNavigationTimeMs(): Long = lastNavigationMs

  @JvmStatic
  fun previousNavigationTimeMs(): Long = previousNavigationMs

  @JvmStatic
  fun isNavigationBurst(previousMs: Long, lastMs: Long, nowMs: Long): Boolean {
    if (previousMs <= 0L || lastMs <= previousMs) {
      return false
    }
    return lastMs - previousMs < NAVIGATION_BURST_WINDOW_MS &&
      nowMs - lastMs < NAVIGATION_BURST_WINDOW_MS
  }

  @JvmStatic
  @JvmOverloads
  fun inNavigationBurst(nowMs: Long = SystemClock.uptimeMillis()): Boolean =
    isNavigationBurst(previousNavigationMs, lastNavigationMs, nowMs)

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
    val order =
      strategy?.takeIf { root == this.root }
        ?: runCatching { OrderedTraversalStrategy(root) }.getOrNull()
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
    if (!enabled) return
    this.root = root
    this.strategy = strategy
    firstIgnoredChangeTime = 0L
    resetGracedChanges()
  }

  /**
   * Sets who is told when an operation throws the saved order away — a node action performed by
   * TalkBack, a resume, a focus retry — with the id of the window the order belonged to. Pass
   * null to stop being told.
   */
  @JvmStatic
  fun setOrderClearedListener(listener: IntConsumer?) {
    orderClearedListener = listener
  }

  /** Throws away the saved order. */
  @JvmStatic
  fun clear(reason: String) {
    clear(reason, notifyListener = true)
  }

  private fun clear(reason: String, notifyListener: Boolean) {
    revisionCounter.incrementAndGet()
    if (strategy == null) {
      return
    }
    val windowId = root?.windowId ?: NO_WINDOW_ID
    clearedWindowId = windowId
    root = null
    strategy = null
    firstIgnoredChangeTime = 0L
    resetGracedChanges()
    lastClearReason = reason
    if (notifyListener) {
      orderClearedListener?.accept(windowId)
    }
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

  private fun advanceRevision(event: AccessibilityEvent) {
    if (event.eventType and IGNORED_EVENT_TYPES != 0) return
    val trackedWindow = if (buildingWindowId != NO_WINDOW_ID) buildingWindowId
      else root?.windowId ?: NO_WINDOW_ID
    if (!LatencyExperiments.Feature.SCOPED_CACHE.enabled() ||
      WindowCachePolicy.affectsOrder(event, trackedWindow)) revisionCounter.incrementAndGet()
  }

  @JvmStatic
  fun noteDeferredEvent(event: AccessibilityEvent) {
    advanceRevision(event)
    val saved = root ?: return
    if (event.windowId >= 0 && event.windowId != saved.windowId) return
    if (WindowCachePolicy.isNonStructuralContent(event)) {
      noteGracedChange(event.windowId, null)
    }
  }

  /** Throws away the saved order if [event] can change its window. Returns whether it did. */
  /** Forget rate observations when the early event gate is switched off. */
  @JvmStatic
  fun resetChurnTracking() = structuralChurn.clear()

  /** Runs at service entry, including for updates later coalesced by the active-event gate. */
  @JvmStatic
  fun observeIncomingEvent(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
    val changes = event.contentChangeTypes
    if (changes == 0 || changes and NON_STRUCTURAL_CHANGES_WITH_DESCRIPTION.inv() != 0) {
      structuralChurn.record(event.windowId, SystemClock.uptimeMillis())
    }
  }

  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
    val type = event.eventType
    if (type and IGNORED_EVENT_TYPES != 0) return false
    advanceRevision(event)
    val savedRoot = root ?: return false
    val windowId = event.windowId
    if (LatencyExperiments.Feature.SCOPED_CACHE.enabled() &&
      !WindowCachePolicy.affectsOrder(event, savedRoot.windowId)) return false
    if (
      type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        windowId == NO_WINDOW_ID ||
        windowId == savedRoot.windowId
    ) {
      val changes = event.contentChangeTypes
      val nonStructuralChanges =
        if (regionGraceEnabled) NON_STRUCTURAL_CHANGES_WITH_DESCRIPTION
        else NON_STRUCTURAL_CHANGES
      if (
        type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
          changes != 0 &&
          changes and nonStructuralChanges.inv() == 0
      ) {
        if (regionGraceEnabled) {
          noteGracedChange(windowId, sourceBounds(event))
        } else if (firstIgnoredChangeTime == 0L) {
          firstIgnoredChangeTime = SystemClock.uptimeMillis()
        }
        return false
      }
      clear(
        if (LogUtils.shouldLog(Log.DEBUG)) {
          AccessibilityEvent.eventTypeToString(type) + " 0x" + Integer.toHexString(changes)
        } else {
          ""
        },
        notifyListener = false,
      )
      return true
    }
    return false
  }
}
