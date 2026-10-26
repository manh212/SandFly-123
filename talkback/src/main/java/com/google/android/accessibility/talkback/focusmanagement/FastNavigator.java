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

package com.google.android.accessibility.talkback.focusmanagement;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseEngine;
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget.TargetType;
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.WindowUtils;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.accessibility.utils.traversal.DirectionalTraversalStrategy;
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A navigator of its own, answering directional navigation from the saved reading order while
 * it is switched on, instead of the navigation machinery TalkBack grew up with. It shares only
 * the neutral ground under both: the saved order and its builders, the prepared announcements,
 * and the speaking path the focus listener feeds. It never calls the machinery it replaces —
 * while it is on, that machinery sees no directional navigation at all.
 *
 * <p>A swipe is answered by arithmetic on the order: the focused node's place in it, the node
 * beside that place at the reading control in effect, one {@code refresh()} to vouch for the
 * target, and one focus action. The speech goes out as the action is fired, on the same
 * prediction, through the focus listener — the speaking path checks it against the focus event
 * when the event comes back, as it does for any focus set this way.
 *
 * <p>Nothing is handed back when a swipe is hard. An order that has not landed yet is built on
 * the spot; an end of the order scrolls from here and lands on the first new item; a target
 * that died is replaced by the nearest living one in the swipe's direction; web content is read
 * in this navigator's own web-inclusive order. A swipe that truly cannot move is counted by its
 * reason, and answered with the end-of-list announcement from here, never swallowed quietly.
 */
public class FastNavigator {

  private static final String TAG = "FastNavigator";

  /** How long after a scroll the content it brings is given to land, per try. */
  private static final long SCROLL_LANDING_DELAY_MS = 120;

  /** How many tries a scrolled-for item is given to land before the swipe is given up. */
  private static final int SCROLL_LANDING_ATTEMPTS = 4;

  /**
   * When the timed landing tries run for an event-driven landing, as absolute times after the
   * scroll: a first quick look, then two wider ones. The last is also the cap — a scroll whose
   * content has not landed 300 ms after it fired is given up, where the polling this replaces
   * could keep a swipe waiting some 480 ms. Content changes are the primary trigger and are
   * not bound by this schedule; these tries are only the backstop for a scroll that brings
   * its content quietly.
   */
  private static final long[] SCROLL_LANDING_BACKSTOP_TIMES_MS = {50, 150, 300};

  /** How long after a scroll a content change may still trigger its landing. */
  private static final long SCROLL_LANDING_EVENT_WINDOW_MS = 300;

  /** How many content changes may each trigger a landing try for one scroll. */
  private static final int SCROLL_LANDING_MAX_EVENT_TRIES = 8;

  /**
   * Told about the focus this navigator sets, in the same shape the other focus path tells its
   * listener, so the speaking path cannot tell the two apart: before the action, with the
   * final info, so speech can be submitted on the prediction; after it lands; and when the app
   * refuses it.
   */
  public interface FocusListener {
    void onAccessibilityFocusAboutToBeSet(
        AccessibilityNodeInfoCompat node,
        FocusActionInfo info,
        @Nullable EventId eventId,
        long actionTime);

    void onAccessibilityFocusSet(
        AccessibilityNodeInfoCompat node,
        FocusActionInfo info,
        @Nullable EventId eventId,
        long actionTime);

    void onAccessibilityFocusSetFailed(
        AccessibilityNodeInfoCompat node,
        FocusActionInfo info,
        @Nullable EventId eventId,
        long actionTime);
  }

  private final AccessibilityService service;
  private final FocusFinder focusFinder;
  private final AccessibilityFocusActionHistory history;
  private final ScreenStateMonitor.State screenState;
  private final BooleanSupplier selectionModeActive;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private Pipeline.@Nullable FeedbackReturner feedbackReturner;
  private @Nullable FocusListener focusListener;

  /**
   * This navigator's own order of a window with web content, web children included, which the
   * saved order leaves out. Kept per window and vouched for on every swipe: it serves only
   * while it still holds the focused node, and every target out of it is vouched for again.
   */
  private @Nullable OrderedTraversalStrategy webOrder;
  private int webOrderWindowId = -1;

  /** A scroll this navigator fired, still waiting for the item it was fired for. */
  private @Nullable PendingScrollLanding pendingScrollLanding;
  private final Runnable scrollLandingTask = this::runScrollLanding;

  /** A scroll fired for a swipe, and what landing from it takes. */
  private static final class PendingScrollLanding {
    final NavigationAction action;
    final boolean forward;
    final @Nullable AccessibilityNodeInfoCompat preferNode;
    final List<AccessibilityNodeInfoCompat> oldSpeaking;
    final @Nullable EventId eventId;
    /** Whether content changes trigger the landing tries, with timed tries as a backstop. */
    final boolean eventDriven;
    /** The window the scroll was fired in; content changes elsewhere are not its landing. */
    final int windowId;
    /** When the scroll was fired, by the uptime clock. */
    final long startedAtMs;
    /** Tries left, when the landing is timed (not event-driven). */
    int attemptsLeft;
    /** Which backstop time the next timed try runs at, when the landing is event-driven. */
    int backstopStage;
    /** How many content changes have already triggered a try for this scroll. */
    int eventTries;

