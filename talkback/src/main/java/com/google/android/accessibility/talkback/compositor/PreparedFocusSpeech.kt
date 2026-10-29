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

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache
import com.google.android.accessibility.utils.LatencyExperiments

/**
 * The announcements of the nodes that a swipe from the focused node would most likely reach,
 * worked out while the user listens, so that a swipe can speak one without working it out then.
 *
 * They are thrown away when anything in the window changes, even text, and after [MAX_AGE_MS]. An
 * announcement is kept per node and per kind of swipe target it was worked out for, so the
 * announcement of a node as the next plain target does not stand in for it as the next heading, or
 * the other way round. Keeping other still-valid targets after one hit allows optional multi-item
 * lookahead to help a short burst of swipes.
 */
object PreparedFocusSpeech {
  /** How long a prepared announcement is kept, in case a change came without an event. */
  const val MAX_AGE_MS = 5_000L

  /**
   * How long before a swipe an announcement must have been prepared to be used. One prepared just
   * before may predate a change whose event has not arrived yet, so the swipe reads the node again.
   */
  const val MIN_AGE_MS = 100L

  private const val NO_WINDOW_ID = -1
  private const val MAX_PREPARED_ANNOUNCEMENTS = 8

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
  ) {
    internal val time = SystemClock.uptimeMillis()
    internal val version = contentVersion

    internal fun isFor(other: AccessibilityNodeInfoCompat): Boolean = node == other

    internal fun isFor(other: AccessibilityNodeInfoCompat, otherTargetType: Int): Boolean =
      node == other && targetType == otherTargetType
  }

  private var contentVersion = 0L
  private val prepared = ArrayList<Prepared>(MAX_PREPARED_ANNOUNCEMENTS)
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
  ) {
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
    if (prepared.size >= MAX_PREPARED_ANNOUNCEMENTS) {
      val oldest = prepared.minByOrNull { it.time }
      if (oldest != null) prepared.remove(oldest)
    }
    prepared += Prepared(node, feedback, containerTitle, targetType)
  }

  /** Returns whether an announcement of focusing [node] as a [targetType] target is kept. */
  @JvmStatic
  @JvmOverloads
  fun has(node: AccessibilityNodeInfoCompat, targetType: Int = NavigationTarget.TARGET_DEFAULT): Boolean =
    find(node, targetType) != null

  /**
   * Returns an old-enough announcement of focusing [node]. Other still-valid targets are retained
   * for subsequent swipes; content-changing events and age limits still invalidate the cache.
   */
  @JvmStatic
  fun peek(node: AccessibilityNodeInfoCompat, targetType: Int): Prepared? = find(node, targetType)

  @JvmStatic
  fun take(node: AccessibilityNodeInfoCompat): Prepared? = takeForType(node, null)

  @JvmStatic
  fun take(node: AccessibilityNodeInfoCompat, targetType: Int): Prepared? = takeForType(node, targetType)

  private fun takeForType(node: AccessibilityNodeInfoCompat, targetType: Int?): Prepared? {
    val found = find(node, targetType)
    if (found == null || (!LatencyExperiments.Feature.FRESH_PREPARED.enabled() &&
        !isOldEnough(found.time, SystemClock.uptimeMillis()))) {
      return null
    }
    prepared.remove(found)
    // A preparation was used: the window is earning its preparations again.
    wastedStreak = 0
    if (windowId == suppressedWindowId) {
      suppressedWindowId = NO_WINDOW_ID
    }
    return found
  }

  /** Whether an announcement prepared at [preparedTime] may be used at [now]; see [MIN_AGE_MS]. */
  @JvmStatic
  fun isOldEnough(preparedTime: Long, now: Long): Boolean = now - preparedTime >= MIN_AGE_MS

  /** Forgets all announcements. */
  @JvmStatic
  fun clear() {
    contentVersion++
    prepared.clear()
    windowId = NO_WINDOW_ID
  }

  /** Forgets all announcements if [event] can change what a node in their window says. */
  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (prepared.isNotEmpty() && canChange(event.eventType, event.windowId, windowId)) {
      noteWasted()
      clear()
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
   * After [MAX_WASTED_STREAK] wastes in a row, preparation pauses for that window until
   * [SUPPRESSION_MS] passes, after which one preparation is let through and a further waste
   * re-arms the pause at once. A window that never changes under the focus never wastes a
   * preparation, so a static list is prepared exactly as before.
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
      ((!LatencyExperiments.Feature.SCOPED_CACHE.enabled() &&
        (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
          type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) ||
        eventWindowId == NO_WINDOW_ID ||
        eventWindowId == preparedWindowId)

  /** Forgets all announcements before Backtalk performs [actionId], if it can change the nodes. */
  @JvmStatic
  fun onNodeAction(actionId: Int) {
    if (TraversalTreeCache.changesNodes(actionId)) clear()
  }

  private fun find(node: AccessibilityNodeInfoCompat, targetType: Int?): Prepared? {
    val now = SystemClock.uptimeMillis()
    prepared.removeAll { now - it.time > MAX_AGE_MS || it.version != contentVersion }
    return prepared.firstOrNull {
      if (targetType == null) it.isFor(node) else it.isFor(node, targetType)
    }
  }
}
