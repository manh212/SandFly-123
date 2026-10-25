/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.collapse;

import static com.google.android.accessibility.talkback.Feedback.Focus.Action.CLICK_NODE;
import static com.google.android.accessibility.talkback.Feedback.Focus.Action.LONG_CLICK_NODE;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Feedback.Speech;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.collapse.CollapsedItem.ChildAction;
import com.google.android.accessibility.talkback.contextmenu.AbstractOnContextMenuItemClickListener;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.menurules.CustomActionMenu;
import com.google.android.accessibility.talkback.menurules.NodeMenu;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Generates the synthesized actions of a collapsed item for the Actions reading control. The
 * items are rebuilt from the currently focused node on every request, so a click always lands
 * on a fresh child node; each item id also carries a fingerprint of the child's bounds relative
 * to the item, so an item whose child changed shape since it was heard no longer matches and
 * the activation reports "not supported" instead of clicking the wrong child.
 *
 * <p>These items must only ever surface in the reading control: every other surface (context
 * menu, custom-action surfaces) filters them out via {@link #isCollapsedActionItem}.
 */
public class CollapsedItemActions implements NodeMenu {
  private static final String TAG = "CollapsedItemActions";
  static final int ACTION_ID_BASE = 0x5d000000;
  private static final int ACTION_ID_RANGE = 0x100000;
  /** How long the delayed control dispatch waits out the user's own gesture cascade. */
  private static final int DELAYED_DISPATCH_DELAY_MS = 400;
  /** How long the focus-first route waits for the live button's own focus event. */
  private static final int FOCUS_EVENT_WAIT_MS = 800;
  /** The gap between the two strokes of a simulated press; mirrors FocusActor's. */
  private static final int STROKE_TIME_GAP_MS = 40;

  private final Pipeline.FeedbackReturner pipeline;
  private final TalkBackAnalytics analytics;

  public CollapsedItemActions(Pipeline.FeedbackReturner pipeline, TalkBackAnalytics analytics) {
    this.pipeline = pipeline;
    this.analytics = analytics;
  }

  public static boolean isCollapsedActionItem(ContextMenuItem item) {
    int itemId = item.getItemId();
    return itemId >= ACTION_ID_BASE && itemId < ACTION_ID_BASE + ACTION_ID_RANGE;
  }

  /**
   * Observes the service's event stream on behalf of an armed focus wait, forwarding
   * each accessibility-focus event's source node to it. Observe-only: the event is never
   * consumed here, so the focus announcement is spoken exactly as usual. Called from
   * {@code TalkBackService.onAccessibilityEvent}, next to the other observe-only hooks.
   */
  public static void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      return;
    }
    AccessibilityNodeInfo source = event.getSource();
    focusEventSource.deliver(
        source == null ? null : AccessibilityNodeInfoCompat.wrap(source));
  }

  @Override
  public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
    CollapsedItem item = CollapseEngine.matchRepresentative(context, node);
    return item != null && !item.actions().isEmpty();
  }

  @Override
  public List<ContextMenuItem> getMenuItemsForNode(
      Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
    List<ContextMenuItem> menuItems = new ArrayList<>();
    CollapsedItem item = CollapseEngine.matchRepresentative(context, node);
    if (item == null) {
      return menuItems;
    }
    List<ChildAction> actions = item.actions();
    for (int i = 0; i < actions.size(); i++) {
      ChildAction action = actions.get(i);
      CharSequence title =
          action.labelResId != 0
              ? context.getString(action.labelResId)
              : action.longClick
                  ? context.getString(R.string.title_collapse_action_long_press, action.label)
                  : action.label;
      ContextMenuItem menuItem =
          ContextMenu.createMenuItem(
              context,
              CustomActionMenu.CUSTOM_ACTION_GROUP_ID,
              ACTION_ID_BASE + (i << 10) + boundsHash(action.node, item.itemRoot()),
              Menu.NONE,
              title);
      menuItem.setOnMenuItemClickListener(
          action.kind == ChildAction.Kind.MENU_ITEM
              ? new MenuItemClickListener(context, item, action, title, pipeline, analytics)
              : new CollapseActionClickListener(
                  context,
                  action.node,
                  title,
                  action.longClick,
                  item.pattern() == CollapseScript.Pattern.COMPOSITE_ROOT,
                  item.composedLabel(),
                  action.label,
                  pipeline,
                  analytics));
      menuItem.setCheckable(false);
      menuItems.add(menuItem);
    }
    return menuItems;
  }

  private static int boundsHash(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat itemRoot) {
    Rect nodeBounds = new Rect();
    node.getBoundsInScreen(nodeBounds);
    Rect rootBounds = new Rect();
    itemRoot.getBoundsInScreen(rootBounds);
    int relativeLeft = (nodeBounds.left - rootBounds.left) / 8;
    int relativeTop = (nodeBounds.top - rootBounds.top) / 8;
    return (relativeLeft * 31 + relativeTop) & 0x3FF;
  }

  private static class CollapseActionClickListener
      extends AbstractOnContextMenuItemClickListener {
    /** The one armed focus wait, if any; a new activation supersedes the previous one. */
    private static @Nullable FocusWaitSession pendingFocusWait;

    private final Context context;
    private final CharSequence label;
    private final boolean longClick;
    private final boolean compositeRoot;
    private final @Nullable CharSequence itemComposedLabel;
    private final CharSequence actionLabel;

    CollapseActionClickListener(
        Context context,
        AccessibilityNodeInfoCompat node,
        CharSequence label,
        boolean longClick,
        boolean compositeRoot,
        @Nullable CharSequence itemComposedLabel,
        CharSequence actionLabel,
        Pipeline.FeedbackReturner pipeline,
        TalkBackAnalytics analytics) {
      super(node, pipeline, analytics);
      this.context = context;
      this.label = label;
      this.longClick = longClick;
      this.compositeRoot = compositeRoot;
      this.itemComposedLabel = itemComposedLabel;
      this.actionLabel = actionLabel;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
      try {
        return perform();
      } finally {
        clear();
      }
    }

    /**
     * Performs the press the way activating the child itself would be performed. The direct
     * node action only reaches children whose nodes expose the action; some apps (Facebook's
     * Litho buttons) mark a child clickable without exposing ACTION_CLICK, and the framework
     * then refuses the action outright -- the failure the focus actor's own click path defends
     * against. So an unexposed or refused action falls back first to that defended
     * activation, which redirects through app scripts and simulates a tap at the child's
     * centre when the node exposes no action, and finally to a simulated press at the
     * child's centre, so the press lands on the same child either way and "not supported"
     * is only reported when every route failed.
     *
     * <p>Composite items try one route before all of those: the focus-first route on the
     * live button itself, re-located in the live tree at activation time (see {@link
     * #performFocusFirst}). The stored target of a composite item is only the frame
     * around the real button, so the routes below never reach the button: their direct
     * action runs on the frame, and a coordinate tap on the frame produces no click inside
     * the app at all. On device the button refuses a direct action outright, and the
     * defended activation aimed at it produces no click while it does not hold
     * accessibility focus. The route therefore moves accessibility focus onto the button
     * and issues the defended activation at it in that state -- but the focus move is
     * asynchronous on device: the button's own focus event arrives tens of milliseconds
     * after the focus action is accepted, so a synchronous activation still fires at a
     * not-yet-focused target. The route is a {@link FocusWaitSession}: this perform
     * relocates the button, fires the focus action, arms a one-shot wait for the button's
     * own focus event and returns at once; the session issues the activation only once
     * that event has arrived and a fresh fetch confirms the button holds focus, then
     * restores focus to the item. When the wait times out or focus lands elsewhere, the
     * session runs the delayed-dispatch control and the stored-target routes below from
     * its continuation, and reports total failure through the same "not supported"
     * announcement the synchronous path produces. Any relocation doubt falls through to
     * the stored-target routes below, unchanged.
     *
     * <p>The target is refreshed first: the stored node is a snapshot from when the items
     * were built and the app may have re-created the child since, leaving the snapshot with
     * empty bounds. A simulated tap reads the target's centre, so while the bounds stay
     * empty the defended activation is skipped rather than allowed to report a tap at
     * (0, 0) as success, and the failure surfaces as "not supported" instead of silence.
     */
    private boolean perform() {
      int actionId =
          longClick
              ? AccessibilityNodeInfoCompat.ACTION_LONG_CLICK
              : AccessibilityNodeInfoCompat.ACTION_CLICK;
      Rect boundsBefore = new Rect();
      node.getBoundsInScreen(boundsBefore);
      boolean refreshed = nodeRefresher.refresh(node);
      Rect boundsAfter = new Rect();
      node.getBoundsInScreen(boundsAfter);
      boolean supportsClick =
          PerformActionUtils.isNodeSupportAction(node, AccessibilityNodeInfoCompat.ACTION_CLICK);
      boolean supportsLongClick =
          PerformActionUtils.isNodeSupportAction(
              node, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
      LogUtils.v(
          TAG,
          "perform label=%s longClick=%b target class=%s viewId=%s clickable=%b focusable=%b"
              + " longClickable=%b supportsClick=%b supportsLongClick=%b boundsBefore=%s"
              + " boundsAfter=%s refreshed=%b",
          label,
          longClick,
          node.getClassName(),
          node.getViewIdResourceName(),
          node.isClickable(),
          node.isFocusable(),
          node.isLongClickable(),
          supportsClick,
          supportsLongClick,
          boundsBefore,
          boundsAfter,
          refreshed);
      AccessibilityNodeInfoCompat focusNode = focusNodeProvider.getFocusNode(context);
      boolean focusWaitArmed = false;
      try {
        logAccessibilityFocus(focusNode);
        DelayedControl delayedControl = new DelayedControl();
        focusWaitArmed =
            compositeRoot
                && performFocusFirst(
                    focusNode, delayedControl, actionId, boundsAfter, supportsClick,
                    supportsLongClick);
        if (focusWaitArmed) {
          // The armed focus wait owns the rest of the ladder: its continuation issues
          // the activation, runs these tiers on failure and announces total failure, so
          // this click is already handled and must not be reported as unsupported.
          LogUtils.v(TAG, "perform async: focus wait armed");
          return true;
        }
        boolean performed =
            runStoredTiers(actionId, boundsAfter, supportsClick, supportsLongClick);
        delayedControl.tiersSucceeded = performed;
        LogUtils.v(TAG, "perform result=%b", performed);
        return performed;
      } finally {
        // An armed session owns the focus node (it is the restore target) and recycles
        // it when the session finishes; every other path recycles it here as before.
        if (focusNode != null && !focusWaitArmed) {
          focusNode.recycle();
        }
      }
    }

    /**
     * The stored-target tiers, in ladder order: the direct node action when the target
     * exposes it, then the defended activation, then a simulated press at the target's
     * centre. Runs synchronously from {@link #perform}, or from a focus wait's
     * continuation when the wait failed, with the target state captured at perform time.
     */
    private boolean runStoredTiers(
        int actionId, Rect boundsAfter, boolean supportsClick, boolean supportsLongClick) {
      boolean performed = false;
      if (longClick ? supportsLongClick : supportsClick) {
        performed =
            pipeline.returnFeedback(
                EVENT_ID_UNTRACKED,
                Feedback.nodeAction(node, actionId).vibration(R.array.view_clicked_pattern));
        LogUtils.v(TAG, "tier1 nodeAction result=%b", performed);
      }
      if (!performed) {
        if (boundsAfter.isEmpty()) {
          LogUtils.v(TAG, "tier2 skipped: target bounds empty after refresh");
        } else {
          performed =
              pipeline.returnFeedback(
                  EVENT_ID_UNTRACKED,
                  Feedback.focus(longClick ? LONG_CLICK_NODE : CLICK_NODE).setTarget(node));
          LogUtils.v(
              TAG, "tier2 %s result=%b", longClick ? "LONG_CLICK_NODE" : "CLICK_NODE", performed);
        }
      }
      if (!performed) {
        performed = simulatedTapper.tap(context, node, longClick);
        LogUtils.v(TAG, "tier3 simulatedTap result=%b", performed);
      }
      return performed;
    }

    /**
     * Speaks the "not supported" announcement for a perform whose asynchronous rungs all
     * failed -- the same feedback the caller of a synchronous perform produces when the
     * click reports false, issued here because the async perform already returned.
     */
    private void announceActionNotSupported() {
      pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.Part.builder()
              .setSpeech(
                  Speech.builder()
                      .setAction(Speech.Action.SPEAK)
                      .setText(context.getString(R.string.action_not_supported))
                      .setHintSpeakOptions(
                          SpeechController.SpeakOptions.create()
                              .setFlags(FeedbackItem.FLAG_NO_HISTORY))
                      .setHint(context.getString(R.string.hint_select_action))
                      .build()));
    }

    private void logAccessibilityFocus(@Nullable AccessibilityNodeInfoCompat focus) {
      if (focus == null) {
        LogUtils.v(TAG, "focus node: none");
        return;
      }
      Rect focusBounds = new Rect();
      focus.getBoundsInScreen(focusBounds);
      LogUtils.v(
          TAG,
          "focus node class=%s bounds=%s sameAsTarget=%b",
          focus.getClassName(),
          focusBounds,
          focus.equals(node));
    }

    /**
     * The focus-first route for composite items. The stored target is only the frame
     * around the real button, so first the button itself is re-located in the live tree
     * (see {@link #relocateLiveButtonNode}); then a {@link FocusWaitSession} is armed on
     * it: the session fires the focus action and waits for the button's own focus event
     * before activating it, because on device that event lands tens of milliseconds
     * after the action while a synchronous activation still fires at the not-yet-focused
     * button and produces nothing. Returns true when the session was armed -- the ladder
     * then continues in the session, not here. When the button cannot be pinned down
     * with certainty this returns false at once and no wait is armed, so the
     * stored-target tiers run as before.
     */
    private boolean performFocusFirst(
        @Nullable AccessibilityNodeInfoCompat focusNode,
        DelayedControl delayedControl,
        int actionId,
        Rect boundsAfter,
        boolean supportsClick,
        boolean supportsLongClick) {
      AccessibilityNodeInfoCompat button = relocateLiveButtonNode(focusNode);
      if (button == null) {
        return false;
      }
      Rect bounds = new Rect();
      button.getBoundsInScreen(bounds);
      LogUtils.v(
          TAG,
          "liveButton class=%s bounds=%s supportsClick=%b supportsLongClick=%b",
          button.getClassName(),
          bounds,
          PerformActionUtils.isNodeSupportAction(
              button, AccessibilityNodeInfoCompat.ACTION_CLICK),
          PerformActionUtils.isNodeSupportAction(
              button, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK));
      FocusWaitSession session =
          new FocusWaitSession(
              this, button, focusNode, delayedControl, actionId, boundsAfter, supportsClick,
              supportsLongClick);
      session.arm();
      return true;
    }

    /**
     * Schedules the control dispatch for the dispatch-timing question: the same press
     * the defended activation simulates, but issued after the user's own gesture
     * cascade has fully settled instead of from inside it. The dispatch is only a
     * control -- it runs after this perform has already reported its result, so it never
     * claims the perform's success; its outcome is written to the log through the
     * gesture callback, told apart from the dispatch acceptance boolean.
     */
    private void scheduleDelayedDispatch(DelayedControl delayedControl) {
      LogUtils.v(TAG, "delayedDispatch scheduled delayMs=%d", DELAYED_DISPATCH_DELAY_MS);
      delayedDispatcher.postDelayed(
          () -> runDelayedDispatch(delayedControl), DELAYED_DISPATCH_DELAY_MS);
    }

    private void runDelayedDispatch(DelayedControl delayedControl) {
      if (delayedControl.tiersSucceeded) {
        LogUtils.v(TAG, "delayedDispatch skipped: a stored tier already performed");
        return;
      }
      AccessibilityNodeInfoCompat focus = focusNodeProvider.getFocusNode(context);
      try {
        AccessibilityNodeInfoCompat button = relocateLiveButtonNode(focus);
        if (button == null) {
          return;
        }
        GestureDescription gesture = buildTwoStrokeTap(button, longClick);
        if (gesture == null) {
          LogUtils.v(TAG, "delayedDispatch aborted: live button bounds empty");
          return;
        }
        boolean accepted =
            gestureDispatcher.dispatch(context, gesture, DELAYED_DISPATCH_CALLBACK);
        LogUtils.v(TAG, "delayedDispatch accepted=%b", accepted);
      } finally {
        if (focus != null) {
          focus.recycle();
        }
      }
    }

    /** The gesture callback of the control dispatch; kept apart from acceptance above. */
    private static final AccessibilityService.GestureResultCallback DELAYED_DISPATCH_CALLBACK =
        new AccessibilityService.GestureResultCallback() {
          @Override
          public void onCompleted(GestureDescription gestureDescription) {
            LogUtils.v(TAG, "delayedDispatch onCompleted");
          }

          @Override
          public void onCancelled(GestureDescription gestureDescription) {
            LogUtils.v(TAG, "delayedDispatch onCancelled");
          }
        };

    /**
     * Builds the canonical simulated press at a node's centre: two strokes of tap
     * timeout with the focus actor's gap between them -- for a long press, the second
     * stroke is held for the long-press timeout instead, the double-tap-and-hold shape.
     */
    private static @Nullable GestureDescription buildTwoStrokeTap(
        AccessibilityNodeInfoCompat node, boolean longClick) {
      Rect bounds = new Rect();
      node.getBoundsInScreen(bounds);
      if (bounds.isEmpty()) {
        return null;
      }
      Path path = new Path();
      path.moveTo(bounds.centerX(), bounds.centerY());
      int tapMs = ViewConfiguration.getTapTimeout();
      long secondDurationMs = longClick ? ViewConfiguration.getLongPressTimeout() : tapMs;
      return new GestureDescription.Builder()
          .addStroke(new GestureDescription.StrokeDescription(path, /* startTime= */ 0, tapMs))
          .addStroke(
              new GestureDescription.StrokeDescription(
                  path, tapMs + STROKE_TIME_GAP_MS, secondDurationMs))
          .build();
    }

    /** Carries the stored tiers' outcome to a control dispatch posted during perform. */
    private static final class DelayedControl {
      boolean tiersSucceeded;
    }

    /**
     * One armed focus-first attempt on a composite item's live button. Arming fires the
     * focus action at the button and subscribes to the next accessibility-focus event;
     * the activation is issued only when that event shows the button itself received
     * focus and a fresh fetch confirms it still holds focus, because the focus move
     * completes asynchronously on device. A wait that times out, or an event that shows
     * focus landed somewhere else, restores focus to the item and runs the ladder's
     * remaining rungs (delayed-dispatch control, then the stored-target tiers) from this
     * continuation; a focus event that arrives after the wait resolved is ignored, so
     * the press can never fire twice. At most one session is armed at a time -- arming a
     * new one supersedes the previous -- and all of it runs on the main thread: the
     * click, the service event stream and the posted timeout all arrive there.
     */
    private static final class FocusWaitSession {
      private final CollapseActionClickListener owner;
      private final AccessibilityNodeInfoCompat button;
      /** The button's identity as captured at relocation: class, text and description. */
      private final @Nullable CharSequence buttonClass;
      private final @Nullable CharSequence buttonText;
      private final @Nullable CharSequence buttonDescription;
      private final Rect buttonBounds = new Rect();
      /**
       * The item node handed over by perform, which does not recycle it once a session
       * is armed; kept as the restore target and recycled when the session finishes.
       */
      private final @Nullable AccessibilityNodeInfoCompat representative;
      private final DelayedControl delayedControl;
      private final int tierActionId;
      private final Rect tierBoundsAfter;
      private final boolean tierSupportsClick;
      private final boolean tierSupportsLongClick;
      private long focusActionTimeMs;
      private boolean resolved;

      FocusWaitSession(
          CollapseActionClickListener owner,
          AccessibilityNodeInfoCompat button,
          @Nullable AccessibilityNodeInfoCompat representativeNode,
          DelayedControl delayedControl,
          int tierActionId,
          Rect tierBoundsAfter,
          boolean tierSupportsClick,
          boolean tierSupportsLongClick) {
        this.owner = owner;
        this.button = button;
        this.buttonClass = button.getClassName();
        this.buttonText = button.getText();
        this.buttonDescription = button.getContentDescription();
        button.getBoundsInScreen(this.buttonBounds);
        this.representative = representativeNode;
        this.delayedControl = delayedControl;
        this.tierActionId = tierActionId;
        this.tierBoundsAfter = new Rect(tierBoundsAfter);
        this.tierSupportsClick = tierSupportsClick;
        this.tierSupportsLongClick = tierSupportsLongClick;
      }

      /** Subscribes to the focus event, fires the focus action and posts the timeout. */
      void arm() {
        FocusWaitSession previous = pendingFocusWait;
        if (previous != null && previous != this) {
          previous.supersede();
        }
        pendingFocusWait = this;
        // Subscribe before firing the action, so even an unusually fast focus event
        // cannot slip past the wait.
        focusEventSource.arm(this::onFocusEvent);
        focusActionTimeMs = SystemClock.uptimeMillis();
        boolean actionResult =
            nodeActionPerformer.performAction(
                button, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
        LogUtils.v(TAG, "focusFirst focusAction result=%b", actionResult);
        delayedDispatcher.postDelayed(this::onTimeout, FOCUS_EVENT_WAIT_MS);
      }

      /** A newer activation took over the wait; this attempt ends without acting. */
      private void supersede() {
        resolved = true;
        LogUtils.v(TAG, "focusFirst focusWait superseded by a newer activation");
        finish();
      }

      private void onFocusEvent(@Nullable AccessibilityNodeInfoCompat source) {
        if (resolved) {
          LogUtils.v(TAG, "focusFirst focusEvent ignored: wait already resolved");
          if (source != null) {
            source.recycle();
          }
          return;
        }
        long latencyMs = SystemClock.uptimeMillis() - focusActionTimeMs;
        boolean sourceMatched = matchesButton(source);
        if (source != null) {
          source.recycle();
        }
        LogUtils.v(
            TAG,
            "focusFirst focusEvent arrived=true latencyMs=%d sourceMatched=%b",
            latencyMs,
            sourceMatched);
        // The event is the trigger; a fresh fetch of the focus is the authority on
        // where focus actually landed.
        AccessibilityNodeInfoCompat current = focusNodeProvider.getFocusNode(owner.context);
        try {
          if (current != null && current.equals(button)) {
            LogUtils.v(TAG, "focusFirst focusedNodeVerified=true");
            resolveOnButtonFocused();
          } else if (current != null && current.equals(representative)) {
            // Focus never left the item: this event is not the button's arrival, so
            // the wait stays armed for the real one.
            LogUtils.v(TAG, "focusFirst focusEvent ignored: focus still on the item");
          } else {
            LogUtils.v(TAG, "focusFirst focusedNodeVerified=false");
            resolveFocusElsewhere();
          }
        } finally {
          if (current != null) {
            current.recycle();
          }
        }
      }

      /** Whether an event source is the button, by the identity captured at relocation. */
      private boolean matchesButton(@Nullable AccessibilityNodeInfoCompat source) {
        if (source == null) {
          return false;
        }
        if (source.equals(button)) {
          return true;
        }
        Rect sourceBounds = new Rect();
        source.getBoundsInScreen(sourceBounds);
        return TextUtils.equals(source.getClassName(), buttonClass)
            && TextUtils.equals(source.getText(), buttonText)
            && TextUtils.equals(source.getContentDescription(), buttonDescription)
            && sourceBounds.equals(buttonBounds);
      }

      private void onTimeout() {
        if (resolved) {
          LogUtils.v(TAG, "focusFirst focusEvent timeout ignored: wait already resolved");
          return;
        }
        LogUtils.v(TAG, "focusFirst focusEvent timeout=true afterMs=%d", FOCUS_EVENT_WAIT_MS);
        resolveFocusElsewhere();
      }

      /** The button holds focus: activate it in that state, then restore and finish. */
      private void resolveOnButtonFocused() {
        resolved = true;
        focusEventSource.disarm();
        boolean performed = false;
        try {
          performed =
              owner.pipeline.returnFeedback(
                  EVENT_ID_UNTRACKED,
                  Feedback.focus(owner.longClick ? LONG_CLICK_NODE : CLICK_NODE)
                      .setTarget(button));
          LogUtils.v(
              TAG,
              "focusFirst %s result=%b",
              owner.longClick ? "longClickNode" : "clickNode",
              performed);
        } finally {
          restoreFocus();
        }
        if (performed) {
          LogUtils.v(TAG, "perform result=true");
          finish();
        } else {
          runFallbacksAndFinish();
        }
      }

      /** The wait failed: focus is restored to the item and the ladder continues. */
      private void resolveFocusElsewhere() {
        resolved = true;
        focusEventSource.disarm();
        restoreFocus();
        runFallbacksAndFinish();
      }

      private void runFallbacksAndFinish() {
        owner.scheduleDelayedDispatch(delayedControl);
        boolean performed =
            owner.runStoredTiers(
                tierActionId, tierBoundsAfter, tierSupportsClick, tierSupportsLongClick);
        delayedControl.tiersSucceeded = performed;
        LogUtils.v(TAG, "perform result=%b", performed);
        if (!performed) {
          owner.announceActionNotSupported();
        }
        finish();
      }

      private void restoreFocus() {
        boolean restored =
            representative != null
                && nodeActionPerformer.performAction(
                    representative, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
        LogUtils.v(TAG, "focusFirst focusRestored=%b", restored);
      }

      private void finish() {
        if (pendingFocusWait == this) {
          pendingFocusWait = null;
        }
        if (representative != null) {
          representative.recycle();
        }
      }
    }

    /**
     * Finds the live button a composite action names, or null when it cannot be
     * identified with certainty. The anchor is the node holding accessibility focus --
     * the item the user is standing on -- re-matched through the engine, and the match
     * is accepted only when the live item's composed label still equals the composed
     * label of the item these actions were built from, so the press can never jump to a
     * neighbouring item. Inside the live item the action is pinned by its label and press
     * kind, and the button is the one clickable inside the live action target -- for
     * Facebook the live Litho button inside its frame -- cross-checked through the
     * script's own action label, so the press lands on the same child the stored action
     * names.
     */
    private @Nullable AccessibilityNodeInfoCompat relocateLiveButtonNode(
        @Nullable AccessibilityNodeInfoCompat focusNode) {
      if (focusNode == null) {
        LogUtils.v(TAG, "liveButton relocation failed: no accessibility focus node");
        return null;
      }
      CollapsedItem liveItem = CollapseEngine.matchRepresentative(context, focusNode);
      if (liveItem == null) {
        LogUtils.v(
            TAG, "liveButton relocation failed: focus is not a collapsed representative");
        return null;
      }
      if (!TextUtils.equals(liveItem.composedLabel(), itemComposedLabel)) {
        LogUtils.v(
            TAG, "liveButton relocation failed: live item label differs from the menu item");
        return null;
      }
      ChildAction liveAction = null;
      for (ChildAction candidate : liveItem.actions()) {
        if (candidate.kind == ChildAction.Kind.CLICK
            && candidate.longClick == longClick
            && TextUtils.equals(candidate.label, actionLabel)) {
          if (liveAction != null) {
            LogUtils.v(
                TAG, "liveButton relocation failed: action label matches two live actions");
            return null;
          }
          liveAction = candidate;
        }
      }
      if (liveAction == null) {
        LogUtils.v(TAG, "liveButton relocation failed: action not found in the live item");
        return null;
      }
      ItemScan scan = ItemScan.of(liveItem.itemRoot());
      if (scan == null) {
        LogUtils.v(TAG, "liveButton relocation failed: live item cannot be scanned");
        return null;
      }
      AccessibilityNodeInfoCompat button = null;
      for (AccessibilityNodeInfoCompat clickable : scan.clickables()) {
        if (!clickable.equals(liveAction.node)
            && !AccessibilityNodeInfoUtils.hasAncestor(clickable, liveAction.node)) {
          continue;
        }
        if (button != null) {
          LogUtils.v(TAG, "liveButton relocation failed: two clickables inside the live target");
          return null;
        }
        button = clickable;
      }
      if (button == null) {
        LogUtils.v(TAG, "liveButton relocation failed: no clickable inside the live target");
        return null;
      }
      CollapseScript script = CollapseScripts.forPackage(liveItem.itemRoot().getPackageName());
      if (script == null
          || !TextUtils.equals(
              script.actionLabel(button, ItemScan.spokenLabel(button)), actionLabel)) {
        LogUtils.v(TAG, "liveButton relocation failed: live button label differs from the action");
        return null;
      }
      Rect bounds = new Rect();
      button.getBoundsInScreen(bounds);
      if (bounds.isEmpty()) {
        LogUtils.v(TAG, "liveButton relocation failed: live button bounds empty");
        return null;
      }
      return button;
    }
  }

  /** Refreshes the target node at activation time; a seam so tests can stand in for it. */
  interface NodeRefresher {
    boolean refresh(AccessibilityNodeInfoCompat node);
  }

  private static NodeRefresher nodeRefresher = AccessibilityNodeInfoCompat::refresh;

  @VisibleForTesting
  static void setNodeRefresherForTest(NodeRefresher refresher) {
    nodeRefresher = refresher;
  }

  @VisibleForTesting
  static void resetNodeRefresherForTest() {
    nodeRefresher = AccessibilityNodeInfoCompat::refresh;
  }

  /** Simulates a press at a node's centre; a seam so tests can record the press. */
  interface SimulatedTapper {
    boolean tap(Context context, AccessibilityNodeInfoCompat node, boolean longClick);
  }

  private static SimulatedTapper simulatedTapper = CollapsedItemActions::dispatchSimulatedTap;

  @VisibleForTesting
  static void setSimulatedTapperForTest(SimulatedTapper tapper) {
    simulatedTapper = tapper;
  }

  @VisibleForTesting
  static void resetSimulatedTapperForTest() {
    simulatedTapper = CollapsedItemActions::dispatchSimulatedTap;
  }

  private static boolean dispatchSimulatedTap(
      Context context, AccessibilityNodeInfoCompat node, boolean longClick) {
    if (!(context instanceof AccessibilityService)) {
      return false;
    }
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (bounds.isEmpty()) {
      return false;
    }
    Path path = new Path();
    path.moveTo(bounds.centerX(), bounds.centerY());
    long durationMs =
        longClick ? ViewConfiguration.getLongPressTimeout() : ViewConfiguration.getTapTimeout();
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(
                new GestureDescription.StrokeDescription(path, /* startTime= */ 0, durationMs))
            .build();
    return ((AccessibilityService) context)
        .dispatchGesture(gesture, /* callback= */ null, /* handler= */ null);
  }

  /** Supplies the node holding accessibility focus; a seam so tests can stand in for it. */
  interface FocusNodeProvider {
    @Nullable AccessibilityNodeInfoCompat getFocusNode(Context context);
  }

  private static FocusNodeProvider focusNodeProvider = CollapsedItemActions::fetchFocusNode;

  @VisibleForTesting
  static void setFocusNodeProviderForTest(FocusNodeProvider provider) {
    focusNodeProvider = provider;
  }

  @VisibleForTesting
  static void resetFocusNodeProviderForTest() {
    focusNodeProvider = CollapsedItemActions::fetchFocusNode;
  }

  private static @Nullable AccessibilityNodeInfoCompat fetchFocusNode(Context context) {
    if (!(context instanceof AccessibilityService)) {
      return null;
    }
    try {
      return FocusFinder.getAccessibilityFocusNode((AccessibilityService) context, false);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Performs an accessibility action directly on a node; a seam so tests can stand in. */
  interface NodeActionPerformer {
    boolean performAction(AccessibilityNodeInfoCompat node, int actionId);
  }

  private static NodeActionPerformer nodeActionPerformer =
      (node, actionId) -> node.performAction(actionId);

  @VisibleForTesting
  static void setNodeActionPerformerForTest(NodeActionPerformer performer) {
    nodeActionPerformer = performer;
  }

  @VisibleForTesting
  static void resetNodeActionPerformerForTest() {
    nodeActionPerformer = (node, actionId) -> node.performAction(actionId);
  }

  /** Posts a runnable after a delay; a seam so tests can run the delay out by hand. */
  interface DelayedDispatcher {
    void postDelayed(Runnable runnable, long delayMs);
  }

  private static DelayedDispatcher delayedDispatcher =
      (runnable, delayMs) -> new Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs);

  @VisibleForTesting
  static void setDelayedDispatcherForTest(DelayedDispatcher dispatcher) {
    delayedDispatcher = dispatcher;
  }

  @VisibleForTesting
  static void resetDelayedDispatcherForTest() {
    delayedDispatcher =
        (runnable, delayMs) -> new Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs);
  }

  /** Receives the source node of an accessibility-focus event, or null when it has none. */
  interface FocusEventConsumer {
    void onFocusEvent(@Nullable AccessibilityNodeInfoCompat source);
  }

  /**
   * A one-shot subscription to the service's accessibility-focus events; a seam so
   * tests can stand in for the event stream. Arming replaces any armed consumer;
   * deliveries after {@link #disarm} never happen.
   */
  interface FocusEventSource {
    void arm(FocusEventConsumer consumer);

    void disarm();

    /** Delivers one focus event's source to the armed consumer, if any. */
    void deliver(@Nullable AccessibilityNodeInfoCompat source);
  }

  private static FocusEventSource focusEventSource = new ServiceFocusEventSource();

  @VisibleForTesting
  static void setFocusEventSourceForTest(FocusEventSource source) {
    focusEventSource = source;
  }

  @VisibleForTesting
  static void resetFocusEventSourceForTest() {
    focusEventSource = new ServiceFocusEventSource();
  }

  /** The production event source: a single armed consumer fed by {@link #onAccessibilityEvent}. */
  private static final class ServiceFocusEventSource implements FocusEventSource {
    private @Nullable FocusEventConsumer consumer;

    @Override
    public void arm(FocusEventConsumer newConsumer) {
      consumer = newConsumer;
    }

    @Override
    public void disarm() {
      consumer = null;
    }

    @Override
    public void deliver(@Nullable AccessibilityNodeInfoCompat source) {
      FocusEventConsumer current = consumer;
      if (current == null) {
        if (source != null) {
          source.recycle();
        }
        return;
      }
      current.onFocusEvent(source);
    }
  }

  /** Dispatches a gesture with its result callback; a seam so tests can stand in. */
  interface GestureDispatcher {
    boolean dispatch(
        Context context,
        GestureDescription gesture,
        AccessibilityService.GestureResultCallback callback);
  }

  private static GestureDispatcher gestureDispatcher =
      CollapsedItemActions::dispatchGestureWithCallback;

  @VisibleForTesting
  static void setGestureDispatcherForTest(GestureDispatcher dispatcher) {
    gestureDispatcher = dispatcher;
  }

  @VisibleForTesting
  static void resetGestureDispatcherForTest() {
    gestureDispatcher = CollapsedItemActions::dispatchGestureWithCallback;
  }

  private static boolean dispatchGestureWithCallback(
      Context context,
      GestureDescription gesture,
      AccessibilityService.GestureResultCallback callback) {
    if (!(context instanceof AccessibilityService)) {
      return false;
    }
    return ((AccessibilityService) context)
        .dispatchGesture(gesture, callback, /* handler= */ null);
  }

  /**
   * Activates one catalog item of the item's own overflow menu. The data captured here comes
   * from the item as rebuilt at activation time, so the menu button node is fresh and the
   * origin description (the representative's spoken label and the item's bounds) describes the
   * row the user is standing on right now.
   */
  private static class MenuItemClickListener extends AbstractOnContextMenuItemClickListener {
    private final Context context;
    private final String targetLabel;
    private final String[] targetVariants;
    private final String[] targetCores;
    private final String sheetItemLabelViewId;
    private final String originLabel;
    private final Rect originBounds;

    MenuItemClickListener(
        Context context,
        CollapsedItem item,
        ChildAction action,
        CharSequence title,
        Pipeline.FeedbackReturner pipeline,
        TalkBackAnalytics analytics) {
      super(action.node, pipeline, analytics);
      this.context = context;
      this.targetLabel = title.toString();
      this.targetVariants = action.menuStateVariants;
      this.targetCores = action.menuStateCores;
      this.sheetItemLabelViewId = action.sheetItemLabelViewId;
      CharSequence label = ItemScan.spokenLabel(item.representative());
      this.originLabel = label == null ? "" : label.toString();
      this.originBounds = new Rect();
      item.itemRoot().getBoundsInScreen(this.originBounds);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
      try {
        if (MenuAutomation.isPending()) {
          return true;
        }
        return MenuAutomation.begin(
            pipeline,
            context,
            node,
            targetLabel,
            targetVariants,
            targetCores,
            sheetItemLabelViewId,
            originLabel,
            originBounds);
      } finally {
        clear();
      }
    }
  }
}
