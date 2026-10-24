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

import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Runs one overflow-menu item activation as if the item's row supported the action natively.
 * {@link #begin} clicks the row's menu button; while the sequence is pending, {@link
 * #onAccessibilityEvent} watches for the app's menu sheet (a window whose tree contains the
 * script's label view id), clicks the row whose live label matches, and swallows the sheet's
 * window and focus events so none of its feedback is spoken. When the sequence ends — by
 * success, by the label being absent, by a deadline, or by the user backing out — accessibility
 * focus is restored to the originating row, re-resolved from the label and bounds recorded at
 * the start. At most one sequence runs at a time; everything is confined to the main thread.
 */
public final class MenuAutomation {
  private static final int MASTER_TIMEOUT_MS = 3000;
  private static final int SHEET_GRACE_MS = 1000;
  private static final int CLOSE_SETTLE_MS = 1200;
  private static final int SCAN_NODE_CAP = 2048;
  private static final int BOUNDS_TOLERANCE_PX = 64;

  private enum Stage {
    AWAIT_SHEET,
    AWAIT_CLOSE
  }

  /** The window roots the state machine may inspect; faked in tests. */
  interface RootProvider {
    List<AccessibilityNodeInfoCompat> windowRoots();

    @Nullable AccessibilityNodeInfoCompat activeWindowRoot();
  }

  private static final class Pending {
    final Pipeline.FeedbackReturner pipeline;
    final Context context;
    final String packageName;
    final String targetLabel;
    final String sheetLabelViewId;
    final String originLabel;
    final Rect originBounds;
    final Handler handler = new Handler(Looper.getMainLooper());
    Stage stage = Stage.AWAIT_SHEET;
    boolean sheetSeen;
    boolean windowEventSeen;
    boolean failureSpeechPending;
    @Nullable RootProvider lastProvider;
    @Nullable Runnable masterTimeout;
    @Nullable Runnable sheetGrace;
    @Nullable Runnable closeSettle;

    Pending(
        Pipeline.FeedbackReturner pipeline,
        Context context,
        String packageName,
        String targetLabel,
        String sheetLabelViewId,
        String originLabel,
        Rect originBounds) {
      this.pipeline = pipeline;
      this.context = context;
      this.packageName = packageName;
      this.targetLabel = targetLabel;
      this.sheetLabelViewId = sheetLabelViewId;
      this.originLabel = originLabel;
      this.originBounds = originBounds;
    }
  }

  private static @Nullable Pending pending;

  private MenuAutomation() {}

  public static boolean isPending() {
    return pending != null;
  }

  /**
   * Arms a sequence and clicks the row's menu button. The origin is described by the
   * representative's spoken label and the item root's bounds, so the row can be re-resolved in
   * whatever tree is current when the sequence ends.
   */
  public static boolean begin(
      Pipeline.FeedbackReturner pipeline,
      Context context,
      AccessibilityNodeInfoCompat menuButton,
      String targetLabel,
      String sheetLabelViewId,
      String originLabel,
      Rect originBounds) {
    if (pending != null || menuButton.getPackageName() == null || sheetLabelViewId == null) {
      return false;
    }
    Pending session =
        new Pending(
            pipeline,
            context.getApplicationContext(),
            menuButton.getPackageName().toString(),
            targetLabel,
            sheetLabelViewId,
            originLabel,
            new Rect(originBounds));
    pending = session;
    session.masterTimeout = () -> onMasterTimeout(session);
    session.handler.postDelayed(session.masterTimeout, MASTER_TIMEOUT_MS);
    boolean clicked =
        pipeline.returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.nodeAction(menuButton, AccessibilityNodeInfoCompat.ACTION_CLICK)
                .vibration(R.array.view_clicked_pattern));
    if (!clicked) {
      finish(session);
      return false;
    }
    return true;
  }

  /**
   * Event hook for the service: returns true when the event belongs to a running sequence and
   * must be dropped before it can produce any feedback.
   */
  public static boolean onAccessibilityEvent(AccessibilityService service, AccessibilityEvent event) {
    if (pending == null) {
      return false;
    }
    return handleEvent(new ServiceRootProvider(service), event);
  }

  static boolean handleEvent(RootProvider provider, AccessibilityEvent event) {
    Pending session = pending;
    if (session == null) {
      return false;
    }
    session.lastProvider = provider;
    int type = event.getEventType();
    CharSequence eventPackage = event.getPackageName();
    boolean fromApp = eventPackage != null && session.packageName.equals(eventPackage.toString());
    if (!fromApp) {
      if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        // Another app's UI took the foreground; the sequence is over and the new window
        // must be announced as usual.
        finish(session);
      }
      return false;
    }
    return session.stage == Stage.AWAIT_SHEET
        ? handleAwaitSheet(session, provider, event, type)
        : handleAwaitClose(session, provider, event, type);
  }

  private static boolean handleAwaitSheet(
      Pending session, RootProvider provider, AccessibilityEvent event, int type) {
    if (isScanType(type)) {
      AccessibilityNodeInfoCompat sheet =
          findSheetRoot(scanRoots(provider, event), session.sheetLabelViewId);
      if (sheet != null) {
        session.sheetSeen = true;
        if (tryClickTarget(session, sheet)) {
          return true;
        }
        if (session.sheetGrace == null) {
          session.sheetGrace = () -> onSheetGrace(session);
          session.handler.postDelayed(session.sheetGrace, SHEET_GRACE_MS);
        }
        return true;
      }
      if (session.sheetSeen) {
        // The sheet was open and the current window list no longer contains it, before
        // any click: the user backed out. The window list is the evidence, so this is
        // concluded on any scanned event type, not just window events.
        tryRestore(session, provider, /* speakFailure= */ false);
        finish(session);
        return true;
      }
      if (session.windowEventSeen
          && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
              || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
              || type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)) {
        // A window came and went without the sheet ever matching, and the feed is in
        // front again: the user backed out before the sheet could be read. The flag is
        // only raised by an earlier event, so the sheet's own opening event — whose
        // snapshot may not contain its rows yet — never triggers this.
        AccessibilityNodeInfoCompat active = provider.activeWindowRoot();
        if (active != null && resolveOriginRepresentative(active, session) != null) {
          tryRestore(session, provider, /* speakFailure= */ false);
          finish(session);
          return true;
        }
      }
      if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
          || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
        session.windowEventSeen = true;
      }
    }
    return isSuppressedType(type);
  }

  private static boolean handleAwaitClose(
      Pending session, RootProvider provider, AccessibilityEvent event, int type) {
    if (isScanType(type)) {
      boolean sheetGone =
          findSheetRoot(scanRoots(provider, event), session.sheetLabelViewId) == null;
      if (sheetGone) {
        AccessibilityNodeInfoCompat active = provider.activeWindowRoot();
        if (active != null && resolveOriginRepresentative(active, session) != null) {
          tryRestore(session, provider, session.failureSpeechPending);
          finish(session);
          return true;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
          // The sheet closed onto some other UI (the follow-up of the chosen item):
          // the sequence ends silently and the new UI is announced as usual.
          finish(session);
          return false;
        }
      }
    }
    return isSuppressedType(type);
  }

  private static void onMasterTimeout(Pending session) {
    if (pending != session) {
      return;
    }
    if (session.stage == Stage.AWAIT_CLOSE) {
      finish(session);
      return;
    }
    boolean sheetOpen =
        session.sheetSeen
            || (session.lastProvider != null
                && findSheetRoot(session.lastProvider.windowRoots(), session.sheetLabelViewId)
                    != null);
    if (sheetOpen) {
      startFailureClose(session);
    } else {
      // The menu never opened; focus never left the row, but the action has failed.
      tryRestore(session, session.lastProvider, /* speakFailure= */ true);
      finish(session);
    }
  }

  private static void onSheetGrace(Pending session) {
    if (pending != session || session.stage != Stage.AWAIT_SHEET) {
      return;
    }
    RootProvider provider = session.lastProvider;
    AccessibilityNodeInfoCompat sheet =
        provider == null ? null : findSheetRoot(provider.windowRoots(), session.sheetLabelViewId);
    if (sheet == null) {
      tryRestore(session, provider, /* speakFailure= */ false);
      finish(session);
      return;
    }
    if (!tryClickTarget(session, sheet)) {
      startFailureClose(session);
    }
  }

  private static void onCloseSettle(Pending session) {
    if (pending != session || session.stage != Stage.AWAIT_CLOSE) {
      return;
    }
    RootProvider provider = session.lastProvider;
    if (provider != null
        && findSheetRoot(provider.windowRoots(), session.sheetLabelViewId) == null) {
      AccessibilityNodeInfoCompat active = provider.activeWindowRoot();
      if (active != null && resolveOriginRepresentative(active, session) != null) {
        tryRestore(session, provider, session.failureSpeechPending);
      }
    }
    finish(session);
  }

  /** Clicks the sheet row matching the target label; false when the sheet has no such row. */
  private static boolean tryClickTarget(Pending session, AccessibilityNodeInfoCompat sheetRoot) {
    AccessibilityNodeInfoCompat row =
        sheetTargetRow(sheetRoot, session.sheetLabelViewId, session.targetLabel);
    if (row == null) {
      return false;
    }
    session.pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.nodeAction(row, AccessibilityNodeInfoCompat.ACTION_CLICK));
    session.stage = Stage.AWAIT_CLOSE;
    cancel(session, session.sheetGrace);
    session.sheetGrace = null;
    session.closeSettle = () -> onCloseSettle(session);
    session.handler.postDelayed(session.closeSettle, CLOSE_SETTLE_MS);
    return true;
  }

  /** Closes the sheet with Back and arranges for focus restoration plus the failure signal. */
  private static void startFailureClose(Pending session) {
    session.pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.systemAction(AccessibilityService.GLOBAL_ACTION_BACK));
    session.failureSpeechPending = true;
    session.stage = Stage.AWAIT_CLOSE;
    cancel(session, session.sheetGrace);
    session.sheetGrace = null;
    session.closeSettle = () -> onCloseSettle(session);
    session.handler.postDelayed(session.closeSettle, CLOSE_SETTLE_MS);
  }

  /**
   * Sets accessibility focus back on the originating row, re-resolved in the active window, and
   * optionally speaks the minimal failure signal afterwards.
   */
  private static void tryRestore(Pending session, @Nullable RootProvider provider, boolean speakFailure) {
    if (provider != null) {
      AccessibilityNodeInfoCompat active = provider.activeWindowRoot();
      AccessibilityNodeInfoCompat representative =
          active == null ? null : resolveOriginRepresentative(active, session);
      if (representative != null) {
        session.pipeline.returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.focus(
                    representative,
                    FocusActionInfo.builder()
                        .setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE)
                        .setInitialFocusType(FocusActionInfo.RESTORED_LAST_FOCUS)
                        .build())
                .setForceRefocus(true));
      }
    }
    if (speakFailure) {
      session.pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.speech(
              session.context.getString(R.string.title_collapse_menu_not_available)));
    }
  }

  private static void finish(Pending session) {
    if (pending != session) {
      return;
    }
    cancel(session, session.masterTimeout);
    cancel(session, session.sheetGrace);
    cancel(session, session.closeSettle);
    pending = null;
  }

  private static void cancel(Pending session, @Nullable Runnable runnable) {
    if (runnable != null) {
      session.handler.removeCallbacks(runnable);
    }
  }

  static void resetForTest() {
    pending = null;
  }

  // --------------------------------------------------------------------------------------------
  // Tree inspection
  // --------------------------------------------------------------------------------------------

  /** The first window root whose subtree contains a node with the given view id, or null. */
  static @Nullable AccessibilityNodeInfoCompat findSheetRoot(
      List<AccessibilityNodeInfoCompat> roots, String labelViewId) {
    for (AccessibilityNodeInfoCompat root : roots) {
      List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
      collect(root, nodes);
      for (AccessibilityNodeInfoCompat node : nodes) {
        if (labelViewId.equals(node.getViewIdResourceName())) {
          return root;
        }
      }
    }
    return null;
  }

  /**
   * The clickable row of the sheet whose label view reads exactly {@code targetLabel}
   * (surrounding whitespace ignored on both sides), or null.
   */
  static @Nullable AccessibilityNodeInfoCompat sheetTargetRow(
      AccessibilityNodeInfoCompat sheetRoot, String labelViewId, String targetLabel) {
    String target = targetLabel.trim();
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    collect(sheetRoot, nodes);
    for (AccessibilityNodeInfoCompat node : nodes) {
      if (!labelViewId.equals(node.getViewIdResourceName())) {
        continue;
      }
      CharSequence label = node.getText();
      if (TextUtils.isEmpty(label)) {
        label = node.getContentDescription();
      }
      if (label == null || !target.equals(label.toString().trim())) {
        continue;
      }
      for (AccessibilityNodeInfoCompat current = node;
          current != null;
          current = current.getParent()) {
        if (current.isClickable()) {
          return current;
        }
        if (current.equals(sheetRoot)) {
          break;
        }
      }
    }
    return null;
  }

  /**
   * Re-resolves the originating row's representative inside a window root: a collapsed item
   * whose representative speaks the recorded label, preferring one whose item bounds still
   * match the recorded bounds.
   */
  private static @Nullable AccessibilityNodeInfoCompat resolveOriginRepresentative(
      AccessibilityNodeInfoCompat windowRoot, Pending session) {
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    collect(windowRoot, nodes);
    AccessibilityNodeInfoCompat labelOnlyMatch = null;
    for (AccessibilityNodeInfoCompat node : nodes) {
      if (!CollapseEngine.isCollectionItem(node)) {
        continue;
      }
      CollapsedItem item = CollapseEngine.matchItemRoot(node, CollapseScripts.all());
      if (item == null) {
        continue;
      }
      CharSequence label = ItemScan.spokenLabel(item.representative());
      if (label == null || !session.originLabel.equals(label.toString())) {
        continue;
      }
      Rect bounds = new Rect();
      item.itemRoot().getBoundsInScreen(bounds);
      if (boundsClose(bounds, session.originBounds)) {
        return item.representative();
      }
      if (labelOnlyMatch == null) {
        labelOnlyMatch = item.representative();
      }
    }
    return labelOnlyMatch;
  }

  private static boolean boundsClose(Rect a, Rect b) {
    return Math.abs(a.left - b.left) <= BOUNDS_TOLERANCE_PX
        && Math.abs(a.top - b.top) <= BOUNDS_TOLERANCE_PX
        && Math.abs(a.right - b.right) <= BOUNDS_TOLERANCE_PX
        && Math.abs(a.bottom - b.bottom) <= BOUNDS_TOLERANCE_PX;
  }

  private static void collect(AccessibilityNodeInfoCompat node, List<AccessibilityNodeInfoCompat> out) {
    if (node == null || out.size() >= SCAN_NODE_CAP) {
      return;
    }
    out.add(node);
    for (int i = 0; i < node.getChildCount(); i++) {
      collect(node.getChild(i), out);
    }
  }

  private static List<AccessibilityNodeInfoCompat> scanRoots(
      RootProvider provider, AccessibilityEvent event) {
    List<AccessibilityNodeInfoCompat> roots = new ArrayList<>(provider.windowRoots());
    AccessibilityNodeInfo source = event.getSource();
    if (source != null) {
      AccessibilityNodeInfoCompat root = AccessibilityNodeInfoCompat.wrap(source);
      while (root.getParent() != null) {
        root = root.getParent();
      }
      if (!roots.contains(root)) {
        roots.add(root);
      }
    }
    return roots;
  }

  private static boolean isScanType(int type) {
    return type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        || type == AccessibilityEvent.TYPE_VIEW_FOCUSED;
  }

  private static boolean isSuppressedType(int type) {
    return isScanType(type)
        || type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
        || type == AccessibilityEvent.TYPE_VIEW_SCROLLED;
  }

  private static final class ServiceRootProvider implements RootProvider {
    private final AccessibilityService service;

    ServiceRootProvider(AccessibilityService service) {
      this.service = service;
    }

    @Override
    public List<AccessibilityNodeInfoCompat> windowRoots() {
      List<AccessibilityNodeInfoCompat> roots = new ArrayList<>();
      for (AccessibilityWindowInfo window : service.getWindows()) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null) {
          roots.add(AccessibilityNodeInfoCompat.wrap(root));
        }
      }
      return roots;
    }

    @Override
    public @Nullable AccessibilityNodeInfoCompat activeWindowRoot() {
      for (AccessibilityWindowInfo window : service.getWindows()) {
        if (window.isActive()) {
          AccessibilityNodeInfo root = window.getRoot();
          if (root != null) {
            return AccessibilityNodeInfoCompat.wrap(root);
          }
        }
      }
      return null;
    }
  }
}
