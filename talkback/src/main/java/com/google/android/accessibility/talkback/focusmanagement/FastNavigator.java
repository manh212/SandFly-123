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
 * that died is replaced by the nearest living one in the swipe's direction; a swipe in web
 * content is delegated to the page itself, which owns its elements and their order, and only
 * the reading controls the page cannot serve are read in this navigator's own web-inclusive
 * order. A swipe that truly cannot move is counted by its reason, and answered with the
 * end-of-list announcement from here, never swallowed quietly.
 */
public class FastNavigator {

  private static final String TAG = "FastNavigator";

  /**
   * How long a scroll's window must stay quiet before its landing is tried: a pager's snap,
   * the new page attaching, and its rebinding all send changes, and a try that runs while
   * they are still arriving reads a tree mid-flight. Every change in the window starts this
   * quiet window over, so the try runs on the tree as it stands once the changes stop.
   */
  private static final long SCROLL_LANDING_SETTLE_QUIET_MS = 150;

  /**
   * The longest a scroll's landing may take, all in. Past it, the landing falls back to
   * position alone and then gives up loudly: a feed page has either landed by then or is
   * churning without settling, and more waiting only delays the edge answer.
   */
  private static final long SCROLL_LANDING_DEADLINE_MS = 1200;

  /**
   * How long a fired scroll may change nothing at all before it is counted as having no
   * effect: a scroll the app accepted but did not act on (or fired at a list already at its
   * end) brings no change event, so silence past this is itself the evidence.
   */
  private static final long SCROLL_NO_EFFECT_WINDOW_MS = 200;

  /** The closest together two landing tries for one scroll may run. */
  private static final long SCROLL_LANDING_MIN_TRY_SPACING_MS = 50;

  /** How often a waiting landing re-checks for the order its background build owes it. */
  private static final long SCROLL_LANDING_POLL_MS = 150;

  /**
   * The quiet an early landing try asks for — far shorter than the settle quiet: an early
   * try runs while the window may still be changing (a playing page changes without end),
   * so it is allowed only once this much quiet has passed, and it may land only on a target
   * that is unambiguous on the tree as it stands — the item the swipe aimed at, showing, or
   * an item newly on screen. Anything less clear waits for the full settle or the deadline.
   */
  private static final long SCROLL_LANDING_EARLY_QUIET_MS = 50;

  /**
   * How long a scroll must have been waiting before an early try is allowed at all: a
   * pager's snap takes about this long, and a try before it reads the page still turning.
   */
  private static final long SCROLL_LANDING_EARLY_MIN_ELAPSED_MS = 250;

  /** How many focus fires one landing try may spend before it waits for a fresher tree. */
  private static final int SCROLL_LANDING_MAX_FIRES_PER_TRY = 3;

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

  /** A scroll this navigator fired, still waiting for the item it was fired for. */
  private @Nullable PendingScrollLanding pendingScrollLanding;
  private final Runnable scrollLandingTask = this::runScrollLanding;

  /**
   * When the landing's next check is due, by the uptime clock; zero when none is scheduled.
   * Kept so a change in the landing's window can ask for a check without ever pushing the
   * one already due later — a window that changes without pause (a playing video page)
   * would otherwise postpone the check, and with it the landing deadline the check exists
   * to enforce, forever.
   */
  private long scrollLandingCheckDueMs;

  /** One item on screen when a scroll was fired: the node, and where it stood. */
  private static final class VisibleItem {
    final AccessibilityNodeInfoCompat node;
    final Rect bounds;

    VisibleItem(AccessibilityNodeInfoCompat node, Rect bounds) {
      this.node = node;
      this.bounds = bounds;
    }
  }

