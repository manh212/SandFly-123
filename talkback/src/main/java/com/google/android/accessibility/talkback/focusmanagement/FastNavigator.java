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
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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
import com.google.android.accessibility.utils.Role;
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
 * the spot; an end of the order scrolls the window from here, on its live tree, and leaves
 * where the focus goes next to the app; a target that died is replaced by the nearest
 * living one in the swipe's direction; a swipe in web
 * content is delegated to the page itself, which owns its elements and their order, and only
 * the reading controls the page cannot serve are read in this navigator's own web-inclusive
 * order. A swipe that truly cannot move is counted by its reason, and answered with the
 * end-of-list announcement from here, never swallowed quietly.
 */
public class FastNavigator {

  private static final String TAG = "FastNavigator";

  /**
   * A scrollable ancestor tall enough, against the outermost scrollable ancestor's height,
   * to be the page's own scroller rather than a small scroller nested inside an item.
   */
  private static final double SCROLLABLE_PAGE_HEIGHT_FRACTION = 0.8;

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
   * It is also rebuilt once the cache's order has been thrown away since it was built: this
   * order is kept apart from that one, so nothing else would ever retire it, and items the
   * window gained meanwhile are not in it.
   */
  private @Nullable OrderedTraversalStrategy webOrder;
  private int webOrderWindowId = -1;
  private long webOrderInvalidations = -1;

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

