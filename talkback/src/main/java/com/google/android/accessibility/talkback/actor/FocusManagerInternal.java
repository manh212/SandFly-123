/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.google.android.accessibility.talkback.actor;

import static android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY;
import static com.google.android.accessibility.talkback.Feedback.Focus.Action.INITIAL_FOCUS_FIRST_CONTENT;
import static com.google.android.accessibility.talkback.Feedback.Focus.Action.INITIAL_FOCUS_FOLLOW_INPUT;
import static com.google.android.accessibility.talkback.Feedback.Focus.Action.INITIAL_FOCUS_RESTORE;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.ActorStateWritable;
import com.google.android.accessibility.talkback.CursorGranularityManager;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Feedback.Focus;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.actor.helper.FocusActorHelper;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForScreenStateChange;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory.WindowIdentifier;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionRecord;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.input.CursorGranularity;
import com.google.android.accessibility.utils.input.WindowEventInterpreter;
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Helps FocusActor execute focus actions. */
// TODO: Merge FocusActor with FocusManagerInternal.
public class FocusManagerInternal {

  private static final String TAG = "FocusManagerInternal";

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Member variables

  private final AccessibilityService service;
  private final FocusFinder focusFinder;
  private final ScreenStateMonitor.State screenState;

  @VisibleForTesting protected boolean muteNextFocus = false;

  /** Writable focus-history. */
  private final AccessibilityFocusActionHistory history;

  /** Actor-state passed in from pipeline, which encapsulates {@code history}. */
  private ActorStateWritable actorState;

  private final AccessibilityFocusMonitor accessibilityFocusMonitor;

  /** Posts the focus-history record off the swipe path; see the focus action method below. */
  private final Handler historyHandler = new Handler(Looper.getMainLooper());

  private Pipeline.FeedbackReturner pipeline;

  // To avoid the endless repeat of Ensure A11y focus-on-screen, the [sourceAction] is recorded and
  // validate before doing so. In case the focused node disappears by user's operation but framework
  // does not report the a11y focus clear, the ensure focus procedure will escape unexpectedly. Here
  // the new flag to determine the validity of source action; suppose when user touch the screen,
  // the ensure focus would need perform again.
  private boolean skipRefocus = false;

  // Whether the last requestA11yFocusFromLastFocusRecord() call was stopped by skipRefocus
  // rather than by failing to find a node. The failovers in ensureAccessibilityFocusOnScreen must
  // not run in that case: they record a different source action, which re-arms the request and
  // repeats the focus announcement after every subtree change.
  private boolean ensureRequestBlockedBySkipRefocus = false;

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Construction methods

  public FocusManagerInternal(
      AccessibilityService service,
      FocusFinder focusFinder,
      ScreenStateMonitor.State screenState,
      AccessibilityFocusActionHistory history,
      AccessibilityFocusMonitor accessibilityFocusMonitor) {
    this.service = service;
    this.focusFinder = focusFinder;
    this.screenState = screenState;
    this.history = history;
    this.accessibilityFocusMonitor = accessibilityFocusMonitor;
  }

  public void setActorState(ActorStateWritable actorState) {
    this.actorState = actorState;
  }

