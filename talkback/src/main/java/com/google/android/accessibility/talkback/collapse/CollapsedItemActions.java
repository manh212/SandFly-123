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
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewConfiguration;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.collapse.CollapsedItem.ChildAction;
import com.google.android.accessibility.talkback.contextmenu.AbstractOnContextMenuItemClickListener;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.menurules.CustomActionMenu;
import com.google.android.accessibility.talkback.menurules.NodeMenu;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.List;

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
                  context, action.node, title, action.longClick, pipeline, analytics));
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
    private final Context context;
    private final CharSequence label;
    private final boolean longClick;

    CollapseActionClickListener(
        Context context,
        AccessibilityNodeInfoCompat node,
        CharSequence label,
        boolean longClick,
        Pipeline.FeedbackReturner pipeline,
        TalkBackAnalytics analytics) {
      super(node, pipeline, analytics);
      this.context = context;
      this.label = label;
      this.longClick = longClick;
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
      logAccessibilityFocus();
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
      LogUtils.v(TAG, "perform result=%b", performed);
      return performed;
    }

    private void logAccessibilityFocus() {
      AccessibilityNodeInfoCompat focus = null;
      if (context instanceof AccessibilityService) {
        try {
          focus = FocusFinder.getAccessibilityFocusNode((AccessibilityService) context, false);
        } catch (RuntimeException e) {
          focus = null;
        }
      }
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
      focus.recycle();
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