  /**
   * A scroll fired for a swipe, and what landing from it takes. The landing is anchored on
   * the window and on position — the live root of the window and a picture of what was on
   * screen when the scroll fired — never on an item node: a pager recycles or detaches its
   * items as the page turns, and an anchor on one of them points at a detached fragment by
   * the time the landing runs.
   */
  private static final class PendingScrollLanding {
    final NavigationAction action;
    final boolean forward;
    final @Nullable AccessibilityNodeInfoCompat preferNode;
    final List<AccessibilityNodeInfoCompat> oldSpeaking;
    final @Nullable EventId eventId;
    /** The window the scroll was fired in; content changes elsewhere are not its landing. */
    final int windowId;
    /** The window's root as the scroll fired, re-read live at every try. */
    final @Nullable AccessibilityNodeInfoCompat rootHint;
    /** What was on screen when the scroll fired: the landing's picture of "before". */
    final List<VisibleItem> visibleBefore;
    /** The scrollable ancestors the scroll may fire on, best first; see the no-effect retry. */
    final List<AccessibilityNodeInfoCompat> scrollCandidates;
    /** Which of {@link #scrollCandidates} the scroll last fired on. */
    int scrollCandidateIndex;
    /** The order generation when the scroll fired; only orders published after it may land it. */
    long generationAtScroll;
    /** When the scroll was fired, by the uptime clock; restarted by the no-effect retry. */
    long startedAtMs;
    /** Whether any change in the window has been seen since the scroll fired. */
    boolean changeSeen;
    /** When the window last changed, by the uptime clock. */
    long lastChangeMs;
    /** Whether the silence after firing has already been judged for effect once. */
    boolean noEffectHandled;
    /** Whether the one no-effect retry, on the next scrollable ancestor, has been spent. */
    boolean noEffectRetried;
    /** The generation the last try ran against; a try needs a newer one, or the deadline. */
    long lastTryGeneration = -1;
    /** The saved order's generation at the last try; a build landing moves this, not the above. */
    long lastTrySavedGeneration = -1;
    /** When the last try ran, by the uptime clock. */
    long lastTryMs;
    /** How many tries have run for this scroll. */
    int tries;
    /** How many times the window has gone quiet and been tried since the scroll fired. */
    int settleCloses;
    /** Whether a background build has been asked for this landing's post-scroll order. */
    boolean backgroundBuildAsked;
    /** Whether this landing's one on-the-spot build has been spent. */
    boolean onTheSpotBuildUsed;
    /** Whether any try ever read an order published after the scroll. */
    boolean usableOrderSeen;
    /** Whether the item the swipe aimed at was seen in a post-scroll order. */
    boolean preferSeenInOrder;
    /** The newest pre-scroll generation this landing has already counted refusing. */
    long preScrollGenerationCounted = Long.MIN_VALUE;
    /** Whether the window this scroll was fired in proved gone at a try. */
    boolean windowGone;