    PendingScrollLanding(
        NavigationAction action,
        boolean forward,
        @Nullable AccessibilityNodeInfoCompat preferNode,
        List<AccessibilityNodeInfoCompat> oldSpeaking,
        @Nullable EventId eventId,
        int attemptsLeft,
        boolean eventDriven,
        int windowId,
        long startedAtMs) {
      this.action = action;
      this.forward = forward;
      this.preferNode = preferNode;
      this.oldSpeaking = oldSpeaking;
      this.eventId = eventId;
      this.attemptsLeft = attemptsLeft;
      this.eventDriven = eventDriven;
      this.windowId = windowId;
      this.startedAtMs = startedAtMs;
    }
  }

  public FastNavigator(
      AccessibilityService service,
      AccessibilityFocusActionHistory history,
      ScreenStateMonitor.State screenState,
      BooleanSupplier selectionModeActive) {
    this.service = service;
    this.focusFinder = new FocusFinder(service);
    this.history = history;
    this.screenState = screenState;
    this.selectionModeActive = selectionModeActive;
  }

  public void setFeedbackReturner(Pipeline.@Nullable FeedbackReturner feedbackReturner) {
    this.feedbackReturner = feedbackReturner;
  }

  public void setFocusListener(@Nullable FocusListener listener) {
    focusListener = listener;
  }

