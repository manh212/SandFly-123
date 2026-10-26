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
import android.os.Handler
import android.os.Looper
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
   * Whether a swipe may be served by the previous order, under the checks in [serveStaleOrder],
   * while a fresh one is being built. When false, a throw-away costs the next swipe a build on
   * its own thread, as before.
   */
  @JvmStatic var serveStaleOrdersEnabled: Boolean = true

  /**
   * Whether a missing order is asked for again once the screen has been quiet for a while (see
   * [armSettleBuild]). When false, only the triggers that already existed ask for a build.
   */
  @JvmStatic var settleRetryEnabled: Boolean = true

  /**
   * Whether background builds use the lean order builder, which walks the window once, instead
   * of the standard one. When false, every background build is the standard build.
   */
  @JvmStatic var leanOrderBuilderEnabled: Boolean = true

  /**
   * Whether directional navigation is answered by the fast navigator, working from this cache's
   * order, instead of by the usual navigation machinery. When false, the fast navigator is never
   * asked, and navigation is exactly as it was before it existed.
   */
  @JvmStatic var fastNavigatorEnabled: Boolean = true

  /**
   * Whether a listener's ask for a background build, arriving while a build for the same window
   * is already on its way, is folded into that build — remembered as a need to build once more
   * from the newest tree when the current build lands — instead of replacing the waiting build.
   * When false, every ask is posted as it was before.
   */
  @JvmStatic var listenerBuildCoalescingEnabled: Boolean = true

  /**
   * Whether the fast navigator fires the focus action at a target without refreshing the
   * target first. A target that turns out dead refuses the action, and the navigator walks on
   * to the next living one, exactly as a failed refresh used to make it do — one binder round
   * trip fewer on every swipe. When false, targets are refreshed before firing, as before.
   */
  @JvmStatic var skipTargetRefreshEnabled: Boolean = true

  /**
   * Whether the remembered focus may serve as a navigation's starting point on cheap local
   * evidence — the saved order has not been thrown away since the focus was remembered, the
   * saved order still holds the node, and the node still carries its focused flag — instead
   * of a refresh round trip to the app. When false, the remembered focus is always confirmed
   * by refreshing it, as before.
   */
  @JvmStatic var cheapPivotCheckEnabled: Boolean = true

  /**
   * Whether the fast navigator fires the focus action before it starts the target's speech, so
   * the action never waits behind composing and submitting the announcement. Both calls run on
   * the same thread one after the other, so no focus event can arrive between them. When
   * false, the speech is submitted first, as before.
   */
  @JvmStatic var fireFocusBeforeSpeechEnabled: Boolean = true

  /**
   * Whether a scroll the fast navigator performs is landed by the first content change the app
   * reports for the window, with the timed tries kept only as a shorter backstop, instead of
   * by timed tries alone. When false, the landing is polled as before.
   */
  @JvmStatic var eventDrivenScrollLandingEnabled: Boolean = true

  /**
   * Whether a background build asked for while swipes are landing in quick succession is held
   * until the burst passes, instead of starting at once and competing for the main thread.
   * The ask is kept, newest one winning, and the build still runs. When false, builds start
   * the moment they are asked for, as before.
   */
  @JvmStatic var burstBuildDeferralEnabled: Boolean = true

  /** The node accessibility focus was last known to land on, as far as this cache is told. */
  @Volatile private var lastFocusNode: AccessibilityNodeInfoCompat? = null

  /**
   * How many times the saved order had been thrown away when [lastFocusNode] was remembered.
   * A fresh order landing does not count: it replaces the snapshot without the screen having
   * moved past the remembered focus, and the remembered node is re-located in the new order
   * before anything is aimed from it.
   */
  private var lastFocusInvalidations: Long = -1L

  /** How many times the saved order has been thrown away; see [lastFocusInvalidations]. */
  @Volatile private var invalidations = 0L

  /** When directional navigation last ran, by the uptime clock; zero when none has yet. */
  @Volatile private var lastNavigationMs: Long = 0L

  /**
   * Remembers the node accessibility focus last landed on. A navigation that starts from it
   * need not ask the app where the focus is, which is a round trip on the swipe's way.
   */
  @JvmStatic
  fun rememberFocus(node: AccessibilityNodeInfoCompat) {
    lastFocusNode = node
    lastFocusInvalidations = invalidations
  }

  /** Notes that a directional navigation just ran, for the burst checks that read it. */
  @JvmStatic
  fun noteNavigationActivity(nowMs: Long = SystemClock.uptimeMillis()) {
    lastNavigationMs = nowMs
  }

  /** When directional navigation last ran, by the uptime clock; zero when none has yet. */
  @JvmStatic fun lastNavigationTimeMs(): Long = lastNavigationMs

  /**
   * The focus node a navigation may start from without asking the app, or null when it must ask
   * as usual: the remembered node serves only while the saved order still holds it, it is still
   * there when asked again, and it still reports itself as the accessibility focus. Any of
   * those failing, the app is the only authority, exactly as before.
   */
  @JvmStatic
  fun focusNodeForNavigation(): AccessibilityNodeInfoCompat? {
    val node = lastFocusNode ?: return null
    // Cheap evidence first, when its switch allows: the saved order has not been thrown away
    // since this focus was remembered (a focus move onto a node is itself remembered the
    // moment its event arrives, and a focus cleared without a landing is forgotten, so a
    // remembered focus the screen has moved past fails one of these), the saved order still
    // holds the node, and the node still carries its focused flag. Any doubt falls through
    // to the refresh below, which stays the authority.
    if (
      cheapPivotCheckEnabled &&
        runCatching {
              lastFocusInvalidations == invalidations &&
                holds(node) &&
                node.isAccessibilityFocused
            }
            .getOrDefault(false)
    ) {
      Performance.getInstance().onCachedFocusShortcutUsed()
      return node
    }
    val usable =
      runCatching {
            holds(node) && refreshForNavigation(node) && node.isAccessibilityFocused
          }
          .getOrDefault(false)
    if (usable) {
      Performance.getInstance().onCachedFocusShortcutUsed()
      return node
    }
    Performance.getInstance().onCachedFocusShortcutRejected()
    return null
  }

  /** Refreshes [node] for a navigation, timing the round trip as its own swipe segment. */
  private fun refreshForNavigation(node: AccessibilityNodeInfoCompat): Boolean {
    val started = SystemClock.uptimeMillis()
    val refreshed = node.refresh()
    Performance.getInstance()
      .recordSwipeSegment("fast nav pivot refresh", SystemClock.uptimeMillis() - started)
    return refreshed
  }

  /**
   * Forgets the remembered focus when the app reports it cleared: a node whose focus was
   * cleared without a landing elsewhere must not serve as a starting point on the strength of
   * its stale focused flag.
   */
  private fun forgetFocusIfCleared(event: AccessibilityEvent) {
    val remembered = lastFocusNode ?: return
    val source =
      runCatching { event.source?.let { AccessibilityNodeInfoCompat.wrap(it) } }
        .getOrNull()
        ?: return
    try {
      if (source == remembered) {
        lastFocusNode = null
        lastFocusInvalidations = -1L
      }
    } finally {
      source.recycle()
    }
  }

  /**
   * The last order thrown away, kept as a stale copy a swipe may be served by under checks (see
   * [serveStaleOrder]) until a fresh order lands. A throw-away demotes the saved order to here
   * instead of dropping it; only a throw-away that proves an order wrong (see [clearCompletely])
   * or a fresh order landing drops it.
   */
  @Volatile private var staleRoot: AccessibilityNodeInfoCompat? = null

  @Volatile private var staleStrategy: OrderedTraversalStrategy? = null

  /** Regions of the structural changes since the demotion, as window id to bounds in screen. */
  private val structuralChangeRegions = ArrayList<Pair<Int, Rect>>()

  /** Whether a structural change since the demotion has a region that is not known. */
  private var structuralChangeUnknown = false

  /** Forgets the structural changes remembered since the demotion. */
  private fun resetStructuralChanges() {
    structuralChangeRegions.clear()
    structuralChangeUnknown = false
  }

  /**
   * Remembers where a structural change happened, so a stale order can tell the nodes it reached
   * from the ones it did not. A null [sourceBounds] means the region is not known, which reaches
   * every node; more than [MAX_STRUCTURAL_CHANGE_REGIONS] regions are not remembered either.
   */
  private fun noteStructuralChange(windowId: Int, sourceBounds: Rect?) {
    synchronized(stateLock) {
      if (staleStrategy == null) {
        return
      }
      if (sourceBounds == null || sourceBounds.isEmpty || structuralChangeUnknown) {
        structuralChangeUnknown = true
        return
      }
      if (structuralChangeRegions.size >= MAX_STRUCTURAL_CHANGE_REGIONS) {
        structuralChangeUnknown = true
        return
      }
      structuralChangeRegions += windowId to Rect(sourceBounds)
    }
  }

  /** Whether any structural change since the demotion touched [node]. */
  private fun structuralChangesTouch(node: AccessibilityNodeInfoCompat): Boolean {
    if (structuralChangeUnknown) {
      return true
    }
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return structuralChangeRegions.any { (windowId, region) ->
      windowId == node.windowId && Rect.intersects(region, bounds)
    }
  }

  /**
   * The previous order of [root], for a swipe, if it may still be trusted for [pivot]: the order
   * has been thrown away, but nothing that happened since reached the pivot, and the pivot is
   * still there when asked again. The caller checks the target it picks from the order in the
   * same way (see [validateStaleTarget]) before focusing it, and the background build a
   * throw-away starts replaces this order with a fresh one for the swipes after. Null when the
   * previous order may not be trusted, and the swipe then builds its own, as before.
   */
  @JvmStatic
  fun serveStaleOrder(
    root: AccessibilityNodeInfoCompat,
    pivot: AccessibilityNodeInfoCompat,
  ): OrderedTraversalStrategy? {
    if (!serveStaleOrdersEnabled) {
      return null
    }
    val (savedStaleRoot, savedStale) = synchronized(stateLock) { staleRoot to staleStrategy }
    if (savedStale == null || root != savedStaleRoot || !savedStale.containsNode(pivot)) {
      return null
    }
    if (structuralChangesTouch(pivot)) {
      return null
    }
    if (!pivot.refresh()) {
      return null
    }
    Performance.getInstance().onStaleOrderServed()
    return savedStale
  }

  /**
   * The previous order holding [pivot], for working out where a swipe from it would land
   * before the swipe happens, under the same checks as [serveStaleOrder] but without counting
   * a served swipe: no swipe is being answered yet. The caller checks the target it picks in
   * the same way (see [validateStaleTarget]) before working out its announcement, and the
   * announcement is worked out from the node read again from the app, so a target let through
   * can at worst not be the node the swipe reaches, never a wrong announcement.
   */
  @JvmStatic
  fun staleOrderForPrediction(
    pivot: AccessibilityNodeInfoCompat
  ): OrderedTraversalStrategy? {
    if (!serveStaleOrdersEnabled) {
      return null
    }
    val savedStale = synchronized(stateLock) { staleStrategy }
    if (savedStale == null || !savedStale.containsNode(pivot)) {
      return null
    }
    if (structuralChangesTouch(pivot)) {
      return null
    }
    if (!pivot.refresh()) {
      return null
    }
    return savedStale
  }

  /**
   * Whether [target], picked from the previous order, may be focused: no structural change
   * touched it, it is still there when asked again, it is exactly where the order left it, and
   * it is still visible. A target the order does not hold did not come from it and needs no
   * check. When this returns false the caller throws the order away for good and searches again
   * in a fresh one, so a wrong answer costs one build, never a wrong landing.
   */
  @JvmStatic
  fun validateStaleTarget(target: AccessibilityNodeInfoCompat): Boolean {
    val savedStale = staleStrategy ?: return true
    if (!savedStale.containsNode(target)) {
      return true
    }
    if (structuralChangesTouch(target)) {
      Performance.getInstance().onStaleTargetRejected()
      return false
    }
    val before = Rect()
    target.getBoundsInScreen(before)
    if (!target.refresh()) {
      Performance.getInstance().onStaleTargetRejected()
      return false
    }
    val after = Rect()
    target.getBoundsInScreen(after)
    val valid = before == after && target.isVisibleToUser
    if (!valid) {
      Performance.getInstance().onStaleTargetRejected()
    }
    return valid
  }

  /** How many structural change regions are remembered before their reach is given up as unknown. */
  private const val MAX_STRUCTURAL_CHANGE_REGIONS = 8

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
   * Whether any order is saved at all. A touch that begins while none is saved is the first
   * warning that a swipe is coming for a window whose order has still to be built.
   */
  @JvmStatic
  fun hasSavedOrder(): Boolean = synchronized(stateLock) { strategy != null }

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
    // The stale copy answers the positional question just as well — the caller checks the node
    // it picks with the app before using it — and asking it costs no build at all.
    val order =
      saved
        ?: staleStrategy?.takeIf { root == staleRoot }
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

  /**
   * Told the window of a throw-away, or [NO_WINDOW_ID] when the window is not known. Registered
   * by TalkBackService, which starts a background build for the window: a throw-away caused by
   * an action TalkBack itself performed — a click, a selection — used to start nothing, so the
   * next swipe paid for the whole build itself, which is where the slowest swipes came from.
   */
  fun interface OrderClearedListener {
    fun onOrderCleared(windowId: Int)
  }

  @JvmStatic var orderClearedListener: OrderClearedListener? = null

  /**
   * Told that the screen has been quiet for a while and still no order is saved, so one should
   * be built now. Registered by TalkBackService, like [orderClearedListener]: the cache knows
   * when the order is missing, but only the service can resolve a window's root.
   */
  fun interface SettleBuildRequester {
    fun onSettleBuildNeeded(windowId: Int)
  }

  @JvmStatic var settleBuildRequester: SettleBuildRequester? = null

  /** How long the screen must stay quiet after a change before a missing order is retried. */
  private const val SETTLE_BUILD_DELAY_MS = 300L

  private val settleHandler = Handler(Looper.getMainLooper())

  /** The window the pending settle retry would build, from the change that armed it. */
  private var settleBuildWindowId = NO_WINDOW_ID

  private val settleBuildRunnable = Runnable { fireSettleBuild() }

  /**
   * Arms the settle retry for [windowId]: if the screen stays quiet for
   * [SETTLE_BUILD_DELAY_MS] and no order has been saved by then, a build is asked for. Every
   * further change arms it again, so the delay runs from the last change, not the first.
   *
   * A build asked for the moment a window changes has to survive the whole burst of changes
   * that follows — on the owner's phone, roughly two in five did not — so the retry is what
   * turns those dead builds into a second chance once the window has settled, instead of
   * leaving the first swipe on the settled window to build the order itself.
   */
  private fun armSettleBuild(windowId: Int) {
    if (!settleRetryEnabled) {
      return
    }
    settleBuildWindowId = windowId
    settleHandler.removeCallbacks(settleBuildRunnable)
    settleHandler.postDelayed(settleBuildRunnable, SETTLE_BUILD_DELAY_MS)
  }

  /** The settle retry falling due: asks for a build if the order is still missing. */
  private fun fireSettleBuild() {
    if (!settleRetryEnabled || hasSavedOrder()) {
      return
    }
    settleBuildRequester?.onSettleBuildNeeded(settleBuildWindowId)
  }

  /** Saves the order of [root], replacing any saved order. */
  @JvmStatic
  fun put(root: AccessibilityNodeInfoCompat, strategy: OrderedTraversalStrategy) {
    synchronized(stateLock) {
      generation++
      this.root = root
      this.strategy = strategy
      staleRoot = null
      staleStrategy = null
      resetStructuralChanges()
      firstIgnoredChangeTime = 0L
      resetGracedChanges()
    }
  }

  /** Throws away the saved order. */
  @JvmStatic
  fun clear(reason: String) {
    clear(reason, notifyListener = true)
  }

  /**
   * Throws away the saved order for good, the previous one with it: the order has been proved
   * wrong — a target it offered could not be focused, or failed its checks — so nothing may be
   * served by it again.
   */
  @JvmStatic
  fun clearCompletely(reason: String) {
    val clearedWindowId =
      synchronized(stateLock) {
        generation++
        invalidations++
        val windowId = root?.windowId ?: staleRoot?.windowId ?: NO_WINDOW_ID
        root = null
        strategy = null
        staleRoot = null
        staleStrategy = null
        resetStructuralChanges()
        firstIgnoredChangeTime = 0L
        resetGracedChanges()
        lastClearReason = reason
        windowId
      }
    armSettleBuild(clearedWindowId)
    orderClearedListener?.onOrderCleared(clearedWindowId)
  }

  /**
   * Throws away the saved order. The saved order is demoted to the stale copy, not dropped, so
   * a swipe may still be served by it under checks while a fresh one is built. The listener is
   * told unless the throw-away came from an accessibility event, whose handler in
   * TalkBackService starts the background build itself with the event's own window.
   */
  private fun clear(reason: String, notifyListener: Boolean) {
    val clearedWindowId =
      synchronized(stateLock) {
        generation++
        invalidations++
        val windowId = root?.windowId ?: staleRoot?.windowId ?: NO_WINDOW_ID
        if (strategy != null) {
          staleRoot = root
          staleStrategy = strategy
          resetStructuralChanges()
        }
        root = null
        strategy = null
        firstIgnoredChangeTime = 0L
        resetGracedChanges()
        lastClearReason = reason
        windowId
      }
    armSettleBuild(clearedWindowId)
    if (notifyListener) {
      orderClearedListener?.onOrderCleared(clearedWindowId)
    }
  }

  /** The current order generation, captured by a background build when it is asked for. */
  internal fun currentGeneration(): Long = generation

  /**
   * Builds the order of [root]'s window on this thread and saves it, for a navigation that
   * arrived before any background build landed. Returns whether the built order was saved —
   * false when the window changed while it was being built. The build is given a copy of the
   * root to keep or throw away as its own; the caller's root is never the build's to recycle.
   */
  @JvmStatic
  fun buildOrderOnTheSpot(root: AccessibilityNodeInfoCompat): Boolean {
    @SuppressWarnings("deprecation") // obtain(): the copy the build owns, published or recycled.
    val copy = AccessibilityNodeInfoCompat.obtain(root)
    return TraversalOrderSnapshotBuilder.buildNow(
      root.windowId,
      generation,
      { copy },
      root.packageName?.toString() ?: "",
    )
  }

  /**
   * Asks for the order of the window [windowId] to be built in the background, so that the next
   * swipe finds it saved here instead of building it on the swiping thread. The root is resolved
   * by [rootSupplier] on the builder's thread, never here. Below Android 13 the background read
   * of the nodes is all that is offered, exactly as before this mechanism existed.
   */
  @JvmStatic
  @JvmOverloads
  fun buildOrderInBackground(
    windowId: Int,
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
    source: Int = Performance.ORDER_BUILD_SOURCE_EVENT_CLEAR,
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      Performance.getInstance().onOrderBuildPosted(source)
      WindowNodePrefetcher.prefetchInBackground(rootSupplier, ownPackage)
      return
    }
    snapshotBuildWindowId = windowId
    val outcome =
      TraversalOrderSnapshotBuilder.request(windowId, generation, rootSupplier, ownPackage, source)
    // A folded ask posted nothing: the build on its way, and the one follow-up it owes, are
    // what the posted count describes, so folded asks are counted by their own counter only.
    // A deferred ask posted nothing yet either: its build is counted when the hold lifts and
    // the ask is taken up for real.
    if (outcome != TraversalOrderSnapshotBuilder.OUTCOME_COALESCED &&
      outcome != TraversalOrderSnapshotBuilder.OUTCOME_DEFERRED) {
      Performance.getInstance().onOrderBuildPosted(source)
    }
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
      staleRoot = null
      staleStrategy = null
      resetStructuralChanges()
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
   * us, and TalkBack can navigate again in between, such as right after it scrolls a list. The
   * action's [target] says where the change will happen, so the demoted order can still serve
   * the nodes away from it.
   */
  @JvmStatic
  fun onNodeAction(actionId: Int, target: AccessibilityNodeInfoCompat?) {
    if (changesNodes(actionId)) {
      clear(
        if (LogUtils.shouldLog(Log.DEBUG)) AccessibilityNodeInfoUtils.actionToString(actionId)
        else ""
      )
      if (target != null) {
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        noteStructuralChange(target.windowId, bounds)
      } else {
        noteStructuralChange(NO_WINDOW_ID, null)
      }
    }
  }

  /** Whether [actionId] can change the nodes before the app's event about it arrives. */
  @JvmStatic
  fun changesNodes(actionId: Int): Boolean = actionId !in FOCUS_ACTIONS && actionId != SHOW_ON_SCREEN

  /** Throws away the saved order if [event] can change its window. Returns whether it did. */
  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
    val type = event.eventType
    if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) {
      forgetFocusIfCleared(event)
    }
    if (type and IGNORED_EVENT_TYPES != 0) {
      return false
    }
    val savedRoot = root
    if (savedRoot == null) {
      invalidateSnapshotBuild(event)
      noteStructuralChangeAgainstStale(event)
      // No order is saved, so no throw-away happened here to ask for a build — the blind spot
      // where a fresh window, or a build that already died, leaves the next swipe to build
      // the order itself. The settle retry is what asks, once the changes stop.
      armSettleBuild(event.windowId)
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
        },
        notifyListener = false,
      )
      // The change that threw the order away is the first one remembered against its stale
      // copy: where it happened decides which of the copy's nodes a swipe may still be served.
      noteStructuralChange(
        windowId,
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) sourceBounds(event) else null,
      )
      return true
    }
    return false
  }

  /**
   * No order is saved, but a stale one may be: a structural change that reaches it is remembered
   * against it, and a fresh build is asked for, exactly as if the order had just been thrown
   * away. Graced changes leave the stale order as it is — they cannot move a node within it, and
   * the nodes they touch are re-checked before any of them is focused.
   */
  private fun noteStructuralChangeAgainstStale(event: AccessibilityEvent) {
    val savedStaleRoot = staleRoot ?: return
    val type = event.eventType
    if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      val changes = event.contentChangeTypes
      if (changes != 0 && changes and NON_STRUCTURAL_CHANGES.inv() == 0) {
        return
      }
    }
    val windowId = event.windowId
    if (
      type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        windowId == NO_WINDOW_ID ||
        windowId == savedStaleRoot.windowId
    ) {
      noteStructuralChange(
        windowId,
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) sourceBounds(event) else null,
      )
      orderClearedListener?.onOrderCleared(savedStaleRoot.windowId)
    }
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