    PendingScrollLanding(
        NavigationAction action,
        boolean forward,
        @Nullable AccessibilityNodeInfoCompat preferNode,
        List<AccessibilityNodeInfoCompat> oldSpeaking,
        @Nullable EventId eventId,
        int windowId,
        @Nullable AccessibilityNodeInfoCompat rootHint,
        List<VisibleItem> visibleBefore,
        List<AccessibilityNodeInfoCompat> scrollCandidates,
        long generationAtScroll,
        long startedAtMs) {
      this.action = action;
      this.forward = forward;
      this.preferNode = preferNode;
      this.oldSpeaking = oldSpeaking;
      this.eventId = eventId;
      this.windowId = windowId;
      this.rootHint = rootHint;
      this.visibleBefore = visibleBefore;
      this.scrollCandidates = scrollCandidates;
      this.generationAtScroll = generationAtScroll;
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
        List<AccessibilityNodeInfoCompat> speaking = speakingList(fullOrder, filter);
        if (scrollFor(action, container, forward, candidate, speaking, eventId, fullOrder)) {
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
        // Scrolling from here is this navigator's own work, and the item is landed on when
        // the scroll brings it in. A web element is never detoured this way: most of a page
        // is off screen by nature, and firing focus on the element lets the page scroll it
        // into view itself, as a web element may be focused unseen.
        List<AccessibilityNodeInfoCompat> speaking = speakingList(fullOrder, filter);
        if (scrollFor(action, pivot, forward, candidate, speaking, eventId, fullOrder)) {
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
        && scrollFor(action, pivot, forward, null, speaking, eventId, fullOrder)) {
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
   * Scrolls the window from here, in the swipe's direction, and arms the landing: when the
   * content the scroll brings has settled, the first newly visible item in the direction is
   * focused — or {@code preferNode}, the item the swipe was already aimed at, once it shows.
   * The scroll fires on the window's own scroller (see {@link #orderScrollCandidates}), and
   * the landing remembers every candidate: a scroll that changes nothing is retried once on
   * the next candidate before it is given up on.
   *
   * <p>The candidates are the scrollable ancestors of the pivot — and of the aimed item,
   * when the pivot's own chain offers none. Both chains are walked on nodes read earlier,
   * and a pager recycles or detaches its items as the page turns: a chain that ends at a
   * torn-off fragment offers nothing, though the window's scroller stands in the order the
   * swipe is walking. So when both chains come up empty, the window's own nodes are
   * searched ({@link #scrollableInWindow}) before the swipe is given up on. And a candidate
   * that refuses the scroll does not end the swipe either: with nested pagers the first
   * candidate is often the wrong one of the two, so the fire steps on to the next candidate
   * — the same stepping the landing's no-effect retry does later — and only a refusal by
   * every candidate is a refusal of the swipe.
   */
  private boolean scrollFor(
      NavigationAction action,
      AccessibilityNodeInfoCompat pivot,
      boolean forward,
      @Nullable AccessibilityNodeInfoCompat preferNode,
      List<AccessibilityNodeInfoCompat> oldSpeaking,
      @Nullable EventId eventId,
      List<AccessibilityNodeInfoCompat> windowNodes) {
    long seekStart = SystemClock.uptimeMillis();
    List<AccessibilityNodeInfoCompat> candidates = scrollableAncestors(pivot, forward);
    if (candidates.isEmpty() && preferNode != null) {
      candidates = scrollableAncestors(preferNode, forward);
    }
    if (candidates.isEmpty()) {
      candidates = scrollableInWindow(windowNodes, pivot.getWindowId(), forward);
    }
    List<AccessibilityNodeInfoCompat> ordered = orderScrollCandidates(candidates);
    recordSegment("fast nav scroll seek", seekStart);
    if (ordered.isEmpty()) {
      Performance.getInstance().onScrollNoScrollableNode();
      return false;
    }
    AccessibilityNodeInfoCompat rootHint = TraversalTreeCache.rootFor(pivot);
    if (rootHint == null) {
      rootHint = rootOf(pivot);
    }
    List<VisibleItem> visibleBefore = visibleSnapshot(oldSpeaking);
    long generationAtScroll = TraversalTreeCache.orderGeneration();
    int firedIndex = -1;
    for (int i = 0; i < ordered.size(); i++) {
      if (fireScrollAction(ordered.get(i), forward)) {
        firedIndex = i;
        break;
      }
      Performance.getInstance().onScrollFiredAppRefused();
    }
    if (firedIndex < 0) {
      return false;
    }
    Performance.getInstance().onFastNavigationHardCase(Performance.FAST_NAV_HARD_SELF_SCROLL);
    Performance.getInstance().onFastNavigationHandled();
    if (pendingScrollLanding != null) {
      Performance.getInstance().onScrollLandingSuperseded();
    }
    handler.removeCallbacks(scrollLandingTask);
    scrollLandingCheckDueMs = 0;
    PendingScrollLanding landing =
        new PendingScrollLanding(
            action,
            forward,
            preferNode,
            oldSpeaking,
            eventId,
            pivot.getWindowId(),
            rootHint,
            visibleBefore,
            ordered,
            generationAtScroll,
            SystemClock.uptimeMillis());
    landing.scrollCandidateIndex = firedIndex;
    pendingScrollLanding = landing;
    scheduleScrollLandingCheck(SCROLL_NO_EFFECT_WINDOW_MS);
    return true;
  }

  /**
   * The scrollable nodes of one window, searched in the order the swipe is walking when no
   * ancestor chain offers a scroller: the window's pager is a node of its own order whether
   * or not the focused item still hangs under it. Handed to {@link #orderScrollCandidates}
   * smallest first, so the largest stands last, where that ordering reads the outermost
   * candidate for its page-level measure — a flat search has no nearness of its own.
   */
  private static List<AccessibilityNodeInfoCompat> scrollableInWindow(
      List<AccessibilityNodeInfoCompat> windowNodes, int windowId, boolean forward) {
    int scrollAction =
        forward
            ? AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD;
    List<AccessibilityNodeInfoCompat> found = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node : windowNodes) {
      if (node.getWindowId() == windowId
          && isScrollableFor(node, scrollAction)
          && !found.contains(node)) {
        found.add(node);
      }
    }
    found.sort(
        (a, b) -> {
          Rect boundsA = new Rect();
          a.getBoundsInScreen(boundsA);
          Rect boundsB = new Rect();
          b.getBoundsInScreen(boundsB);
          return Integer.compare(
              boundsA.width() * boundsA.height(), boundsB.width() * boundsB.height());
        });
    return found;
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

  /**
   * Schedules the landing's next check {@code delayMs} out — unless a check is already due
   * sooner, which stands: a check may be asked for earlier, never pushed later. The
   * landing's deadline is judged inside the check, so a window changing faster than the
   * settle quiet must not be able to starve the check itself.
   */
  private void scheduleScrollLandingCheck(long delayMs) {
    long due = SystemClock.uptimeMillis() + delayMs;
    if (scrollLandingCheckDueMs > 0 && scrollLandingCheckDueMs <= due) {
      return;
    }
    handler.removeCallbacks(scrollLandingTask);
    handler.postDelayed(scrollLandingTask, delayMs);
    scrollLandingCheckDueMs = due;
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

  /** The items of {@code speaking} on screen right now, with where each of them stands. */
  private static List<VisibleItem> visibleSnapshot(
      List<AccessibilityNodeInfoCompat> speaking) {
    List<VisibleItem> snapshot = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node : speaking) {
      if (node.isVisibleToUser()) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        snapshot.add(new VisibleItem(node, bounds));
      }
    }
    return snapshot;
  }

  /**
   * Tells the navigator that the content of {@code windowId} changed. The service calls this
   * for content changes and scroll events, after the order cache has had the event. A scroll
   * this navigator fired may be waiting to land in that window: the change is not tried as
   * its landing at once — a pager's snap sends its changes while the page is still moving —
   * but starts the landing's quiet window over, and the landing is tried once the window has
   * been quiet for {@link #SCROLL_LANDING_SETTLE_QUIET_MS}.
   */
  public void onWindowContentChanged(int windowId) {
    PendingScrollLanding landing = pendingScrollLanding;
    if (landing == null || landing.windowId != windowId) {
      return;
    }
    landing.changeSeen = true;
    landing.lastChangeMs = SystemClock.uptimeMillis();
    // The check is asked for a settle quiet out — but never past the landing's deadline:
    // a window that keeps changing (a playing page) would otherwise hold the check off
    // indefinitely, and the deadline it exists to enforce would never be reached.
    long untilDeadline =
        landing.startedAtMs + SCROLL_LANDING_DEADLINE_MS - landing.lastChangeMs;
    scheduleScrollLandingCheck(
        Math.min(SCROLL_LANDING_SETTLE_QUIET_MS, Math.max(0, untilDeadline)));
  }

  /**
   * The landing's one scheduled check, re-armed by every change in its window and by itself:
   * it judges the silence after firing (a scroll that changed nothing is retried once, on
   * the next scrollable ancestor), tries the landing once the window has gone quiet on a
   * tree newer than the last try, and at the deadline spends the landing's one on-the-spot
   * build, then its positional fallback, before giving up loudly.
   *
   * <p>A window that never goes quiet — a playing page sends content changes without end —
   * is not waited on for the full quiet when its answer is already clear: once the scroll
   * is {@link #SCROLL_LANDING_EARLY_MIN_ELAPSED_MS} old, a shorter quiet suffices for an
   * early try, provided an order published after the scroll exists to read. An early try
   * may land only on an unambiguous target (see {@link #tryScrollLanding}); a tree still in
   * flight offers none, and the settle and deadline paths run as before.
   */
  private void runScrollLanding() {
    PendingScrollLanding landing = pendingScrollLanding;
    if (landing == null) {
      return;
    }
    scrollLandingCheckDueMs = 0;
    long now = SystemClock.uptimeMillis();
    long elapsed = now - landing.startedAtMs;
    if (!landing.changeSeen
        && !landing.noEffectHandled
        && TraversalTreeCache.orderGeneration() == landing.generationAtScroll
        && elapsed >= SCROLL_NO_EFFECT_WINDOW_MS) {
      // The scroll fired and the window did not move at all: the app accepted a scroll it
      // did not act on, or the list was already at its end. Judged now, not at the deadline,
      // so a true end of the content is answered as fast as it can be told from a slow one.
      landing.noEffectHandled = true;
      Performance.getInstance().onScrollNoEffect();
      if (retryScrollOnNextCandidate(landing)) {
        scheduleScrollLandingCheck(SCROLL_NO_EFFECT_WINDOW_MS);
        return;
      }
      if (tryPositionalLanding(landing)) {
        return;
      }
      giveUpScrollLanding(landing);
      return;
    }
    boolean deadlineReached = elapsed >= SCROLL_LANDING_DEADLINE_MS;
    boolean settled =
        landing.changeSeen && now - landing.lastChangeMs >= SCROLL_LANDING_SETTLE_QUIET_MS;
    // A try needs a tree it has not tried: the generation moving on says the tree changed,
    // and the saved order's generation moving says a build landed — publishing does not move
    // the generation itself, so the pair of them is the try's currency.
    boolean generationNew =
        TraversalTreeCache.orderGeneration() != landing.lastTryGeneration
            || TraversalTreeCache.savedOrderGeneration() != landing.lastTrySavedGeneration;
    boolean spacingKept = now - landing.lastTryMs >= SCROLL_LANDING_MIN_TRY_SPACING_MS;
    boolean earlyTry =
        !deadlineReached
            && landing.changeSeen
            && elapsed >= SCROLL_LANDING_EARLY_MIN_ELAPSED_MS
            && now - landing.lastChangeMs >= SCROLL_LANDING_EARLY_QUIET_MS
            && generationNew
            && spacingKept
            && TraversalTreeCache.savedOrderGeneration() > landing.generationAtScroll;
    if ((settled && generationNew && spacingKept) || earlyTry || deadlineReached) {
      if (settled) {
        landing.settleCloses++;
      }
      boolean buildEligible =
          landing.settleCloses >= 2 || deadlineReached || landing.tries >= 2;
      if (tryScrollLanding(landing, buildEligible, /* early= */ earlyTry && !settled)) {
        return;
      }
      if (landing.windowGone) {
        giveUpScrollLanding(landing);
        return;
      }
      landing.lastTryGeneration = TraversalTreeCache.orderGeneration();
      landing.lastTrySavedGeneration = TraversalTreeCache.savedOrderGeneration();
      landing.lastTryMs = now;
      if (deadlineReached) {
        if (tryPositionalLanding(landing)) {
          return;
        }
        giveUpScrollLanding(landing);
        return;
      }
    }
    scheduleScrollLandingCheck(SCROLL_LANDING_POLL_MS);
  }

  /**
   * Fires the landing's scroll once more, on the next scrollable ancestor: the first node
   * accepted the scroll and nothing moved, so the scroll it wanted may live one level up —
   * a small scroller inside an item can take a scroll meant for the page. The landing's
   * clock and generation restart for the new firing; its picture of "before" stands, as the
   * window has not changed. Returns whether a retry was fired.
   */
  private boolean retryScrollOnNextCandidate(PendingScrollLanding landing) {
    int next = landing.scrollCandidateIndex + 1;
    if (landing.noEffectRetried || next >= landing.scrollCandidates.size()) {
      return false;
    }
    if (!fireScrollAction(landing.scrollCandidates.get(next), landing.forward)) {
      Performance.getInstance().onScrollFiredAppRefused();
      return false;
    }
    Performance.getInstance().onScrollRetriedOnNextAncestor();
    Performance.getInstance().onFastNavigationHardCase(Performance.FAST_NAV_HARD_SELF_SCROLL);
    landing.noEffectRetried = true;
    landing.scrollCandidateIndex = next;
    landing.startedAtMs = SystemClock.uptimeMillis();
    landing.generationAtScroll = TraversalTreeCache.orderGeneration();
    landing.noEffectHandled = false;
    return true;
  }

  /** Gives up on a scroll's landing, counted loudly, and answers its swipe with the edge. */
  private void giveUpScrollLanding(PendingScrollLanding landing) {
    pendingScrollLanding = null;
    handler.removeCallbacks(scrollLandingTask);
    scrollLandingCheckDueMs = 0;
    Performance performance = Performance.getInstance();
    performance.recordSwipeSegment(
        "scroll landing wait", SystemClock.uptimeMillis() - landing.startedAtMs);
    performance.recordSwipeSegment("scroll landing tries", landing.tries);
    performance.onFastNavigationStuck(Performance.FAST_NAV_STUCK_SCROLL_NO_LANDING);
    performance.onScrollLandingFailed(scrollLandingFailReason(landing));
    announceEdge(landing.action, landing.forward, landing.eventId);
  }

  /** Why a landing that never landed is given up on, of the reasons the statistics keep. */
  private static int scrollLandingFailReason(PendingScrollLanding landing) {
    if (landing.windowGone) {
      return Performance.SCROLL_LANDING_FAIL_WINDOW_GONE;
    }
    if (landing.preferNode != null && landing.preferSeenInOrder) {
      return Performance.SCROLL_LANDING_FAIL_PREFER_NEVER_VISIBLE;
    }
    if (landing.noEffectHandled && !landing.changeSeen) {
      // The scroll changed nothing at all: what failed to appear is a newly visible item.
      return Performance.SCROLL_LANDING_FAIL_NO_NEWLY_VISIBLE;
    }
    if (!landing.usableOrderSeen) {
      return Performance.SCROLL_LANDING_FAIL_NO_POST_SCROLL_ORDER;
    }
    return Performance.SCROLL_LANDING_FAIL_NO_NEWLY_VISIBLE;
  }

  /**
   * The live root of the landing's window: the root of the order the cache currently saves
   * for the window, else the root remembered when the scroll fired, else the active
   * window's root when it is the landing's window — each re-read by {@code refresh()}, so a
   * root the window has since dropped fails here. Null when the window is gone — its old
   * items are never walked up for a root, as a detached item's parent chain ends at the
   * fragment the pager tore off, not at the window.
   */
  private @Nullable AccessibilityNodeInfoCompat landingRoot(PendingScrollLanding landing) {
    AccessibilityNodeInfoCompat saved = TraversalTreeCache.rootOfSavedOrder(landing.windowId);
    if (saved != null && saved.refresh()) {
      return saved;
    }
    AccessibilityNodeInfoCompat hint = landing.rootHint;
    if (hint != null && hint.getWindowId() == landing.windowId && hint.refresh()) {
      return hint;
    }
    AccessibilityNodeInfoCompat root =
        AccessibilityServiceCompatUtils.getRootInActiveWindow(service);
    if (root != null && root.getWindowId() == landing.windowId) {
      return root;
    }
    return null;
  }

  /**
   * One try at landing a scroll, on a tree that has gone quiet: reads the window's order —
   * only one published after the scroll fired, so a mid-flight order the scroll's own first
   * moments left behind can never be served back to it — and focuses the first item that is
   * newly on screen in the scroll's direction, or the item the swipe aimed at, once it
   * shows. With no post-scroll order to read, the first try asks for one in the background
   * under the landing's own build source; a later try, when {@code buildEligible}, spends
   * the landing's one on-the-spot build on the settled tree instead. An {@code early} try —
   * one taken before the window has gone fully quiet — reads the same order but may land
   * only on an unambiguous target, never on the positional safety net. Returns whether the
   * landing is settled, having focused its item.
   */
  private boolean tryScrollLanding(
      PendingScrollLanding landing, boolean buildEligible, boolean early) {
    landing.tries++;
    AccessibilityNodeInfoCompat root = landingRoot(landing);
    if (root == null) {
      landing.windowGone = true;
      return false;
    }
    OrderedTraversalStrategy order = postScrollOrder(landing, root);
    if (order == null && !landing.backgroundBuildAsked) {
      landing.backgroundBuildAsked = true;
      TraversalTreeCache.buildOrderInBackground(
          landing.windowId,
          () -> rootOfWindowForBuild(landing.windowId),
          service.getPackageName(),
          Performance.ORDER_BUILD_SOURCE_SCROLL_LANDING);
    }
    if (order == null && buildEligible && !landing.onTheSpotBuildUsed) {
      landing.onTheSpotBuildUsed = true;
      if (TraversalTreeCache.buildOrderOnTheSpot(root)) {
        // The build just read the tree as it stands, settled: its order is this try's to
        // read however the generation gate below would judge it.
        order = savedOrderOf(landing, root);
        if (order != null) {
          landing.usableOrderSeen = true;
          return landFromOrder(
              landing, root, order, Performance.SCROLL_LANDING_BY_DEADLINE_BUILD, early);
        }
      }
    }
    if (order == null) {
      return false;
    }
    landing.usableOrderSeen = true;
    return landFromOrder(landing, root, order, Performance.SCROLL_LANDING_BY_SETTLE, early);
  }

  /**
   * The saved order of {@code root}'s window, when it was published after the landing's
   * scroll fired: the order the scroll left behind is the landing's to read, and an older
   * one — read from the tree as the scroll found it, or from the tree mid-flight — is not,
   * so being offered one is counted and refused.
   */
  private @Nullable OrderedTraversalStrategy postScrollOrder(
      PendingScrollLanding landing, AccessibilityNodeInfoCompat root) {
    OrderedTraversalStrategy order = savedOrderOf(landing, root);
    if (order == null) {
      return null;
    }
    long savedGeneration = TraversalTreeCache.savedOrderGeneration();
    if (savedGeneration <= landing.generationAtScroll) {
      if (savedGeneration != landing.preScrollGenerationCounted) {
        landing.preScrollGenerationCounted = savedGeneration;
        Performance.getInstance().onScrollLandingPreScrollOrderRead();
      }
      return null;
    }
    return order;
  }

  /** The saved order of {@code root}'s window holding the landing's anchor item, if it has one. */
  private @Nullable OrderedTraversalStrategy savedOrderOf(
      PendingScrollLanding landing, AccessibilityNodeInfoCompat root) {
    AccessibilityNodeInfoCompat anchor =
        landing.preferNode != null
            ? landing.preferNode
            : landing.oldSpeaking.isEmpty()
                ? root
                : landing.forward
                    ? landing.oldSpeaking.get(landing.oldSpeaking.size() - 1)
                    : landing.oldSpeaking.get(0);
    TraversalStrategy strategy = TraversalTreeCache.get(root, anchor);
    if (strategy instanceof OrderedTraversalStrategy ordered) {
      return ordered;
    }
    strategy = TraversalTreeCache.get(root, root);
    return strategy instanceof OrderedTraversalStrategy ordered ? ordered : null;
  }

  /**
   * Lands a scroll from {@code order}: the item the swipe aimed at once it shows, else the
   * first newly visible item in the scroll's direction. A target whose focus fire is refused
   * is stepped past within the same try, a few times at most. When {@code unambiguousOnly},
   * the landing-index safety net below is not used: on a tree that has not settled, only
   * the aimed item showing, or an item newly on screen, is a target clear enough to take.
   * Returns whether it landed.
   */
  private boolean landFromOrder(
      PendingScrollLanding landing,
      AccessibilityNodeInfoCompat root,
      OrderedTraversalStrategy order,
      int settleSource,
      boolean unambiguousOnly) {
    Filter<AccessibilityNodeInfoCompat> filter = composeFilter(landing.action.targetType, order);
    List<AccessibilityNodeInfoCompat> speaking = speakingList(order.dumpTree(), filter);
    List<AccessibilityNodeInfoCompat> candidates = new ArrayList<>();
    if (landing.preferNode != null) {
      int preferIndex = indexOfNode(speaking, landing.preferNode);
      if (preferIndex >= 0) {
        landing.preferSeenInOrder = true;
        AccessibilityNodeInfoCompat prefer = speaking.get(preferIndex);
        // A web element the swipe aimed at is landed on as it stands: the saved order is
        // not its measure (it leaves web elements out by design), and the page scrolls a
        // focused element into view itself, so the visibility a native item must show is
        // not asked of it.
        if (prefer.isVisibleToUser() || WebInterfaceUtils.supportsWebActions(prefer)) {
          candidates.add(prefer);
        }
      }
    }
    int scanFrom = landing.forward ? 0 : speaking.size() - 1;
    int scanStep = landing.forward ? 1 : -1;
    for (int i = scanFrom; i >= 0 && i < speaking.size(); i += scanStep) {
      AccessibilityNodeInfoCompat node = speaking.get(i);
      if (!candidates.contains(node) && node.isVisibleToUser() && isNewlyVisible(landing, node)) {
        candidates.add(node);
        break;
      }
    }
    if (candidates.isEmpty() && !unambiguousOnly) {
      int index =
          landingIndex(
              speaking, landing.oldSpeaking, landing.forward, node -> node.isVisibleToUser());
      if (index >= 0) {
        candidates.add(speaking.get(index));
      }
    }
    int fires = 0;
    for (AccessibilityNodeInfoCompat target : candidates) {
      if (fires >= SCROLL_LANDING_MAX_FIRES_PER_TRY || !target.refresh()) {
        continue;
      }
      fires++;
      if (fireLandingTarget(landing, target, settleSource)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code node} is newly on screen for this landing: it was not on screen when the
   * scroll fired, or it stands somewhere other than where it stood then — a recycled node
   * carrying the new page counts by its place, which is what a pager leaves to tell by.
   */
  private static boolean isNewlyVisible(
      PendingScrollLanding landing, AccessibilityNodeInfoCompat node) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    for (VisibleItem before : landing.visibleBefore) {
      if (before.node.equals(node)) {
        return !before.bounds.equals(bounds);
      }
    }
    return true;
  }

  /**
   * The landing's last answer, by position alone: the first item on screen now that was not
   * on screen when the scroll fired, nearest the edge the scroll moved towards — read from
   * whatever order the window has, of whatever age, or from the place the last navigation
   * started. Returns whether it landed.
   */
  private boolean tryPositionalLanding(PendingScrollLanding landing) {
    AccessibilityNodeInfoCompat root = landingRoot(landing);
    if (root == null) {
      landing.windowGone = true;
      return false;
    }
    TraversalStrategy strategy = TraversalTreeCache.get(root, root);
    if (strategy instanceof OrderedTraversalStrategy ordered) {
      Filter<AccessibilityNodeInfoCompat> filter =
          composeFilter(landing.action.targetType, ordered);
      List<AccessibilityNodeInfoCompat> speaking = speakingList(ordered.dumpTree(), filter);
      AccessibilityNodeInfoCompat target = null;
      int scanFrom = landing.forward ? 0 : speaking.size() - 1;
      int scanStep = landing.forward ? 1 : -1;
      for (int i = scanFrom; i >= 0 && i < speaking.size(); i += scanStep) {
        AccessibilityNodeInfoCompat node = speaking.get(i);
        if (node.isVisibleToUser() && isNewlyVisible(landing, node)) {
          // The item past the old screen's edge in the scroll's direction is the one whose
          // place the scroll was moving towards; the scan walks in that order already.
          target = node;
          break;
        }
      }
      if (target != null && target.refresh()) {
        return fireLandingTarget(landing, target, Performance.SCROLL_LANDING_BY_POSITION);
      }
    }
    AccessibilityNodeInfoCompat pivot = TraversalTreeCache.pivotAfterRemoval(root);
    if (pivot != null
        && pivot.getWindowId() == landing.windowId
        && pivot.isVisibleToUser()
        && indexOfNode(landing.oldSpeaking, pivot) < 0
        && pivot.refresh()) {
      return fireLandingTarget(landing, pivot, Performance.SCROLL_LANDING_BY_POSITION);
    }
    return false;
  }

  /**
   * Settles a landing on {@code target}: the bookkeeping every settled landing shares, then
   * the one focus fire. A refused fire is the caller's to step past, so the landing stays
   * armed and uncounted until a fire lands.
   */
  private boolean fireLandingTarget(
      PendingScrollLanding landing,
      AccessibilityNodeInfoCompat target,
      int settleSource) {
    AccessibilityNodeInfoCompat pivotForWrap =
        landing.preferNode != null ? landing.preferNode : target;
    if (!fireTarget(target, pivotForWrap, landing.action, landing.eventId, false, false)) {
      return false;
    }
    pendingScrollLanding = null;
    handler.removeCallbacks(scrollLandingTask);
    scrollLandingCheckDueMs = 0;
    Performance performance = Performance.getInstance();
    performance.recordSwipeSegment(
        "scroll landing wait", SystemClock.uptimeMillis() - landing.startedAtMs);
    performance.recordSwipeSegment("scroll landing tries", landing.tries);
    performance.onScrollLandingSettled(landing.changeSeen);
    performance.onScrollLandingSettledSource(settleSource);
    return true;
  }

  /**
   * The root of the window {@code windowId}, for a background build's root supplier: runs on
   * the builder's thread, where the window list and a window's root are plain binder calls.
   * Null when the window is already gone.
   */
  private @Nullable AccessibilityNodeInfoCompat rootOfWindowForBuild(int windowId) {
    for (AccessibilityWindowInfo window : service.getWindows()) {
      boolean match = window.getId() == windowId;
      AccessibilityNodeInfo root = match ? window.getRoot() : null;
      window.recycle();
      if (root != null) {
        return AccessibilityNodeInfoCompat.wrap(root);
      }
    }
    return null;
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
   * nearness order, as the no-effect retry's fallbacks. When no candidate is page-level the
   * order is plain nearness — the choice the nearest-ancestor walk always made.
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