  public void setPipeline(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  /** Told when accessibility focus has been set, before the app's focus event for it arrives. */
  public interface FocusSetListener {
    /**
     * @param actionTime when the focus action started, in {@link SystemClock#uptimeMillis()} time,
     *     before the app could send its focus event for it
     */
    void onAccessibilityFocusSet(
        AccessibilityNodeInfoCompat node,
        FocusActionInfo info,
        @Nullable EventId eventId,
        long actionTime);
  }

  private @Nullable FocusSetListener focusSetListener;

  public void setFocusSetListener(@Nullable FocusSetListener listener) {
    focusSetListener = listener;
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Methods

  public void setMuteNextFocus() {
    muteNextFocus = true;
  }

  public void renewEnsureFocus() {
    skipRefocus = false;
  }

  /**
   * Tries to set accessibility focus on the given node. It's used by {@link FocusActor} to set
   * accessibility focus.
   *
   * <p>This method attempts to focus the node only when the node is not accessibility focus or when
   * {@code forceRefocusIfAlreadyFocused} is {@code true}.
   *
   * @param node Node to be focused.
   * @param forceRefocusIfAlreadyFocused Whether we should perform ACTION_ACCESSIBILITY_FOCUS if the
   *     node is already accessibility focused.
   * @param eventId The EventId for performance tracking.
   * @return Whether the node is already accessibility focused or we successfully put accessibility
   *     focus on the node.
   */
  boolean setAccessibilityFocus(
      @NonNull AccessibilityNodeInfoCompat node,
      boolean forceRefocusIfAlreadyFocused,
      @NonNull FocusActionInfo focusActionInfo,
      @Nullable EventId eventId) {
    if (isAccessibilityFocused(node)) {
      // The isAccessibilityFocused() method for a node inside a WebView sometimes does not work
      // correctly, so always clearing a11y focus then performing an a11y focused action on the node
      // to ensure the Talkback focus indicator can move to the expected node.
      if (forceRefocusIfAlreadyFocused || WebInterfaceUtils.isWebContainer(node)) {
        pipeline.returnFeedback(
            eventId,
            Feedback.nodeAction(
                node, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS));
      } else {
        return true;
      }
    }

    // Setting input focus first to decrease lag between a11y focus moving and input focus moving.
    boolean shouldSyncInputFocus =
        FocusActorHelper.shouldSyncInputFocusToAccessibilityFocus(service, node, focusActionInfo);
    if (shouldSyncInputFocus) {
      setFocus(eventId, node);
    }

    boolean isAccessibilityFocusActionPerformedSuccessfully =
        performAccessibilityFocusActionInternal(node, focusActionInfo, eventId);
    if (isAccessibilityFocusActionPerformedSuccessfully
        && !shouldSyncInputFocus
        && FocusActorHelper.shouldClearFocusAfterSetAccessibilityFocus(service, focusActionInfo)) {
      clearFocus(eventId, /* includeEditable= */ false);
    }
    return isAccessibilityFocusActionPerformedSuccessfully;
  }

  void updateFocusHistory(
      @NonNull AccessibilityNodeInfoCompat pivot, @NonNull FocusActionInfo focusActionInfo) {
    // Cache the accessibility focus action history.
    long currentTime = SystemClock.uptimeMillis();
    AccessibilityNodeInfoCompat newFocus = focusFinder.findFocusCompat(FOCUS_ACCESSIBILITY);

    LogUtils.d(
        TAG,
        "Navigate in web:result=%s\nNode:%s\nFocusActionInfo:%s",
        newFocus,
        pivot,
        focusActionInfo);

    FocusActionInfo updatedFocusActionInfo =
        updateFocusActionInfoIfNecessary(focusActionInfo, newFocus);

    if (newFocus == null || pivot.equals(newFocus)) {
      // The focus should have been changed, otherwise we have to wait for the next
      // TYPE_VIEW_ACCESSIBILITY_FOCUSED event to get the correct focused node.
      // Usually this logic will not be invoked. A known case for this is navigating in Firefox.
      history.onPendingAccessibilityFocusActionOnWebElement(
          updatedFocusActionInfo, currentTime, screenState.getStableScreenState());
    } else {
      history.onAccessibilityFocusAction(
          newFocus, updatedFocusActionInfo, currentTime, screenState.getStableScreenState());
    }
  }

  /**
   * Checks the accessibility focus on the current screen. If no focused is found, requests the
   * initial focus by the following order:
   *
   * <ol>
   *   <li>Restore focus from the last focus record
   *   <li>Restore focus on the active window
   *   <li>Input focus on editable view (or any view on TV).
   *   <li>The first non-title content on the active window.
   * </ol>
   */
  boolean ensureAccessibilityFocusOnScreen(EventId eventId) {
    final String subTag = "EnsureOnScreen";
    if (pipeline == null
        || !screenState.areMainWindowsStable()
        || screenState.getStableScreenState() == null) {
      LogUtils.d(TAG, String.format("%s: Return, windows are not stable yet.", subTag));
      return false;
    }

    ScreenState state = screenState.getStableScreenState();
    long windowEventInterpreterDelayTimeMs =
        SystemClock.uptimeMillis() - state.getScreenTransitionStartTime();
    long focusDelayMs = WindowEventInterpreter.getWindowChangeDelayMs();
    long stableDelayMs =
        WindowEventInterpreter.getStableTransitionDelayMs(state.getScreenTransitionStartTime());
    if (stableDelayMs >= 0) {
      // The interpreter already assumed this transition's windows stable, so the wait ends
      // with the window announcement instead of the full delay.
      focusDelayMs = Math.min(focusDelayMs, stableDelayMs);
    }
    if (windowEventInterpreterDelayTimeMs <= focusDelayMs) {
      LogUtils.d(TAG, String.format("%s: Return, the initial focus is not stable yet.", subTag));
      return false;
    }

    if (focusFinder.findFocusCompat(FOCUS_ACCESSIBILITY) != null) {
      // Has focus on screen.
      LogUtils.d(TAG, String.format("%s: Return, accessibility focus is already there.", subTag));
      return true;
    }

    if (isLastFocusInImeWindowSinceScreenChange(state)) {
      // The keyboard redraws itself while typing, which can drop accessibility focus from the
      // key without any window change. Placing focus back here would re-announce the key after
      // every burst of subtree changes, so leave it to the next touch or swipe instead.
      LogUtils.d(
          TAG, String.format("%s: Return, last focus is in the input method window.", subTag));
      return false;
    }

    if (!FocusProcessorForScreenStateChange.focusesNewWindows()) {
      // Only replace focus that was lost on this screen, such as when the focused item is removed.
      return focusedSinceScreenChange(state) && requestA11yFocusFromLastFocusRecord(eventId);
    }

    // On TV, we only try to follow input focus.
    if (FormFactorUtils.isAndroidTv()) {
      return pipeline.returnFeedback(
          Feedback.create(eventId, toFeedbackPart(INITIAL_FOCUS_FOLLOW_INPUT, state)));
    }

    // TODO: Consider to use the information of subtree change event to find the
    // initial focus.

    // Try to generate the focus on the same window with the last focused node. It avoids the focus
    // jumping to another window after a node tree changed. (Especially on IME windows.)
    if (requestA11yFocusFromLastFocusRecord(eventId)) {
      return true;
    }

    if (ensureRequestBlockedBySkipRefocus) {
      // Ensure-focus already placed the last focus and nothing has touched the screen since.
      // Running the failovers would place it again under a different source action, which
      // re-arms the request above and repeats the announcement on the next subtree change.
      return false;
    }

    ArrayList<Feedback.Part> feedbackFailovers = new ArrayList<>();
    // If couldn't get the last focus record, find the initial focus in the same way with handling
    // window changes.
    feedbackFailovers.add(
        toFeedbackPart(INITIAL_FOCUS_RESTORE, screenState.getStableScreenState()));
    feedbackFailovers.add(
        toFeedbackPart(INITIAL_FOCUS_FOLLOW_INPUT, screenState.getStableScreenState()));
    feedbackFailovers.add(
        toFeedbackPart(INITIAL_FOCUS_FIRST_CONTENT, screenState.getStableScreenState()));

    return pipeline.returnFeedback(Feedback.create(eventId, feedbackFailovers));
  }

  /** Returns whether focus was last placed after the windows changed. */
  private boolean focusedSinceScreenChange(ScreenState state) {
    @Nullable FocusActionRecord record = history.getLastFocusActionRecord();
    return record != null && record.getActionTime() > state.getScreenTransitionStartTime();
  }

  /**
   * Returns whether the last accessibility focus was placed in the input method window after
   * the current screen state began. The windows have not changed since then, so losing that
   * focus is churn within the same screen, such as the keyboard redrawing while typing, and not
   * a move to a new window.
   */
  private boolean isLastFocusInImeWindowSinceScreenChange(ScreenState state) {
    if (!focusedSinceScreenChange(state)) {
      return false;
    }
    @Nullable FocusActionRecord record = history.getLastFocusActionRecord();
    AccessibilityNodeInfoCompat lastFocus = (record == null) ? null : record.getFocusedNode();
    return lastFocus != null
        && AccessibilityWindowInfoUtils.isImeWindow(
            AccessibilityNodeInfoUtils.getWindow(lastFocus));
  }

  /**
   * Finds a focusable node from the last {@link FocusActionRecord} for accessibility focus. At
   * first it will try to restore the accessibility focus from the record. If restoring fail, then
   * try to report the input focus node. Finally, reports the node with {@link
   * AccessibilityNodeInfoCompat#hasRequestInitialAccessibilityFocus()} or the first non-title node
   * under the same root.
   *
   * @return true if accessibility focus is successfully found and assigned
   */
  private boolean requestA11yFocusFromLastFocusRecord(EventId eventId) {
    ensureRequestBlockedBySkipRefocus = false;
    @Nullable FocusActionRecord record = history.getLastFocusActionRecord();
    if (record == null) {
      return false;
    }

    AccessibilityNodeInfoCompat lastFocus = record.getFocusedNode();
    if (lastFocus == null) {
      return false;
    }

    final String subTag = "EnsureOnScreen";
    if (skipRefocus && record.getExtraInfo().isSourceEnsureOnScreen()) {
      ensureRequestBlockedBySkipRefocus = true;
      // If the last focus record is also from the source ENSURE_ON_SCREEN, doesn't request to
      // ensure the focus repeatedly.
      LogUtils.w(
          TAG,
          String.format(
              "%s: App UI (%s) is not stable so stop searching focus on it.",
              subTag, lastFocus.getPackageName()));
      return false;
    }
    skipRefocus = true;

    AccessibilityNodeInfoCompat root = AccessibilityNodeInfoUtils.getRoot(lastFocus);
    if (root == null || !root.refresh()) {
      return false;
    }

    // Try to restore focus by the focus record.
    ScreenState stableScreenState = screenState.getStableScreenState();
    AtomicInteger type = new AtomicInteger(FocusActionInfo.UNDEFINED);
    AccessibilityNodeInfoCompat nodeToFocus =
        findInitialFocusFromTargetWindowRoot(
            service, root, stableScreenState, actorState.focusHistory, focusFinder, type);
    if (nodeToFocus == null) {
      return false;
    }

    boolean firstTime =
        stableScreenState != null && stableScreenState.isInterpretFirstTimeWhenWakeUp();
    // Cell broadcast is an emergent announcement, so we don't mute it.
    boolean forceMuteFeedback =
        FocusActorHelper.shouldMuteFeedbackForFocusedNode(nodeToFocus, stableScreenState);

    FocusActionInfo focusActionInfo =
        FocusActionInfo.builder()
            .setForceMuteFeedback(forceMuteFeedback)
            .setSourceAction(FocusActionInfo.ENSURE_ON_SCREEN)
            .setInitialFocusType(type.get())
            .build();

    boolean success =
        pipeline.returnFeedback(
            eventId,
            Focus.builder()
                .setAction(Focus.Action.FOCUS)
                .setFocusActionInfo(focusActionInfo)
                .setTarget(nodeToFocus));

    if (firstTime && success) {
      stableScreenState.consumeInterpretFirstTimeWhenWakeUp();
    }
    return success;
  }

  /**
   * Finds the initial focus from the specified window root for accessibility focus. At first it
   * will try to restore the accessibility focus from the record. If restoring fail, then try to
   * report the input focus node. Finally, reports the node with {@link
   * AccessibilityNodeInfoCompat#hasRequestInitialAccessibilityFocus()} or the first focusable node
   * under the same root. It also updates {@code initialFocusType} with the type.
   *
   * @return the node to focus
   */
  public static @Nullable AccessibilityNodeInfoCompat findInitialFocusFromTargetWindowRoot(
      AccessibilityService service,
      AccessibilityNodeInfoCompat windowRoot,
      ScreenState screenState,
      AccessibilityFocusActionHistory.Reader history,
      FocusFinder focusFinder,
      AtomicInteger initialFocusType) {
    AccessibilityNodeInfoCompat nodeToFocus = null;
    if (service == null || windowRoot == null || focusFinder == null || initialFocusType == null) {
      return null;
    }
    if (history != null) {
      final WindowIdentifier windowIdentifier =
          WindowIdentifier.create(windowRoot.getWindowId(), screenState);
      // The previous focus record may come from a different pane in the same window, it needs to
      // get the right record from the WindowIdentifier to restore.
      FocusActionRecord windowIdentifierRecord =
          history.getLastFocusActionRecordInWindow(windowIdentifier);
      if (windowIdentifierRecord != null) {
        initialFocusType.set(FocusActionInfo.RESTORED_LAST_FOCUS);
        nodeToFocus =
            FocusActionRecord.getFocusableNodeFromFocusRecord(
                windowRoot, focusFinder, windowIdentifierRecord);
      }
    }

    // If couldn't restore the focus from the record directly, then try to find focus node
    // on the same root. The priority: input focus > requested initial focus > the first non-title
    // node.
    if (nodeToFocus == null) {
      Filter<AccessibilityNodeInfoCompat> nodeFilter =
          Filter.node((node) -> AccessibilityNodeInfoUtils.shouldFocusNode(node));
      if (FormFactorUtils.isAndroidWear()) {
        nodeFilter =
            AccessibilityNodeInfoUtils.getFilterExcludingSmallTopAndBottomBorderNode(service)
                .and(nodeFilter);
      }

      AccessibilityNodeInfoCompat inputFocusNode =
          focusFinder.findFocusCompat(AccessibilityNodeInfo.FOCUS_INPUT);
      if (AccessibilityNodeInfoUtils.hasAncestor(inputFocusNode, windowRoot)
          && nodeFilter.accept(inputFocusNode)) {
        nodeToFocus = inputFocusNode;
        initialFocusType.set(FocusActionInfo.SYNCED_INPUT_FOCUS);
      } else {
        OrderedTraversalStrategy strategy = new OrderedTraversalStrategy(windowRoot);
        nodeToFocus = strategy.focusInitial(windowRoot);
        initialFocusType.set(FocusActionInfo.REQUESTED_INITIAL_NODE);

        if (nodeToFocus == null || !nodeFilter.accept(nodeToFocus)) {
          nodeToFocus =
              TraversalStrategyUtils.findFirstFocusInNodeTree(
                  strategy, windowRoot, TraversalStrategy.SEARCH_FOCUS_FORWARD, nodeFilter);
          initialFocusType.set(FocusActionInfo.FIRST_FOCUSABLE_NODE);
        }
      }
    }
    return nodeToFocus;
  }

  /**
   * Creates {@link Feedback.Part} for focus actions.
   *
   * @param action The focus action
   * @param state Current screen state, it can't be null
   * @return Feedback.Part for the pipeline
   */
  private Feedback.Part toFeedbackPart(Focus.Action action, ScreenState state) {
    return Feedback.part().setFocus(Feedback.focus(action).setScreenState(state).build()).build();
  }

  /**
   * Returns whether the node is accessibility focused.
   *
   * <p><strong>Note:</strong> {@link #setAccessibilityFocus(AccessibilityNodeInfoCompat, boolean,
   * FocusActionInfo, EventId)} should use this method instead of directly invoking {@link
   * AccessibilityNodeInfoCompat#isAccessibilityFocused()}. This is in case that if we want to
   * bypass framework's touch exploration and maintain our own accessibility focus, we can easily
   * override this method.
   */
  private boolean isAccessibilityFocused(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && node.isAccessibilityFocused();
  }

  /**
   * Performs {@link AccessibilityNodeInfoCompat#ACTION_ACCESSIBILITY_FOCUS} on the node. Saves the
   * record if the action is successfully performed.
   *
   * @return {@code true} if the action is successfully performed.
   */
  @VisibleForTesting
  protected boolean performAccessibilityFocusActionInternal(
      @NonNull AccessibilityNodeInfoCompat node,
      @NonNull FocusActionInfo focusActionInfo,
      @Nullable EventId eventId) {
    long currentTime = SystemClock.uptimeMillis();
    boolean result =
        pipeline.returnFeedback(
            eventId,
            Feedback.nodeAction(node, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS));
    if (result) {
      focusActionInfo = updateFocusActionInfoIfNecessary(focusActionInfo, node);
      // Told first, so that speech can start before the history walks the node's ancestors. The
      // history only has to be ready by the time the app's focus event arrives.
      if (focusSetListener != null) {
        focusSetListener.onAccessibilityFocusSet(node, focusActionInfo, eventId, currentTime);
      }
      // Building the record walks the node's ancestors, an IPC per level, so it is posted
      // instead of built inside the swipe. The post is queued ahead of the app's focus event,
      // which still has to come back through the event queue, so the record is in place before
      // any interpreter matches that event against the history. The history keeps the node it
      // is given, so it is given an obtain() copy of its own.
      AccessibilityNodeInfoCompat nodeForRecord = AccessibilityNodeInfoCompat.obtain(node);
      FocusActionInfo infoForRecord = focusActionInfo;
      ScreenState stableScreenState = screenState.getStableScreenState();
      historyHandler.post(
          () ->
              history.onAccessibilityFocusAction(
                  nodeForRecord, infoForRecord, currentTime, stableScreenState));
    }
    LogUtils.d(
        TAG,
        "Set accessibility focus:result=%s\nNode:%s\nFocusActionInfo:%s",
        result,
        node,
        focusActionInfo);
    return result;
  }

  private FocusActionInfo updateFocusActionInfoIfNecessary(
      @NonNull FocusActionInfo focusActionInfo, @Nullable AccessibilityNodeInfoCompat node) {
    if (shouldMuteFeedbackForMicroGranularityNavigation(focusActionInfo, node)) {
      LogUtils.d(TAG, "Mute node feedback for micro granularity navigation.");
      focusActionInfo =
          new FocusActionInfo.Builder(focusActionInfo).setForceMuteFeedback(true).build();
    }
    if (muteNextFocus) {
      // Reset mute option regardless of sourceAction. Sometimes it doesn't mute because the
      // source action does not come from SCREEN_STATE_CHANGE, so mute focus from next screen
      // state change.
      muteNextFocus = false;

      LogUtils.d(TAG, "FocusActionInfo modified.");
      focusActionInfo =
          new FocusActionInfo.Builder(focusActionInfo).setForceMuteFeedback(true).build();
    }

    return focusActionInfo;
  }

  /**
   * Checks whether we should mute node feedback for micro granularity navigation.
   *
   * <p>When navigating with micro granularity(character, word, line, etc) across nodes, we don't
   * announce the entire node description from accessibility focus event. There is an exception: If
   * the next node doesn't support target granularity.
   */
  private boolean shouldMuteFeedbackForMicroGranularityNavigation(
      @NonNull FocusActionInfo info, @Nullable AccessibilityNodeInfoCompat node) {
    if (info.navigationAction == null) {
      return false;
    }
    CursorGranularity originalNavigationGranularity =
        info.navigationAction.originalNavigationGranularity;

    if ((originalNavigationGranularity == null)
        || !originalNavigationGranularity.isMicroGranularity()) {
      return false;
    }

    return CursorGranularityManager.getSupportedGranularities(node)
        .contains(originalNavigationGranularity);
  }

  /**
   * Clears accessibility focus on the given node.
   *
   * @return {@code true} if successfully perform {@link
   *     AccessibilityNodeInfo#ACTION_CLEAR_ACCESSIBILITY_FOCUS} on the given node.
   */
  boolean clearAccessibilityFocus(AccessibilityNodeInfoCompat currentNode, EventId eventId) {
    return pipeline.returnFeedback(
        eventId,
        Feedback.nodeAction(currentNode, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS));
  }

  void clearAccessibilityFocus(EventId eventId) {
    AccessibilityNodeInfoCompat currentFocus =
        accessibilityFocusMonitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ false);
    if (currentFocus != null) {
      clearAccessibilityFocus(currentFocus, eventId);
    }
  }

  void clearFocus(EventId eventId, boolean includeEditable) {
    AccessibilityNodeInfoCompat currentFocus = accessibilityFocusMonitor.getInputFocus();
    if (currentFocus != null && (includeEditable || !currentFocus.isEditable())) {
      pipeline.returnFeedback(
          eventId, Feedback.nodeAction(currentFocus, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS));
    }
  }

  void setFocus(@NonNull EventId eventId, @NonNull AccessibilityNodeInfoCompat node) {
    long currentTime = SystemClock.uptimeMillis();
    boolean result =
        pipeline.returnFeedback(
            eventId, Feedback.nodeAction(node, AccessibilityNodeInfo.ACTION_FOCUS));
    if (result) {
      actorState.setInputFocus(node, currentTime);
    }
    LogUtils.d(
        TAG,
        "Perform input focus action:result=%s\n" + " eventId=%s," + " Node=%s",
        result,
        eventId,
        node);
  }
}
