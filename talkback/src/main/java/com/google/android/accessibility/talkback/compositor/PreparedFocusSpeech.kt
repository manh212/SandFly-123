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

package com.google.android.accessibility.talkback.compositor

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache
import com.google.android.accessibility.utils.Performance

/**
 * The announcements of the nodes that a swipe from the focused node would most likely reach,
 * worked out while the user listens, so that a swipe can speak one without working it out then.
 *
 * An announcement is an announcement of its own node alone, worked out from that node read
 * again from the app, so it holds for that node however the focus reaches it. Announcements
 * are thrown away when anything in the window changes, even text, and after [MAX_AGE_MS];
 * taking one spends only that node's announcements, and the ones beside it — which are beside
 * the new focus too — are kept for its swipes, unless [keepUnusedOnTake] is off. An
 * announcement is kept per node and per kind of swipe target it was worked out for, so the
 * announcement of a node as the next plain target does not stand in for it as the next heading,
 * or the other way round.
 */
object PreparedFocusSpeech {
  /** What set a preparation off: the focus moved. */
  const val SOURCE_FOCUS = 0

  /** What set a preparation off: the window went quiet after changing. */
  const val SOURCE_SETTLE = 1

  /** What set a preparation off: the reading control changed under a steady focus. */
  const val SOURCE_GRANULARITY = 2
  /** How long a prepared announcement is kept, in case a change came without an event. */
  const val MAX_AGE_MS = 5_000L

  /**
   * How long before a swipe an announcement must have been prepared to be used. One prepared just
   * before may predate a change whose event has not arrived yet, so the swipe reads the node again.
   */
  const val MIN_AGE_MS = 100L

  private const val NO_WINDOW_ID = -1

  /**
   * Whether a content change that did not touch a prepared node leaves its announcement kept.
   * When false, any content change in the window throws every announcement away, as before.
   */
  @JvmStatic var keepAcrossUnrelatedChanges: Boolean = true

  /**
   * Whether taking an announcement leaves the others kept for the next swipe, instead of
   * forgetting them all as the focus moves. The announcements beside the taken one are beside
   * the newly focused node too: one of them is where a second swipe in the same direction
   * lands, another where a swipe back lands.
   */
  @JvmStatic var keepUnusedOnTake: Boolean = true

  /**
   * Told when an event may have changed the window announcements were last prepared for, so
   * preparation can be offered again once the window goes quiet: a busy window throws
   * preparations away faster than focus moves refill them, which is the usual reason a swipe
   * finds none.
   */
  @JvmStatic var settleListener: Runnable? = null

  /** The window announcements were last prepared for, kept after they are forgotten. */
  private var lastPreparedWindowId = NO_WINDOW_ID

  /** Wasted preparation rounds in a row after which a window's preparation pauses. */
  private const val MAX_WASTED_STREAK = 3

  /** How long preparation stays paused for a window that keeps wasting it. */
  private const val SUPPRESSION_MS = 30_000L

  /**
   * An announcement of focusing [node], which was read from the app to prepare it, and
   * [containerTitle], the title of the container that working it out left focus in, which giving it
   * must leave focus in too.
   */
  class Prepared(
    val node: AccessibilityNodeInfoCompat,
    val feedback: EventFeedback,
    val containerTitle: CharSequence,
    val targetType: Int,
    /** What set the preparation off; one of the SOURCE constants. */
    val source: Int = SOURCE_FOCUS,
    /** Whether [node] was predicted from the previous reading order, under its checks. */
    val fromStaleOrder: Boolean = false,
    /** How many swipes away [node] was predicted: 1, or 2 when prepared further ahead. */
    val depth: Int = 1,
  ) {
    internal val time = SystemClock.uptimeMillis()

    /** Where [node] was when this announcement was prepared, so a change can be told apart. */
    internal val bounds: Rect = Rect().also { node.getBoundsInScreen(it) }

    internal fun isFor(other: AccessibilityNodeInfoCompat): Boolean = node == other

    internal fun isFor(other: AccessibilityNodeInfoCompat, otherTargetType: Int): Boolean =
      node == other && targetType == otherTargetType
  }

  private val prepared = ArrayList<Prepared>(4)
  private var windowId = NO_WINDOW_ID

  /** Preparations thrown away unused in a row, and the window they were made for. */
  private var wastedStreak = 0
  private var wastedWindowId = NO_WINDOW_ID

  /** The window preparation is paused for, and when the pause ends. */
  private var suppressedWindowId = NO_WINDOW_ID
  private var suppressedUntilMs = 0L

  /** Keeps [feedback] as the announcement of focusing [node]; see [Prepared]. */
  @JvmStatic
  @JvmOverloads
  fun put(
    node: AccessibilityNodeInfoCompat,
    feedback: EventFeedback,
    containerTitle: CharSequence,
    targetType: Int = NavigationTarget.TARGET_DEFAULT,
    source: Int = SOURCE_FOCUS,
    fromStaleOrder: Boolean = false,
    depth: Int = 1,
  ) {
    lastPreparedWindowId = node.windowId
    if (node.windowId != windowId) {
      clear()
      windowId = node.windowId
      if (node.windowId != wastedWindowId) {
        // A different window took over: waste in the old one says nothing about this one.
        wastedWindowId = node.windowId
        wastedStreak = 0
      }
    }
    prepared.removeAll { it.isFor(node, targetType) }
    prepared += Prepared(node, feedback, containerTitle, targetType, source, fromStaleOrder, depth)
  }

  /** Returns whether an announcement of focusing [node] as a [targetType] target is kept. */
  @JvmStatic
  @JvmOverloads
  fun has(node: AccessibilityNodeInfoCompat, targetType: Int = NavigationTarget.TARGET_DEFAULT): Boolean =
    find(node, targetType) != null