    if (WebInterfaceUtils.supportsWebActions(pivot)
        && !(Role.getRole(pivot) == Role.ROLE_WEB_VIEW
            && action.targetType == NavigationTarget.TARGET_DEFAULT
            && !forward)) {
      // The one case the page must not answer is left to the saved order below: the web
      // container itself swiping backwards at the default control looks for the native
      // element before the container — asking the page would land on its last element.
      return navigateInWeb(action, pivot, forward, rtl, eventId);
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
        // Unless the background route to this window's order has starved outright — builds
        // dying unpublished one after another — in which case the escalation answers once:
        // build from the tree as it stands now and publish however the window moves while
        // it is read, so the swipe walks a complete order of a moment ago instead of none.
        if (!TraversalTreeCache.orderBuildEscalationArmed(root.getWindowId())) {
          return null;
        }
        TraversalTreeCache.consumeOrderBuildEscalation(root.getWindowId());
        Performance.getInstance().onOrderBuildEscalated();
        AccessibilityNodeInfoCompat escalationRoot = freshRoot != null ? freshRoot : root;
        if (!TraversalTreeCache.buildOrderOnTheSpot(escalationRoot, /* forcePublish= */ true)) {
          return null;
        }
        root = escalationRoot;
      } else {
        root = freshRoot;
      }
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
        && webOrderInvalidations == TraversalTreeCache.invalidations()
        && webOrder.containsNode(pivot)) {
      return webOrder;
    }
    webOrder =
        new OrderedTraversalStrategy(root, /* includeChildrenOfNodesWithWebActions= */ true, false);
    webOrderWindowId = root.getWindowId();
    webOrderInvalidations = TraversalTreeCache.invalidations();
    Performance.getInstance().onFastNavigationHardCase(Performance.FAST_NAV_HARD_WEB_ORDER);
    return webOrder;
  }

  /**
   * A swipe whose focus stands in web content. The page itself is the authority on its
   * elements — which of them are stops, and in what order — so the swipe is delegated to
   * it, through the same feedback the original path sends, and the speech is composed when
   * the page's focus event comes back, as it is there: a delegated target is the page's to
   * choose, never this navigator's to predict. Only a reading control the page has no HTML
   * element name for is answered from this navigator's own web-inclusive order, as before.
   */
  private boolean navigateInWeb(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      boolean rtl,
      @Nullable EventId eventId) {
    Performance.getInstance().onWebSwipe();
    if (NavigationTarget.targetTypeToHtmlElement(action.targetType) == null) {
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
    return delegateToPage(action, pivot, forward, rtl, eventId);
  }

  /**
   * Asks the page to move the focus from {@code pivot} to its next element of the swipe's
   * kind: the page picks the element, scrolls it into view itself, and its focus event
   * drives the speech when it comes back. A refusal — the page has no further element
   * that way, and has already announced the edge itself — is answered by leaving the web
   * content for the nearest native element past it, as the original path falls back.
   */
  private boolean delegateToPage(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      boolean rtl,
      @Nullable EventId eventId) {
    Performance performance = Performance.getInstance();
    long delegateStart = SystemClock.uptimeMillis();
    boolean accepted =
        feedbackReturner != null
            && feedbackReturner.returnFeedback(
                eventId, Feedback.webDirectionHtml(pivot, action));
    recordSegment("fast nav web delegation", delegateStart);
    if (accepted) {
      performance.onWebDelegation();
      performance.onFastNavigationHandled();
      if (action.targetType == NavigationTarget.TARGET_DEFAULT) {
        // The page moves the focus, so its focus event shows where it went.
        ColumnWrap.expectWebFocus(pivot, forward, rtl);
      }
      TraversalTreeCache.rememberPivot(pivot);
      return true;
    }
    performance.onWebPageRefusal();
    return exitWebToNative(action, pivot, forward, eventId);
  }

  /**
   * The answer to a page that refused to move the focus: the nearest native element past
   * the web content in the swipe's direction, read from the saved order — which holds the
   * web container as its one web node and none of its children. A candidate of the web
   * subtree itself is never the exit: leaving the page for another of its elements would
   * hand the swipe back to the content that just refused it. The page has already
   * announced the edge, so a swipe with no native element to leave to is answered by
   * that announcement alone, never a second one from here.
   */
  private boolean exitWebToNative(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      @Nullable EventId eventId) {
    Performance performance = Performance.getInstance();
    AccessibilityNodeInfoCompat container =
        Role.getRole(pivot) == Role.ROLE_WEB_VIEW
            ? pivot
            : WebInterfaceUtils.ascendToWebView(pivot);
    OrderedTraversalStrategy order = container == null ? null : savedOrderFor(container);
    if (container == null || order == null) {
      performance.onFastNavigationHandled();
      performance.onFastNavigationEdge();
      return false;
    }
    List<AccessibilityNodeInfoCompat> fullOrder = order.dumpTree();
    int containerIndex = indexOfNode(fullOrder, container);
    if (containerIndex < 0) {
      performance.onFastNavigationHandled();
      performance.onFastNavigationEdge();
      return false;
    }
    Filter<AccessibilityNodeInfoCompat> filter = composeFilter(action.targetType, order);
    boolean substituted = false;
    int index = containerIndex;
    while (true) {
      index = nextCandidateIndex(fullOrder, index, forward, filter);
      if (index < 0) {
        performance.onFastNavigationHandled();
        performance.onFastNavigationEdge();
        return false;
      }
      AccessibilityNodeInfoCompat candidate = fullOrder.get(index);
      if (candidate.getWindowId() != pivot.getWindowId()
          || WebInterfaceUtils.supportsWebActions(candidate)
          || isInWebSubtree(candidate, container)) {
        // Another window's, or still the page's own: the nearest native one stands in.
        substituted = true;
        continue;
      }
      if (!candidate.isVisibleToUser()) {
        if (scrollFor(action, container, forward, candidate)) {
          performance.onWebNativeExit();
          return true;
        }
        substituted = true;
        continue;
      }
      if (fireTarget(candidate, container, action, eventId, substituted, /* countHandled= */ true)) {
        performance.onWebNativeExit();
        return true;
      }
      if (nextCandidateIndex(fullOrder, index, forward, filter) < 0) {
        performance.onFastNavigationHandled();
        performance.onFastNavigationEdge();
        return false;
      }
      // The action itself reported this target dead, and the order has another candidate
      // beyond it: step on, as a navigation in the order steps on.
      substituted = true;
    }
  }

  /** Whether {@code node} is {@code container} or stands inside its subtree. */
  private static boolean isInWebSubtree(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat container) {
    AccessibilityNodeInfoCompat current = node;
    while (current != null) {
      if (current.equals(container)) {
        return true;
      }
      current = current.getParent();
    }
    return false;
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
    boolean substituted = false;
    int index = pivotIndex;
    while (true) {
      index = nextCandidateIndex(fullOrder, index, forward, filter);
      if (index < 0) {
        return handleEdge(
            action, pivot, fullOrder, filter, forward, eventId, substituted,
            /* scrollFailed= */ false);
      }
      AccessibilityNodeInfoCompat candidate = fullOrder.get(index);
      if (candidate.getWindowId() != pivot.getWindowId()) {
        // Another window's: the nearest one of this window in the swipe's direction stands in.
        substituted = true;
        continue;
      }
      if (!candidate.isVisibleToUser() && !WebInterfaceUtils.supportsWebActions(candidate)) {
        // In the order but not on screen: the swipe has run ahead of the window's drawing.
        // Scrolling from here is this navigator's own work: the scroll fires on the
        // window's live tree and the app brings the item in. A web element is never
        // detoured this way: most of a page is off screen by nature, and firing focus on
        // the element lets the page scroll it into view itself, as a web element may be
        // focused unseen.
        if (scrollFor(action, pivot, forward, candidate)) {
          return true;
        }
        // The scroll this swipe needed could not be fired at all. Stepping on to another
        // candidate of this same order would fire focus on an item of the page already on
        // screen — the swipe asked to turn the page, and a sideways jump inside it is a
        // wrong answer, not a lesser one. The swipe is answered at the edge instead, by
        // the same path that answers an order run out of candidates.
        return handleEdge(
            action, pivot, fullOrder, filter, forward, eventId, substituted,
            /* scrollFailed= */ true);
      }
      if (fireTarget(candidate, pivot, action, eventId, substituted, /* countHandled= */ true)) {
        return true;
      }
      if (nextCandidateIndex(fullOrder, index, forward, filter) >= 0) {
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
      boolean anyTargetDead,
      boolean scrollFailed) {
    List<AccessibilityNodeInfoCompat> speaking = speakingList(fullOrder, filter);
    if (action.shouldScroll
        && !scrollFailed
        && scrollFor(action, pivot, forward, null)) {
      return true;
    }
    if (action.shouldWrap && FocusProcessorForLogicalNavigation.isWrapAroundEnabled()) {
      AccessibilityNodeInfoCompat wrapTarget =
          speaking.isEmpty() ? null : forward ? speaking.get(0) : speaking.get(speaking.size() - 1);
      if (wrapTarget != null && !wrapTarget.equals(pivot)) {
        if (fireTarget(wrapTarget, pivot, action, eventId, false, /* countHandled= */ true)) {
          return true;
        }
        // With the target refresh skipped, a refused action is how a dead wrap target shows
        // itself; the edge announcement below answers it.
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
   * Scrolls the window from here, in the swipe's direction — on the window's live tree,
   * the way the navigation this replaces always scrolled: the scroller is looked up at the
   * moment of the swipe, in the tree the service hands out now, never in a saved copy of
   * it, and never by climbing a remembered item's parent chain — a pager recycles or
   * detaches its items as the page turns, and a chain that ends at a torn-off fragment
   * offers nothing, though the window's scroller stands in the live tree all along.
   *
   * <p>The scroll is the whole of the answer. Where the focus goes once the content has
   * moved is the app's to decide and the standard event pipeline's to announce, as it was
   * before this navigator existed: nothing here waits for the content to settle, aims at
   * an item, or fires a focus of its own after the scroll.
   *
   * <p>The candidates come from the live counterparts of the item the swipe aimed at and
   * of the focused item, resolved in the fresh tree (see {@link #liveScrollCandidates}),
   * and are fired on in the order {@link #orderScrollCandidates} gives — the window's own
   * scroller first. A candidate that refuses the scroll does not end the swipe: with
   * nested pagers the first candidate is often the wrong one of the two, so the fire steps
   * on to the next candidate, and only a refusal by every candidate is a refusal of the
   * swipe.
   */
  private boolean scrollFor(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      @Nullable AccessibilityNodeInfoCompat preferNode) {
    long seekStart = SystemClock.uptimeMillis();
    List<AccessibilityNodeInfoCompat> ordered =
        orderScrollCandidates(liveScrollCandidates(pivot, preferNode, forward));
    recordSegment("fast nav scroll seek", seekStart);
    if (ordered.isEmpty()) {
      Performance.getInstance().onScrollNoScrollableNode();
      return false;
    }
    for (AccessibilityNodeInfoCompat candidate : ordered) {
      if (fireScrollAction(candidate, forward)) {
        Performance.getInstance()
            .onFastNavigationHardCase(Performance.FAST_NAV_HARD_SELF_SCROLL);
        Performance.getInstance().onFastNavigationHandled();
        return true;
      }
      Performance.getInstance().onScrollFiredAppRefused();
    }
    return false;
  }

  /**
   * The scroll candidates of this swipe, nearest first, read from the window's live tree:
   * the scrollable ancestors of the live counterpart of the item the swipe aimed at — its
   * scroller is the one that brings it on screen, the way the navigation this replaces
   * scrolled for a target it could not show — then of the focused item's live counterpart.
   * A counterpart the window has dropped offers nothing, and the other chain, or the tree
   * itself, answers instead. When neither chain offers a scroller, every scrollable node
   * of the tree is offered, smallest first, so the largest stands last, where {@link
   * #orderScrollCandidates} reads the outermost candidate for its page-level measure — a
   * flat search has no nearness of its own.
   */
  private List<AccessibilityNodeInfoCompat> liveScrollCandidates(
      AccessibilityNodeInfoCompat pivot,
      @Nullable AccessibilityNodeInfoCompat preferNode,
      boolean forward) {
    List<AccessibilityNodeInfoCompat> nearestFirst = new ArrayList<>();
    AccessibilityNodeInfoCompat root = liveRootOfWindow(pivot.getWindowId());
    if (root == null) {
      return nearestFirst;
    }
    if (preferNode != null) {
      AccessibilityNodeInfoCompat livePrefer = findInLiveTree(root, preferNode);
      if (livePrefer != null) {
        addScrollCandidates(nearestFirst, scrollableAncestors(livePrefer, forward));
      }
    }
    AccessibilityNodeInfoCompat livePivot = findInLiveTree(root, pivot);
    if (livePivot != null) {
      addScrollCandidates(nearestFirst, scrollableAncestors(livePivot, forward));
    }
    if (nearestFirst.isEmpty()) {
      List<AccessibilityNodeInfoCompat> inTree = new ArrayList<>();
      collectScrollables(
          root,
          forward
              ? AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
              : AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,
          inTree);
      inTree.sort(
          (a, b) -> {
            Rect boundsA = new Rect();
            a.getBoundsInScreen(boundsA);
            Rect boundsB = new Rect();
            b.getBoundsInScreen(boundsB);
            return Integer.compare(
                boundsA.width() * boundsA.height(), boundsB.width() * boundsB.height());
          });
      nearestFirst.addAll(inTree);
    }
    return nearestFirst;
  }

  /** Adds {@code candidates} to {@code into}, skipping any it already holds. */
  private static void addScrollCandidates(
      List<AccessibilityNodeInfoCompat> into, List<AccessibilityNodeInfoCompat> candidates) {
    for (AccessibilityNodeInfoCompat candidate : candidates) {
      if (!into.contains(candidate)) {
        into.add(candidate);
      }
    }
  }

  /**
   * The node of the live tree {@code root} that is {@code node}, or null when the window
   * has dropped it: the fresh handle the scroll's lookups run on, where the remembered
   * node's own chain may already end at a detached fragment.
   */
  private static @Nullable AccessibilityNodeInfoCompat findInLiveTree(
      AccessibilityNodeInfoCompat root, AccessibilityNodeInfoCompat node) {
    if (root.equals(node)) {
      return root;
    }
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = root.getChild(i);
      if (child == null) {
        continue;
      }
      AccessibilityNodeInfoCompat found = findInLiveTree(child, node);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** Every node of the live tree under {@code node} that can scroll {@code scrollAction}. */
  private static void collectScrollables(
      AccessibilityNodeInfoCompat node,
      int scrollAction,
      List<AccessibilityNodeInfoCompat> out) {
    if (isScrollableFor(node, scrollAction)) {
      out.add(node);
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child != null) {
        collectScrollables(child, scrollAction, out);
      }
    }
  }

  /** Whether {@code node} can scroll in the direction {@code scrollAction} names. */
  private static boolean isScrollableFor(
      AccessibilityNodeInfoCompat node, int scrollAction) {
    if (node.isScrollable()) {
      return true;
    }
    for (AccessibilityNodeInfoCompat.AccessibilityActionCompat action : node.getActionList()) {
      if (action.getId() == scrollAction) {
        return true;
      }
    }
    return false;
  }

  /** Fires one scroll action on {@code scrollable}, timed as its own swipe segment. */
  private boolean fireScrollAction(AccessibilityNodeInfoCompat scrollable, boolean forward) {
    int scrollAction =
        forward
            ? AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD;
    long actionStart = SystemClock.uptimeMillis();
    boolean fired = scrollable.performAction(scrollAction);
    recordSegment("fast nav scroll action", actionStart);
    return fired;
  }

  /**
   * The root of the window {@code windowId} as the service has it now: the fresh tree of
   * the window, read at the moment it is asked for — the tree a scroll looks its scroller
   * up in, where a remembered item's broken parent chain cannot strand the lookup. Falls
   * back to the active window's root when it is this window. Null when the window is gone.
   */
  private @Nullable AccessibilityNodeInfoCompat liveRootOfWindow(int windowId) {
    for (AccessibilityWindowInfo window : service.getWindows()) {
      boolean match = window.getId() == windowId;
      AccessibilityNodeInfo root = match ? window.getRoot() : null;
      window.recycle();
      if (root != null) {
        return AccessibilityNodeInfoCompat.wrap(root);
      }
    }
    AccessibilityNodeInfoCompat root =
        AccessibilityServiceCompatUtils.getRootInActiveWindow(service);
    return root != null && root.getWindowId() == windowId ? root : null;
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
    // The focus action fires before the target's speech is composed and submitted, so the
    // action never waits behind the announcement; both calls run on this thread one after
    // the other, so no focus event can arrive between them.
    boolean landed = target.performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
    recordSegment("fast nav focus action", actionStart);
    if (landed && focusListener != null) {
      long speculateStart = SystemClock.uptimeMillis();
      focusListener.onAccessibilityFocusAboutToBeSet(target, info, eventId, actionTime);
      recordSegment("fast nav speculate", speculateStart);
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
   * Every ancestor of {@code node} that can scroll in the swipe's direction, nearest first:
   * the node itself counts, as a list is its own scroller.
   */
  private static List<AccessibilityNodeInfoCompat> scrollableAncestors(
      AccessibilityNodeInfoCompat node, boolean forward) {
    int scrollAction =
        forward
            ? AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD;
    List<AccessibilityNodeInfoCompat> candidates = new ArrayList<>();
    AccessibilityNodeInfoCompat current = node;
    while (current != null) {
      if (isScrollableFor(current, scrollAction)) {
        candidates.add(current);
      }
      current = current.getParent();
    }
    return candidates;
  }

  /**
   * The order a scroll fires its candidates in: the page's own scroller first. The nearest
   * scrollable ancestor is not always the window's scroller — an item can hold a small
   * scroller of its own (a row of chips, a comment thread), and firing the scroll there
   * answers true while the page never turns. Candidates tall enough against the outermost
   * candidate to be page-level are ordered by area, largest first; the rest follow in
   * nearness order, as the fallbacks the fire steps through when a candidate refuses.
   * When no candidate is page-level the order is plain nearness — the choice the
   * nearest-ancestor walk always made.
   */
  static List<AccessibilityNodeInfoCompat> orderScrollCandidates(
      List<AccessibilityNodeInfoCompat> nearestFirst) {
    List<AccessibilityNodeInfoCompat> ordered = new ArrayList<>();
    if (nearestFirst.size() <= 1) {
      ordered.addAll(nearestFirst);
      return ordered;
    }
    Rect outermostBounds = new Rect();
    nearestFirst.get(nearestFirst.size() - 1).getBoundsInScreen(outermostBounds);
    int pageHeight = outermostBounds.height();
    List<AccessibilityNodeInfoCompat> pageLevel = new ArrayList<>();
    List<AccessibilityNodeInfoCompat> nested = new ArrayList<>();
    for (AccessibilityNodeInfoCompat candidate : nearestFirst) {
      Rect bounds = new Rect();
      candidate.getBoundsInScreen(bounds);
      if (pageHeight > 0 && bounds.height() >= SCROLLABLE_PAGE_HEIGHT_FRACTION * pageHeight) {
        pageLevel.add(candidate);
      } else {
        nested.add(candidate);
      }
    }
    if (pageLevel.isEmpty()) {
      ordered.addAll(nearestFirst);
      return ordered;
    }
    pageLevel.sort(
        (a, b) -> {
          Rect boundsA = new Rect();
          a.getBoundsInScreen(boundsA);
          Rect boundsB = new Rect();
          b.getBoundsInScreen(boundsB);
          return Integer.compare(
              boundsB.width() * boundsB.height(), boundsA.width() * boundsA.height());
        });
    ordered.addAll(pageLevel);
    ordered.addAll(nested);
    return ordered;
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