  /**
   * Answers one directional navigation action. Returns whether the focus moved, as the path
   * this replaces reported it: an edge answered with its announcement is not a move.
   */
  public boolean handle(NavigationAction action, @Nullable EventId eventId) {
    long start = SystemClock.uptimeMillis();
    TraversalTreeCache.noteNavigationActivity(start);
    Performance.getInstance().beginSwipeProfile();
    try {
      return handleInternal(action, eventId);
    } catch (RuntimeException e) {
      // A navigator that throws on the swipe's thread would take the gesture down with it; a
      // counted, announced failure is the worst a swipe here is allowed to come to.
      LogUtils.e(TAG, "Fast navigation failed: %s", e);
      Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_NO_ORDER);
      return false;
    } finally {
      long elapsed = SystemClock.uptimeMillis() - start;
      Performance.getInstance().recordSwipeSegment("fast navigation", elapsed);
      Performance.getInstance().endSwipeProfile(elapsed);
    }
  }

  /** Times one part of a navigation under its own segment name, from {@code startMs} to now. */
  private static void recordSegment(String name, long startMs) {
    Performance.getInstance().recordSwipeSegment(name, SystemClock.uptimeMillis() - startMs);
  }

  private boolean handleInternal(NavigationAction action, @Nullable EventId eventId) {
    boolean rtl = WindowUtils.isScreenLayoutRTL(service);
    int logicalDirection = TraversalStrategyUtils.getLogicalDirection(action.searchDirection, rtl);
    long pivotStart = SystemClock.uptimeMillis();
    AccessibilityNodeInfoCompat pivot = resolvePivot();
    recordSegment("fast nav pivot", pivotStart);

    if (pivot == null) {
      return handleWithoutPivot(action, logicalDirection, eventId);
    }
    if (selectionModeActive.getAsBoolean()) {
      // Selection mode's own swipes never reach navigation actions — they are granularity
      // moves, handled where gestures are read — so a directional action landing here in
      // selection mode is an item move, answered as one, as the old path answered it (it had
      // no selection branch either). It is counted so the case is visible, not silent.
      Performance.getInstance()
          .onFastNavigationHardCase(Performance.FAST_NAV_HARD_SELECTION_MODE);
    }
    if (!TraversalStrategyUtils.isLogicalDirection(logicalDirection)) {
      return handleSpatial(action, pivot, eventId);
    }
    boolean forward = logicalDirection == TraversalStrategy.SEARCH_FOCUS_FORWARD;

    if (WebInterfaceUtils.supportsWebActions(pivot)) {
      long orderStart = SystemClock.uptimeMillis();
      OrderedTraversalStrategy order = webOrderFor(pivot);
      recordSegment("fast nav order fetch", orderStart);
      if (order == null) {
        Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_NO_ORDER);
        announceEdge(action, forward, eventId);
        return false;
      }
      return navigateInOrder(action, pivot, order, forward, eventId, /* rebuilt= */ false);
    }

    long orderStart = SystemClock.uptimeMillis();
    OrderedTraversalStrategy order = savedOrderFor(pivot);
    recordSegment("fast nav order fetch", orderStart);
    if (order == null) {
      Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_NO_ORDER);
      announceEdge(action, forward, eventId);
      return false;
    }
    return navigateInOrder(action, pivot, order, forward, eventId, /* rebuilt= */ false);
  }

  /** The node a swipe starts from: the one the order remembers, or the app's own answer. */
  private @Nullable AccessibilityNodeInfoCompat resolvePivot() {
    AccessibilityNodeInfoCompat pivot = TraversalTreeCache.focusNodeForNavigation();
    if (pivot != null) {
      return pivot;
    }
    try {
      pivot = focusFinder.findAccessibilityFocus();
    } catch (RuntimeException e) {
      // The app would not say where its focus is; that is a missing pivot, not a crash.
      LogUtils.w(TAG, "Could not ask the app for its focus: %s", e);
      return null;
    }
    if (pivot != null && !pivot.refresh()) {
      return null;
    }
    return pivot;
  }

  /** A swipe with no focus anywhere to start from: the first or last item of the window. */
  private boolean handleWithoutPivot(
      NavigationAction action, int logicalDirection, @Nullable EventId eventId) {
    AccessibilityNodeInfoCompat root = AccessibilityServiceCompatUtils.getRootInActiveWindow(service);
    if (root == null || !TraversalStrategyUtils.isLogicalDirection(logicalDirection)) {
      Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_NO_PIVOT);
      return false;
    }
    boolean forward = logicalDirection == TraversalStrategy.SEARCH_FOCUS_FORWARD;
    OrderedTraversalStrategy order = orderForWindow(root, root);
    if (order == null) {
      Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_NO_ORDER);
      return false;
    }
    List<AccessibilityNodeInfoCompat> speaking =
        speakingList(order, action.targetType);
    if (speaking.isEmpty()) {
      announceEdge(action, forward, eventId);
      Performance.getInstance().onFastNavigationHandled();
      Performance.getInstance().onFastNavigationEdge();
      return false;
    }
    AccessibilityNodeInfoCompat target = forward ? speaking.get(0) : speaking.get(speaking.size() - 1);
    if (!TraversalTreeCache.getSkipTargetRefreshEnabled()) {
      long refreshStart = SystemClock.uptimeMillis();
      boolean alive = target.refresh();
      recordSegment("fast nav target refresh", refreshStart);
      if (!alive) {
        Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_ALL_TARGETS_DEAD);
        return false;
      }
    }
    return fireTarget(target, root, action, eventId, /* substituted= */ false, /* countHandled= */ true);
  }

  /**
   * The saved order holding {@code pivot}, building it on the spot when none has landed: the
   * build is the shared lean build, run here, and saved for the swipes after this one.
   */
  private @Nullable OrderedTraversalStrategy savedOrderFor(AccessibilityNodeInfoCompat pivot) {
    AccessibilityNodeInfoCompat root = TraversalTreeCache.rootFor(pivot);
    if (root == null) {
      root = rootOf(pivot);
    }
    if (root == null) {
      root = AccessibilityServiceCompatUtils.getRootInActiveWindow(service);
    }
    if (root == null) {
      return null;
    }
    return orderForWindow(root, pivot);
  }

  /** The saved order of {@code root}'s window holding {@code pivot}, built here if missing. */
  private @Nullable OrderedTraversalStrategy orderForWindow(
      AccessibilityNodeInfoCompat root, AccessibilityNodeInfoCompat pivot) {
    TraversalStrategy strategy = TraversalTreeCache.get(root, pivot);
    if (strategy instanceof OrderedTraversalStrategy ordered) {
      return ordered;
    }
    Performance.getInstance().onFastNavigationHardCase(Performance.FAST_NAV_HARD_BUILT_ON_SPOT);
    if (!TraversalTreeCache.buildOrderOnTheSpot(root)) {
      // The window changed while its order was being built; the build is owed once more from
      // the root as it stands now, and no more than once — a window churning faster than it
      // can be read is a counted failure, not a loop.
      AccessibilityNodeInfoCompat freshRoot = rootOf(pivot);
      if (freshRoot == null || !TraversalTreeCache.buildOrderOnTheSpot(freshRoot)) {
        return null;
      }
      root = freshRoot;
    }
    strategy = TraversalTreeCache.get(root, pivot);
    return strategy instanceof OrderedTraversalStrategy ordered ? ordered : null;
  }

  /** This navigator's own order of the web window {@code pivot} is in, web children in. */
  private @Nullable OrderedTraversalStrategy webOrderFor(AccessibilityNodeInfoCompat pivot) {
    AccessibilityNodeInfoCompat root = rootOf(pivot);
    if (root == null) {
      root = AccessibilityServiceCompatUtils.getRootInActiveWindow(service);
    }
    if (root == null) {
      return null;
    }
    if (webOrder != null
        && webOrderWindowId == root.getWindowId()
        && webOrder.containsNode(pivot)) {
      return webOrder;
    }
    webOrder =
        new OrderedTraversalStrategy(root, /* includeChildrenOfNodesWithWebActions= */ true, false);
    webOrderWindowId = root.getWindowId();
    Performance.getInstance().onFastNavigationHardCase(Performance.FAST_NAV_HARD_WEB_ORDER);
    return webOrder;
  }

  /**
   * The swipe itself, as arithmetic on {@code order}: from the pivot's place, the next node
   * the reading control's list offers, skipping any that died or belong elsewhere. A candidate
   * is vouched for by {@code refresh()} before firing, unless the skip-refresh switch is on:
   * then the focus action itself is the vouching — a dead node refuses it, and the walk steps
   * on to the next candidate in the same order, as a failed refresh used to make it do.
   */
  private boolean navigateInOrder(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      OrderedTraversalStrategy order,
      boolean forward,
      @Nullable EventId eventId,
      boolean rebuilt) {
    long searchStart = SystemClock.uptimeMillis();
    List<AccessibilityNodeInfoCompat> fullOrder = order.dumpTree();
    int pivotIndex = indexOfNode(fullOrder, pivot);
    if (pivotIndex < 0) {
      // The order in hand does not hold the focus it was asked to move: it is not this
      // window's order anymore, whatever it was when it was saved.
      Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_NO_ORDER);
      announceEdge(action, forward, eventId);
      return false;
    }
    Filter<AccessibilityNodeInfoCompat> filter = composeFilter(action.targetType, order);
    recordSegment("fast nav target search", searchStart);
    if (action.targetType != NavigationTarget.TARGET_DEFAULT) {
      Performance.getInstance()
          .onFastNavigationHardCase(Performance.FAST_NAV_HARD_GRANULARITY_ORDER);
    }
    boolean skipRefresh = TraversalTreeCache.getSkipTargetRefreshEnabled();
    boolean substituted = false;
    int index = pivotIndex;
    while (true) {
      index = nextCandidateIndex(fullOrder, index, forward, filter);
      if (index < 0) {
        return handleEdge(action, pivot, fullOrder, filter, forward, eventId, substituted);
      }
      AccessibilityNodeInfoCompat candidate = fullOrder.get(index);
      if (candidate.getWindowId() != pivot.getWindowId()) {
        // Another window's: the nearest one of this window in the swipe's direction stands in.
        substituted = true;
        continue;
      }
      if (!skipRefresh) {
        long refreshStart = SystemClock.uptimeMillis();
        boolean alive = candidate.refresh();
        recordSegment("fast nav target refresh", refreshStart);
        if (!alive) {
          // Dead: the nearest living one in the swipe's direction stands in for it, so the
          // swipe still lands somewhere true.
          substituted = true;
          continue;
        }
      }
      if (!candidate.isVisibleToUser()) {
        // In the order but not on screen: the swipe has run ahead of the window's drawing.
        // Scrolling from here is this navigator's own work, and the item is landed on when
        // the scroll brings it in.
        List<AccessibilityNodeInfoCompat> speaking = speakingList(fullOrder, filter);
        if (scrollFor(action, pivot, forward, candidate, speaking, eventId)) {
          return true;
        }
        substituted = true;
        continue;
      }
      if (fireTarget(candidate, pivot, action, eventId, substituted, /* countHandled= */ true)) {
        return true;
      }
      if (skipRefresh && nextCandidateIndex(fullOrder, index, forward, filter) >= 0) {
        // The action itself reported this target dead, and the order has another candidate
        // beyond it: step on within the same order instead of paying for a rebuild. The
        // substitution is counted by the fire that eventually lands, as refresh-skips are.
        substituted = true;
        continue;
      }
      // The app refused the one focus action. Read the window once more, as it now stands,
      // and answer from that — once, so a refusing app is a counted failure, not a loop.
      if (rebuilt) {
        Performance.getInstance()
            .onFastNavigationStuck(Performance.FAST_NAV_STUCK_FOCUS_ACTION_FAILED);
        return false;
      }
      TraversalTreeCache.clearCompletely("fast navigation focus failed");
      OrderedTraversalStrategy fresh = savedOrderFor(pivot);
      if (fresh == null) {
        Performance.getInstance()
            .onFastNavigationStuck(Performance.FAST_NAV_STUCK_FOCUS_ACTION_FAILED);
        return false;
      }
      return navigateInOrder(action, pivot, fresh, forward, eventId, /* rebuilt= */ true);
    }
  }

  /** The end of the order in the swipe's direction: wrap, scroll, or the edge announcement. */
  private boolean handleEdge(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      List<AccessibilityNodeInfoCompat> fullOrder,
      Filter<AccessibilityNodeInfoCompat> filter,
      boolean forward,
      @Nullable EventId eventId,
      boolean anyTargetDead) {
    List<AccessibilityNodeInfoCompat> speaking = speakingList(fullOrder, filter);
    if (action.shouldScroll && scrollFor(action, pivot, forward, null, speaking, eventId)) {
      return true;
    }
    if (action.shouldWrap && FocusProcessorForLogicalNavigation.isWrapAroundEnabled()) {
      AccessibilityNodeInfoCompat wrapTarget =
          speaking.isEmpty() ? null : forward ? speaking.get(0) : speaking.get(speaking.size() - 1);
      if (wrapTarget != null && !wrapTarget.equals(pivot)) {
        if (!TraversalTreeCache.getSkipTargetRefreshEnabled()) {
          long refreshStart = SystemClock.uptimeMillis();
          boolean alive = wrapTarget.refresh();
          recordSegment("fast nav target refresh", refreshStart);
          if (!alive) {
            wrapTarget = null;
          }
        }
        if (wrapTarget != null) {
          if (fireTarget(wrapTarget, pivot, action, eventId, false, /* countHandled= */ true)) {
            return true;
          }
          if (!TraversalTreeCache.getSkipTargetRefreshEnabled()) {
            return false;
          }
          // With the refresh skipped, a refused action is how a dead wrap target shows
          // itself; the refreshed path answers that with the edge announcement below.
        }
      }
    }
    announceEdge(action, forward, eventId);
    if (anyTargetDead) {
      // The swipe did not reach an edge so much as run out of living targets: that is a
      // failure to move, counted as one, and still answered with the edge announcement.
      Performance.getInstance()
          .onFastNavigationStuck(Performance.FAST_NAV_STUCK_ALL_TARGETS_DEAD);
    } else {
      Performance.getInstance().onFastNavigationHandled();
      Performance.getInstance().onFastNavigationEdge();
    }
    return false;
  }

  /**
   * Scrolls the window from here, in the swipe's direction, and arms the landing: when the
   * content the scroll brings has had its moment, the first new item in the direction is
   * focused — or {@code preferNode}, the item the swipe was already aimed at, once it shows.
   */
  private boolean scrollFor(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      @Nullable AccessibilityNodeInfoCompat preferNode,
      List<AccessibilityNodeInfoCompat> oldSpeaking,
      @Nullable EventId eventId) {
    AccessibilityNodeInfoCompat scrollable = scrollableAncestor(pivot, forward);
    if (scrollable == null && preferNode != null) {
      scrollable = scrollableAncestor(preferNode, forward);
    }
    if (scrollable == null) {
      return false;
    }
    int scrollAction =
        forward
            ? AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD;
    if (!scrollable.performAction(scrollAction)) {
      return false;
    }
    Performance.getInstance().onFastNavigationHardCase(Performance.FAST_NAV_HARD_SELF_SCROLL);
    Performance.getInstance().onFastNavigationHandled();
    handler.removeCallbacks(scrollLandingTask);
    boolean eventDriven = TraversalTreeCache.getEventDrivenScrollLandingEnabled();
    PendingScrollLanding landing =
        new PendingScrollLanding(
            action,
            forward,
            preferNode,
            oldSpeaking,
            eventId,
            SCROLL_LANDING_ATTEMPTS,
            eventDriven,
            pivot.getWindowId(),
            SystemClock.uptimeMillis());
    pendingScrollLanding = landing;
    if (eventDriven) {
      scheduleScrollBackstop(landing);
    } else {
      handler.postDelayed(scrollLandingTask, SCROLL_LANDING_DELAY_MS);
    }
    return true;
  }

  /** Arms the next timed try of an event-driven landing, at its backstop time. */
  private void scheduleScrollBackstop(PendingScrollLanding landing) {
    if (landing.backstopStage >= SCROLL_LANDING_BACKSTOP_TIMES_MS.length) {
      return;
    }
    long target = landing.startedAtMs + SCROLL_LANDING_BACKSTOP_TIMES_MS[landing.backstopStage];
    handler.postDelayed(
        scrollLandingTask, Math.max(0, target - SystemClock.uptimeMillis()));
  }

  /**
   * Tells the navigator that the content of {@code windowId} changed: when a scroll this
   * navigator fired is still waiting to land in that window, the change is tried as its
   * landing at once, instead of at the next timed try. The service calls this for content
   * changes and scroll events, after the order cache has had the event.
   */
  public void onWindowContentChanged(int windowId) {
    PendingScrollLanding landing = pendingScrollLanding;
    if (landing == null || !landing.eventDriven || landing.windowId != windowId) {
      return;
    }
    if (SystemClock.uptimeMillis() - landing.startedAtMs > SCROLL_LANDING_EVENT_WINDOW_MS
        || landing.eventTries >= SCROLL_LANDING_MAX_EVENT_TRIES) {
      return;
    }
    landing.eventTries++;
    tryScrollLanding(landing, /* fromEvent= */ true);
  }

  /** Runs one timed landing try for a scroll this navigator fired. */
  private void runScrollLanding() {
    PendingScrollLanding landing = pendingScrollLanding;
    if (landing == null) {
      return;
    }
    if (tryScrollLanding(landing, /* fromEvent= */ false)) {
      return;
    }
    if (landing.eventDriven) {
      landing.backstopStage++;
      if (landing.backstopStage >= SCROLL_LANDING_BACKSTOP_TIMES_MS.length) {
        giveUpScrollLanding(landing);
      } else {
        scheduleScrollBackstop(landing);
      }
    } else if (--landing.attemptsLeft > 0) {
      handler.postDelayed(scrollLandingTask, SCROLL_LANDING_DELAY_MS);
    } else {
      giveUpScrollLanding(landing);
    }
  }

  /** Gives up on a scroll's landing, counted loudly, and answers its swipe with the edge. */
  private void giveUpScrollLanding(PendingScrollLanding landing) {
    pendingScrollLanding = null;
    handler.removeCallbacks(scrollLandingTask);
    Performance.getInstance()
        .recordSwipeSegment("scroll landing wait", SystemClock.uptimeMillis() - landing.startedAtMs);
    Performance.getInstance()
        .onFastNavigationStuck(Performance.FAST_NAV_STUCK_SCROLL_NO_LANDING);
    announceEdge(landing.action, landing.forward, landing.eventId);
  }

  /**
   * One try at landing a scroll: rebuilds or reads the order the scroll left behind, and
   * focuses the first new item in its direction — or the item it was fired for, once that
   * shows. Returns whether the landing is settled, having focused its item.
   */
  private boolean tryScrollLanding(PendingScrollLanding landing, boolean fromEvent) {
    AccessibilityNodeInfoCompat anchor =
        landing.preferNode != null
            ? landing.preferNode
            : landing.oldSpeaking.isEmpty()
                ? null
                : landing.forward
                    ? landing.oldSpeaking.get(landing.oldSpeaking.size() - 1)
                    : landing.oldSpeaking.get(0);
    OrderedTraversalStrategy order = null;
    AccessibilityNodeInfoCompat root = null;
    if (anchor != null) {
      root = TraversalTreeCache.rootFor(anchor);
      if (root == null) {
        root = rootOf(anchor);
      }
    }
    if (root == null) {
      root = AccessibilityServiceCompatUtils.getRootInActiveWindow(service);
    }
    if (root != null) {
      TraversalStrategy strategy =
          anchor != null ? TraversalTreeCache.get(root, anchor) : TraversalTreeCache.get(root, root);
      if (strategy instanceof OrderedTraversalStrategy ordered) {
        order = ordered;
      } else if (TraversalTreeCache.buildOrderOnTheSpot(root)) {
        strategy =
            anchor != null
                ? TraversalTreeCache.get(root, anchor)
                : TraversalTreeCache.get(root, root);
        if (strategy instanceof OrderedTraversalStrategy orderedAgain) {
          order = orderedAgain;
        }
      }
    }
    if (order != null) {
      Filter<AccessibilityNodeInfoCompat> filter =
          composeFilter(landing.action.targetType, order);
      List<AccessibilityNodeInfoCompat> speaking = speakingList(order.dumpTree(), filter);
      int index =
          landingIndex(
              speaking, landing.oldSpeaking, landing.forward, node -> node.isVisibleToUser());
      AccessibilityNodeInfoCompat target = null;
      if (landing.preferNode != null) {
        int preferIndex = indexOfNode(speaking, landing.preferNode);
        if (preferIndex >= 0 && speaking.get(preferIndex).isVisibleToUser()) {
          target = speaking.get(preferIndex);
        }
      }
      if (target == null && index >= 0) {
        target = speaking.get(index);
      }
      if (target != null && target.refresh()) {
        pendingScrollLanding = null;
        handler.removeCallbacks(scrollLandingTask);
        Performance.getInstance()
            .recordSwipeSegment(
                "scroll landing wait", SystemClock.uptimeMillis() - landing.startedAtMs);
        Performance.getInstance().onScrollLandingSettled(fromEvent);
        AccessibilityNodeInfoCompat pivotForWrap =
            anchor != null ? anchor : target;
        fireTarget(target, pivotForWrap, landing.action, landing.eventId, false, false);
        return true;
      }
    }
    return false;
  }

  /**
   * A swipe in a direction of the screen rather than of the reading order — a d-pad direction,
   * say. The order cannot answer it by arithmetic, so the geometric search over the window is
   * used, as a builder is used: the answering, the vouching, and the firing are this
   * navigator's, as for any other swipe.
   */
  private boolean handleSpatial(
      NavigationAction action, AccessibilityNodeInfoCompat pivot, @Nullable EventId eventId) {
    AccessibilityNodeInfoCompat root = TraversalTreeCache.rootFor(pivot);
    if (root == null) {
      root = rootOf(pivot);
    }
    if (root == null) {
      Performance.getInstance().onFastNavigationStuck(Performance.FAST_NAV_STUCK_NO_PIVOT);
      return false;
    }
    DirectionalTraversalStrategy spatial = new DirectionalTraversalStrategy(root, focusFinder);
    Filter<AccessibilityNodeInfoCompat> filter =
        NavigationTarget.createNodeFilter(action.targetType, spatial.getSpeakingNodesCache());
    AccessibilityNodeInfoCompat target =
        TraversalStrategyUtils.searchFocus(spatial, pivot, action.searchDirection, filter);
    if (target == null || !target.refresh()) {
      announceEdge(action, action.searchDirection == TraversalStrategy.SEARCH_FOCUS_FORWARD, eventId);
      Performance.getInstance().onFastNavigationHandled();
      Performance.getInstance().onFastNavigationEdge();
      return false;
    }
    return fireTarget(target, pivot, action, eventId, false, /* countHandled= */ true);
  }

  /**
   * Fires the one focus action of a swipe, telling the focus listener as the action goes out —
   * speech on the prediction first, the telling of the landing after — and leaves the history
   * record the event interpreters match the coming focus event against. When the fire-first
   * switch is on, the order of the first two is swapped: the action goes out before the speech
   * is composed and submitted, so the focus never waits behind the speaking. Both calls run
   * back to back on this thread, so the app's focus event cannot arrive between them either
   * way; a refused action simply never starts any speech.
   */
  private boolean fireTarget(
      AccessibilityNodeInfoCompat target,
      AccessibilityNodeInfoCompat pivot,
      NavigationAction action,
      @Nullable EventId eventId,
      boolean substituted,
      boolean countHandled) {
    long actionStart = SystemClock.uptimeMillis();
    FocusActionInfo info =
        FocusActionInfo.builder()
            .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
            .setNavigationAction(action)
            .setColumnWrap(
                ColumnWrap.detect(
                    pivot,
                    target,
                    action.searchDirection != TraversalStrategy.SEARCH_FOCUS_BACKWARD,
                    WindowUtils.isScreenLayoutRTL(service)))
            .build();
    long actionTime = SystemClock.uptimeMillis();
    boolean fireFirst = TraversalTreeCache.getFireFocusBeforeSpeechEnabled();
    boolean landed;
    if (fireFirst) {
      landed = target.performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
      recordSegment("fast nav focus action", actionStart);
      if (landed && focusListener != null) {
        long speculateStart = SystemClock.uptimeMillis();
        focusListener.onAccessibilityFocusAboutToBeSet(target, info, eventId, actionTime);
        recordSegment("fast nav speculate", speculateStart);
      }
    } else {
      if (focusListener != null) {
        long speculateStart = SystemClock.uptimeMillis();
        focusListener.onAccessibilityFocusAboutToBeSet(target, info, eventId, actionTime);
        recordSegment("fast nav speculate", speculateStart);
      }
      landed = target.performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
      recordSegment("fast nav focus action", actionStart);
    }
    if (!landed) {
      if (focusListener != null) {
        focusListener.onAccessibilityFocusSetFailed(target, info, eventId, actionTime);
      }
      return false;
    }
    long bookkeepingStart = SystemClock.uptimeMillis();
    if (focusListener != null) {
      focusListener.onAccessibilityFocusSet(target, info, eventId, actionTime);
    }
    recordHistory(target, info, actionTime);
    TraversalTreeCache.rememberFocus(target);
    if (substituted) {
      Performance.getInstance()
          .onFastNavigationHardCase(Performance.FAST_NAV_HARD_DEAD_TARGET_SUBSTITUTED);
    }
    if (countHandled) {
      Performance.getInstance().onFastNavigationHandled();
    }
    recordSegment("fast nav bookkeeping", bookkeepingStart);
    return true;
  }

  /**
   * The history record of a focus action, posted as the other path posts it: building it walks
   * the node's ancestors, which no swipe should wait for, and the queue puts it ahead of the
   * app's focus event, which still has to come back through the event queue.
   */
  private void recordHistory(
      AccessibilityNodeInfoCompat node, FocusActionInfo info, long actionTime) {
    @SuppressWarnings("deprecation") // obtain(): the history keeps the node it is given.
    AccessibilityNodeInfoCompat nodeForRecord = AccessibilityNodeInfoCompat.obtain(node);
    handler.post(
        () ->
            history.onAccessibilityFocusAction(
                nodeForRecord, info, actionTime, screenState.getStableScreenState()));
  }

  /** The end-of-list announcement, in the words the path this replaces announces it with. */
  private void announceEdge(NavigationAction action, boolean forward, @Nullable EventId eventId) {
    if (feedbackReturner == null) {
      return;
    }
    String targetName = NavigationTarget.nativeTargetToDisplayName(service, action.targetType);
    if (targetName == null || targetName.isEmpty()) {
      return;
    }
    String text = service.getString(forward ? R.string.end_of_page : R.string.start_of_page, targetName);
    SpeechController.SpeakOptions speakOptions =
        SpeechController.SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH)
            .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK);
    feedbackReturner.returnFeedback(eventId, Feedback.speech(text, speakOptions));
  }

  /** The filter of a reading control over an order, collapse folding included for plain items. */
  private Filter<AccessibilityNodeInfoCompat> composeFilter(
      @TargetType int targetType, OrderedTraversalStrategy order) {
    Filter<AccessibilityNodeInfoCompat> filter =
        NavigationTarget.createNodeFilter(targetType, order.getSpeakingNodesCache());
    if (filter == null) {
      // A web-only reading control has no node filter; its items are the order's items.
      filter = NavigationTarget.createNodeFilter(
          NavigationTarget.TARGET_DEFAULT, order.getSpeakingNodesCache());
    }
    if (targetType == NavigationTarget.TARGET_DEFAULT) {
      filter = CollapseEngine.composeNodeFilter(service, filter);
    }
    return filter;
  }

  /** The nodes of {@code fullOrder} the reading control's {@code filter} offers, in order. */
  private static List<AccessibilityNodeInfoCompat> speakingList(
      List<AccessibilityNodeInfoCompat> fullOrder, Filter<AccessibilityNodeInfoCompat> filter) {
    List<AccessibilityNodeInfoCompat> speaking = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node : fullOrder) {
      if (filter.accept(node)) {
        speaking.add(node);
      }
    }
    return speaking;
  }

  private List<AccessibilityNodeInfoCompat> speakingList(
      OrderedTraversalStrategy order, @TargetType int targetType) {
    return speakingList(order.dumpTree(), composeFilter(targetType, order));
  }

  /** The place of {@code node} in {@code list}, or -1: places are by the nodes themselves. */
  static int indexOfNode(List<AccessibilityNodeInfoCompat> list, AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < list.size(); i++) {
      if (node.equals(list.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The place of the next node after {@code fromIndex} in the swipe's direction that {@code
   * filter} offers, or -1 when the order runs out there. Pure arithmetic on the list: this is
   * the whole of what finding a swipe's target is, here.
   */
  static int nextCandidateIndex(
      List<AccessibilityNodeInfoCompat> fullOrder,
      int fromIndex,
      boolean forward,
      Filter<AccessibilityNodeInfoCompat> filter) {
    int step = forward ? 1 : -1;
    for (int i = fromIndex + step; i >= 0 && i < fullOrder.size(); i += step) {
      if (filter.accept(fullOrder.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Where a scroll's landing is, in the order as it stands after the scroll: the first item in
   * the swipe's direction that the old order did not have, past the last item the two orders
   * share — or, when the orders share nothing, the first item on screen in that direction; a
   * backward landing mirrors it. -1 when nothing on screen is new.
   */
  static int landingIndex(
      List<AccessibilityNodeInfoCompat> newSpeaking,
      List<AccessibilityNodeInfoCompat> oldSpeaking,
      boolean forward,
      Predicate<AccessibilityNodeInfoCompat> visible) {
    if (forward) {
      int lastShared = -1;
      for (int i = 0; i < newSpeaking.size(); i++) {
        if (indexOfNode(oldSpeaking, newSpeaking.get(i)) >= 0) {
          lastShared = i;
        }
      }
      for (int i = lastShared + 1; i < newSpeaking.size(); i++) {
        if (visible.test(newSpeaking.get(i))) {
          return i;
        }
      }
      if (lastShared < 0) {
        for (int i = 0; i < newSpeaking.size(); i++) {
          if (visible.test(newSpeaking.get(i))) {
            return i;
          }
        }
      }
      return -1;
    }
    int firstShared = newSpeaking.size();
    for (int i = newSpeaking.size() - 1; i >= 0; i--) {
      if (indexOfNode(oldSpeaking, newSpeaking.get(i)) >= 0) {
        firstShared = i;
      }
    }
    for (int i = firstShared - 1; i >= 0; i--) {
      if (visible.test(newSpeaking.get(i))) {
        return i;
      }
    }
    if (firstShared >= newSpeaking.size()) {
      for (int i = newSpeaking.size() - 1; i >= 0; i--) {
        if (visible.test(newSpeaking.get(i))) {
          return i;
        }
      }
    }
    return -1;
  }

  /** The nearest ancestor of {@code node} that can scroll in the swipe's direction, if any. */
  private static @Nullable AccessibilityNodeInfoCompat scrollableAncestor(
      AccessibilityNodeInfoCompat node, boolean forward) {
    int scrollAction =
        forward
            ? AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD;
    AccessibilityNodeInfoCompat current = node;
    while (current != null) {
      if (current.isScrollable()) {
        return current;
      }
      for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action :
          current.getActionList()) {
        if (action.getId() == scrollAction) {
          return current;
        }
      }
      current = current.getParent();
    }
    return null;
  }

  /** The root of the window {@code node} is in, by walking up from it. */
  private static @Nullable AccessibilityNodeInfoCompat rootOf(AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat current = node;
    AccessibilityNodeInfoCompat parent;
    while ((parent = current.getParent()) != null) {
      current = parent;
    }
    return current;
  }
}