  /**
   * Returns the announcement of focusing [node], or null if there is none or it was prepared less
   * than [MIN_AGE_MS] ago. The focus is moving to [node]: its own announcements are spent either
   * way, and the ones of the nodes beside it are kept for its swipes when [keepUnusedOnTake],
   * or all are forgotten as before when it is off.
   */
  @JvmStatic
  fun take(node: AccessibilityNodeInfoCompat): Prepared? {
    val found = find(node, null)
    if (found != null) {
      // A preparation was used: the window is earning its preparations again.
      wastedStreak = 0
      if (windowId == suppressedWindowId) {
        suppressedWindowId = NO_WINDOW_ID
      }
    }
    if (keepUnusedOnTake) {
      prepared.removeAll { it.isFor(node) }
      if (prepared.isEmpty()) {
        windowId = NO_WINDOW_ID
      }
    } else {
      clear()
    }
    return found?.takeIf { isOldEnough(it.time, SystemClock.uptimeMillis()) }
  }

  /** Whether an announcement prepared at [preparedTime] may be used at [now]; see [MIN_AGE_MS]. */
  @JvmStatic
  fun isOldEnough(preparedTime: Long, now: Long): Boolean = now - preparedTime >= MIN_AGE_MS

  /** Forgets all announcements. */
  @JvmStatic
  fun clear() {
    prepared.clear()
    windowId = NO_WINDOW_ID
  }

  /** Forgets all announcements if [event] can change what a node in their window says. */
  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent) {
    // Whether or not this event leaves any announcement standing, it may have changed the
    // window they were prepared for: the listener is told, so preparation can be offered
    // again once the window goes quiet.
    val listener = settleListener
    if (
      listener != null &&
        lastPreparedWindowId != NO_WINDOW_ID &&
        canChange(event.eventType, event.windowId, lastPreparedWindowId)
    ) {
      listener.run()
    }
    if (prepared.isEmpty() || !canChange(event.eventType, event.windowId, windowId)) {
      return
    }
    if (
      keepAcrossUnrelatedChanges &&
        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    ) {
      // A change, even a structural one, only reaches the announcements of the nodes in its
      // region: a reaction added to one message says nothing about the message above it. A
      // change whose region is not known reaches everything, as before.
      val region = sourceBounds(event)
      if (region != null) {
        val sizeBefore = prepared.size
        prepared.removeAll { Rect.intersects(region, it.bounds) }
        val evicted = sizeBefore - prepared.size
        if (evicted == 0) {
          // The change touched no prepared node: nothing was wasted.
          Performance.getInstance().onPreparedEntriesKeptByRegion(sizeBefore)
          return
        }
        if (prepared.isNotEmpty()) {
          // Only the touched announcements died; the rest were not wasted either.
          Performance.getInstance().onPreparedEntriesEvictedByRegion(evicted)
          Performance.getInstance().onPreparedEntriesKeptByRegion(prepared.size)
          return
        }
        Performance.getInstance().onPreparedEntriesEvictedByRegion(evicted)
      }
      // The region is not known, or it touched every announcement: as before.
    }
    noteWasted()
    clear()
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

  /** A set of preparations died unused: count it, and pause a window that keeps doing it. */
  private fun noteWasted() {
    if (windowId != wastedWindowId) {
      wastedWindowId = windowId
      wastedStreak = 0
    }
    wastedStreak++
    if (wastedStreak >= MAX_WASTED_STREAK) {
      suppressedWindowId = windowId
      suppressedUntilMs = SystemClock.uptimeMillis() + SUPPRESSION_MS
    }
  }

  /**
   * Whether preparing announcements around [node] is worth its main-thread cost. It is not, for
   * a while, in a window whose preparations kept dying unused — a churning chat window, say,
   * where a swipe then falls back to composing on the spot, exactly as without preparation.
   * After [MAX_WASTED_STREAK] wastes in a row, preparation pauses for that window until a
   * preparation is used there again, or [SUPPRESSION_MS] passes, after which one preparation is
   * let through and a further waste re-arms the pause at once. A window that never changes
   * under the focus never wastes a preparation, so a static list is prepared exactly as before.
   */
  @JvmStatic
  fun shouldPrepare(node: AccessibilityNodeInfoCompat): Boolean {
    if (node.windowId != suppressedWindowId) {
      return true
    }
    if (SystemClock.uptimeMillis() >= suppressedUntilMs) {
      suppressedWindowId = NO_WINDOW_ID
      wastedStreak = MAX_WASTED_STREAK - 1
      return true
    }
    return false
  }

  /**
   * Whether an event of [type] from the window [eventWindowId] can change what a node in the window
   * [preparedWindowId] says. Unlike the saved reading order, any content change counts, even of
   * text or state alone.
   */
  @JvmStatic
  fun canChange(type: Int, eventWindowId: Int, preparedWindowId: Int): Boolean =
    type and TraversalTreeCache.IGNORED_EVENT_TYPES == 0 &&
      (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventWindowId == NO_WINDOW_ID ||
        eventWindowId == preparedWindowId)

  /** Forgets all announcements before Backtalk performs [actionId], if it can change the nodes. */
  @JvmStatic
  fun onNodeAction(actionId: Int) {
    if (TraversalTreeCache.changesNodes(actionId)) clear()
  }

  private fun find(node: AccessibilityNodeInfoCompat, targetType: Int?): Prepared? {
    val now = SystemClock.uptimeMillis()
    prepared.removeAll { now - it.time > MAX_AGE_MS }
    return prepared.firstOrNull {
      if (targetType == null) it.isFor(node) else it.isFor(node, targetType)
    }
  }
}
