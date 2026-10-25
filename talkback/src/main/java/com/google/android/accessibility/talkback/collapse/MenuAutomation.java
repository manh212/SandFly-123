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
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *
 * <p>{@link #beginTray} runs the same state machine for an app's own action tray (Facebook's
 * comment tray): the opener is a real long press instead of a click, the sheet is recognised
 * by anchors instead of a view id, and the click lands on the tray's real button carrying
 * the target label. Tray stages log under the perform tag so one log filter shows a whole
 * activation.
 */
public final class MenuAutomation {
  // The perform log tag: tray stages are stages of a collapsed action's perform.
  private static final String TAG = "CollapsedItemActions";
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
    /** Toggle targets only: the recorded state variants of the target label; null if plain. */
    final @Nullable String[] targetVariants;
    /** Toggle targets only: the stable cores of the target label; null if plain. */
    final @Nullable String[] targetCores;
    final @Nullable String sheetLabelViewId;
    final String originLabel;
    final Rect originBounds;
    // Tray mode only: how the sheet is recognised and what happens inside it.
    final boolean trayMode;
    final @Nullable String trayHeaderText;
    final @Nullable String trayAuthorName;
    final @Nullable String[] trayTargetLabels;
    final boolean restoreFocusOnClose;
    /** Tray mode only: speaks the failure; null speaks the menu-not-available string. */
    final @Nullable Runnable failureAnnouncer;
    final long beginTimeMs;
    final Handler handler = new Handler(Looper.getMainLooper());
    Stage stage = Stage.AWAIT_SHEET;
    boolean sheetSeen;
    boolean windowEventSeen;
    boolean failureSpeechPending;
    boolean trayButtonsLogged;
    @Nullable RootProvider lastProvider;
    @Nullable Runnable masterTimeout;
    @Nullable Runnable sheetGrace;
    @Nullable Runnable closeSettle;

    Pending(
        Pipeline.FeedbackReturner pipeline,
        Context context,
        String packageName,
        String targetLabel,
        @Nullable String[] targetVariants,
        @Nullable String[] targetCores,
        String sheetLabelViewId,
        String originLabel,
        Rect originBounds) {
      this.pipeline = pipeline;
      this.context = context;
      this.packageName = packageName;
      this.targetLabel = targetLabel;
      this.targetVariants = targetVariants;
      this.targetCores = targetCores;
      this.sheetLabelViewId = sheetLabelViewId;
      this.originLabel = originLabel;
      this.originBounds = originBounds;
      this.trayMode = false;
      this.trayHeaderText = null;
      this.trayAuthorName = null;
      this.trayTargetLabels = null;
      this.restoreFocusOnClose = true;
      this.failureAnnouncer = null;
      this.beginTimeMs = SystemClock.uptimeMillis();
    }

    Pending(
        Pipeline.FeedbackReturner pipeline,
        Context context,
        String packageName,
        String trayHeaderText,
        @Nullable String trayAuthorName,
        @Nullable String[] trayTargetLabels,
        boolean restoreFocusOnClose,
        Runnable failureAnnouncer,
        String originLabel,
        Rect originBounds) {
      this.pipeline = pipeline;
      this.context = context;
      this.packageName = packageName;
      this.targetLabel = "";
      this.targetVariants = null;
      this.targetCores = null;
      this.sheetLabelViewId = null;
      this.originLabel = originLabel;
      this.originBounds = originBounds;
      this.trayMode = true;
      this.trayHeaderText = trayHeaderText;
      this.trayAuthorName = trayAuthorName;
      this.trayTargetLabels = trayTargetLabels;
      this.restoreFocusOnClose = restoreFocusOnClose;
      this.failureAnnouncer = failureAnnouncer;
      this.beginTimeMs = SystemClock.uptimeMillis();
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
    return begin(
        pipeline,
        context,
        menuButton,
        targetLabel,
        /* targetVariants= */ null,
        /* targetCores= */ null,
        sheetLabelViewId,
        originLabel,
        originBounds);
  }

  /**
   * Arms a sequence for a state-dependent (toggle) catalog item: the offered label is neutral,
   * so the sheet row is matched by the item's recorded state variants and stable cores instead
   * of by the label itself. See {@link #sheetToggleTargetRow}.
   */
  public static boolean begin(
      Pipeline.FeedbackReturner pipeline,
      Context context,
      AccessibilityNodeInfoCompat menuButton,
      String targetLabel,
      @Nullable String[] targetVariants,
      @Nullable String[] targetCores,
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
            targetVariants,
            targetCores,
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
   * Arms a tray sequence and fires the opener: a real long press on the comment's live body
   * shell, which opens the app's own action tray. The tray is then recognised by its anchors
   * (see {@link #findTrayRoot}), and the sequence clicks the tray's real button whose own
   * label is one of {@code targetButtonLabels} — the target's recorded labels, one per
   * language, since the tray speaks the app's current language. When {@code
   * restoreFocusOnClose} is false, the sequence ends without pulling focus back when the
   * tray closes — the follow-up UI (a composer, a share or report flow) is the intended
   * result and keeps the screen. Failures are announced through {@code failureAnnouncer}
   * instead of the menu string. Returns false when the sequence could not be armed or the
   * opener was refused.
   */
  public static boolean beginTray(
      Pipeline.FeedbackReturner pipeline,
      Context context,
      AccessibilityNodeInfoCompat bodyShell,
      String[] targetButtonLabels,
      boolean restoreFocusOnClose,
      String trayHeaderText,
      @Nullable String trayAuthorName,
      String originLabel,
      Rect originBounds,
      Runnable failureAnnouncer) {
    if (pending != null || bodyShell.getPackageName() == null) {
      return false;
    }
    Pending session =
        new Pending(
            pipeline,
            context.getApplicationContext(),
            bodyShell.getPackageName().toString(),
            trayHeaderText,
            trayAuthorName,
            targetButtonLabels,
            restoreFocusOnClose,
            failureAnnouncer,
            originLabel,
            new Rect(originBounds));
    pending = session;
    session.masterTimeout = () -> onMasterTimeout(session);
    session.handler.postDelayed(session.masterTimeout, MASTER_TIMEOUT_MS);
    boolean opened =
        pipeline.returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.nodeAction(bodyShell, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                .vibration(R.array.view_long_clicked_pattern));
    LogUtils.v(TAG, "tray opener nodeAction result=%b", opened);
    if (!opened) {
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
      List<AccessibilityNodeInfoCompat> roots = scanRoots(provider, event);
      if (session.trayMode && seesForeignTray(session, roots)) {
        // A tray is open, but its anchor names a different comment: pressing anything in
        // it would act on the wrong comment, so the sequence aborts without a click.
        LogUtils.v(TAG, "tray anchor mismatch: open tray belongs to another comment");
        tryRestore(session, provider, /* speakFailure= */ true);
        finish(session);
        return true;
      }
      AccessibilityNodeInfoCompat sheet = findSheet(session, roots);
      if (sheet != null) {
        if (session.trayMode && !session.sheetSeen) {
          LogUtils.v(
              TAG,
              "tray sheet appeared afterMs=%d",
              SystemClock.uptimeMillis() - session.beginTimeMs);
          logTrayButtons(session, sheet);
        }
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
      boolean sheetGone = findSheet(session, scanRoots(provider, event)) == null;
      if (sheetGone && !session.restoreFocusOnClose) {
        // The sheet closed onto its follow-up UI (the reply composer), which is the
        // intended result: end silently and let the new UI be announced, without
        // pulling focus back to the row.
        finish(session);
        return false;
      }
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
      // Settle exactly like a normal close, so the deadline restores focus and speaks
      // a pending failure instead of ending the sequence in silence.
      onCloseSettle(session);
      return;
    }
    boolean sheetOpen =
        session.sheetSeen
            || (session.lastProvider != null
                && findSheet(session, session.lastProvider.windowRoots()) != null);
    if (sheetOpen) {
      startFailureClose(session);
    } else {
      if (session.trayMode) {
        LogUtils.v(TAG, "tray sheet never appeared within the master timeout");
      }
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
        provider == null ? null : findSheet(session, provider.windowRoots());
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
    if (provider != null && findSheet(session, provider.windowRoots()) == null) {
      AccessibilityNodeInfoCompat active = provider.activeWindowRoot();
      if (session.restoreFocusOnClose
          && active != null
          && resolveOriginRepresentative(active, session) != null) {
        tryRestore(session, provider, session.failureSpeechPending);
      }
    }
    finish(session);
  }

  /** Clicks the sheet row matching the target; false when the sheet has no such row. */
  private static boolean tryClickTarget(Pending session, AccessibilityNodeInfoCompat sheetRoot) {
    AccessibilityNodeInfoCompat row =
        session.trayMode
            ? trayTargetButton(sheetRoot, session.trayTargetLabels)
            : session.targetCores != null
                ? sheetToggleTargetRow(
                    sheetRoot,
                    session.sheetLabelViewId,
                    session.targetVariants,
                    session.targetCores)
                : sheetTargetRow(sheetRoot, session.sheetLabelViewId, session.targetLabel);
    if (row == null) {
      return false;
    }
    boolean clicked =
        session.pipeline.returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.nodeAction(row, AccessibilityNodeInfoCompat.ACTION_CLICK));
    if (session.trayMode) {
      LogUtils.v(TAG, "tray target click label=%s result=%b", sheetNodeLabel(row), clicked);
      if (!clicked) {
        // The tray's real button refused the action too: report the click as not done,
        // so the grace and failure-close path takes over instead of waiting for a close
        // that will never come.
        return false;
      }
    }
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
      if (session.failureAnnouncer != null) {
        session.failureAnnouncer.run();
      } else {
        session.pipeline.returnFeedback(
            EVENT_ID_UNTRACKED,
            Feedback.speech(
                session.context.getString(R.string.title_collapse_menu_not_available)));
      }
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

  /** The session's sheet: by view id for menus, by anchors for the tray. */
  private static @Nullable AccessibilityNodeInfoCompat findSheet(
      Pending session, List<AccessibilityNodeInfoCompat> roots) {
    if (!session.trayMode) {
      return findSheetRoot(roots, session.sheetLabelViewId);
    }
    for (AccessibilityNodeInfoCompat root : roots) {
      // The window the sequence started from holds the comment itself, whose own
      // reply button carries the same author anchor: the tray is a window of its
      // own, so the origin's window is never the tray.
      if (resolveOriginRepresentative(root, session) != null) {
        continue;
      }
      if (findTrayRoot(List.of(root), session.trayHeaderText, session.trayAuthorName)
          != null) {
        return root;
      }
    }
    return null;
  }

  /**
   * The window root holding the app's action tray, recognised by anchors instead of a view
   * id: a node carrying the tray's recorded header text, or a reply button whose label names
   * {@code authorName} — the anti-confusion anchor, since a tray belonging to a different
   * comment names a different author and can never match. Either anchor suffices because
   * recorded tray shapes vary in which of them a build exposes. Both the text and the
   * content description of every node are examined: recorded buttons carry their plain
   * label as text and the anchored form as description.
   */
  static @Nullable AccessibilityNodeInfoCompat findTrayRoot(
      List<AccessibilityNodeInfoCompat> roots,
      @Nullable String headerText,
      @Nullable String authorName) {
    for (AccessibilityNodeInfoCompat root : roots) {
      List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
      collect(root, nodes);
      for (AccessibilityNodeInfoCompat node : nodes) {
        for (CharSequence label : labelsOf(node)) {
          String text = label.toString().trim();
          if (headerText != null && headerText.equals(text)) {
            return root;
          }
          if (authorName != null && authorName.equals(anchoredReplyAuthor(text))) {
            return root;
          }
        }
      }
    }
    return null;
  }

  private static final String[] REPLY_AUTHOR_PREFIXES = {
    "Trả lời bình luận của ", "Reply to the comment by ", "Reply to "
  };

  /**
   * The author named by an author-anchored reply label ("Reply to the comment by X, ...",
   * "Trả lời bình luận của X, ...", "Reply to X's comment"), or null when the label is not
   * anchored. Recorded tray reply buttons are usually plain, so null means "no anchor on
   * this label", never "a different comment".
   */
  static @Nullable String anchoredReplyAuthor(CharSequence label) {
    if (label == null) {
      return null;
    }
    String text = label.toString().trim();
    for (String prefix : REPLY_AUTHOR_PREFIXES) {
      if (!text.startsWith(prefix)) {
        continue;
      }
      String rest = text.substring(prefix.length());
      int possessive = rest.indexOf("'s comment");
      if (possessive > 0) {
        return rest.substring(0, possessive).trim();
      }
      int end = rest.length();
      for (int i = 0; i < rest.length(); i++) {
        char c = rest.charAt(i);
        if (c == ',' || c == '.') {
          end = i;
          break;
        }
      }
      String author = rest.substring(0, end).trim();
      return author.isEmpty() ? null : author;
    }
    return null;
  }

  /** Every label form a node carries: its text and its content description, when present. */
  private static List<CharSequence> labelsOf(AccessibilityNodeInfoCompat node) {
    List<CharSequence> labels = new ArrayList<>(2);
    if (!TextUtils.isEmpty(node.getText())) {
      labels.add(node.getText());
    }
    if (!TextUtils.isEmpty(node.getContentDescription())) {
      labels.add(node.getContentDescription());
    }
    return labels;
  }

  /**
   * Whether the current windows hold a tray belonging to a different comment than the one
   * the sequence started from: a tray-shaped window — one carrying the tray header or an
   * author-anchored reply button — whose anchored reply names another author. The window
   * holding the origin row is exempt, because the feed itself lists other comments'
   * anchored reply buttons. Always false when the sequence recorded no author.
   */
  private static boolean seesForeignTray(
      Pending session, List<AccessibilityNodeInfoCompat> roots) {
    if (session.trayAuthorName == null) {
      return false;
    }
    for (AccessibilityNodeInfoCompat root : roots) {
      if (resolveOriginRepresentative(root, session) != null) {
        continue;
      }
      List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
      collect(root, nodes);
      boolean trayShaped = false;
      boolean foreignAuthor = false;
      for (AccessibilityNodeInfoCompat node : nodes) {
        for (CharSequence label : labelsOf(node)) {
          String text = label.toString().trim();
          if (text.equals(session.trayHeaderText)) {
            trayShaped = true;
          }
          String author = anchoredReplyAuthor(text);
          if (author != null) {
            trayShaped = true;
            if (!author.equals(session.trayAuthorName)) {
              foreignAuthor = true;
            }
          }
        }
      }
      if (trayShaped && foreignAuthor) {
        return true;
      }
    }
    return false;
  }

  /**
   * The tray's real button carrying exactly one of {@code targetLabels} (surrounding
   * whitespace ignored) — the target's recorded labels, one per language — or null when
   * there is not exactly one match across all of them. Only Button-class nodes are
   * considered: the click must land on the button itself, never on a container that
   * happens to carry the same words.
   */
  static @Nullable AccessibilityNodeInfoCompat trayTargetButton(
      AccessibilityNodeInfoCompat trayRoot, @Nullable String[] targetLabels) {
    if (targetLabels == null) {
      return null;
    }
    Set<String> targets = new HashSet<>();
    for (String label : targetLabels) {
      if (label != null) {
        targets.add(label.trim());
      }
    }
    if (targets.isEmpty()) {
      return null;
    }
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    collect(trayRoot, nodes);
    AccessibilityNodeInfoCompat found = null;
    for (AccessibilityNodeInfoCompat node : nodes) {
      if (!"android.widget.Button".equals(node.getClassName())) {
        continue;
      }
      CharSequence label = sheetNodeLabel(node);
      if (label == null || !targets.contains(label.toString().trim())) {
        continue;
      }
      if (found != null) {
        return null;
      }
      found = node;
    }
    return found;
  }

  /** Logs the tray's buttons and their ACTION_CLICK support, once per sequence. */
  private static void logTrayButtons(
      Pending session, AccessibilityNodeInfoCompat trayRoot) {
    if (session.trayButtonsLogged) {
      return;
    }
    session.trayButtonsLogged = true;
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    collect(trayRoot, nodes);
    for (AccessibilityNodeInfoCompat node : nodes) {
      if (!"android.widget.Button".equals(node.getClassName())) {
        continue;
      }
      LogUtils.v(
          TAG,
          "tray button label=%s supportsClick=%b",
          sheetNodeLabel(node),
          PerformActionUtils.isNodeSupportAction(
              node, AccessibilityNodeInfoCompat.ACTION_CLICK));
    }
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
      CharSequence label = sheetNodeLabel(node);
      if (label == null || !target.equals(label.toString().trim())) {
        continue;
      }
      AccessibilityNodeInfoCompat row = clickableRow(sheetRoot, node);
      if (row != null) {
        return row;
      }
    }
    return null;
  }

  /**
   * The clickable row of the sheet matching a state-dependent (toggle) catalog item, or null.
   * A row matches when its live label is one of the item's recorded state variants, or — the
   * general rule — carries one of the item's stable cores, the part of the wording the state
   * prefix never touches; comparisons ignore case and runs of whitespace. The match must be
   * unique across the whole sheet: no match means the item is absent for this video, and more
   * than one match means the sheet cannot be read unambiguously, so both end the sequence
   * without a click rather than guessing.
   */
  static @Nullable AccessibilityNodeInfoCompat sheetToggleTargetRow(
      AccessibilityNodeInfoCompat sheetRoot,
      String labelViewId,
      @Nullable String[] stateVariants,
      String[] stateCores) {
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    collect(sheetRoot, nodes);
    Set<String> variants = new HashSet<>();
    if (stateVariants != null) {
      for (String variant : stateVariants) {
        variants.add(normalizeLabel(variant));
      }
    }
    AccessibilityNodeInfoCompat row =
        uniqueToggleRow(sheetRoot, labelViewId, nodes, variants, /* cores= */ null);
    if (row != null) {
      return row;
    }
    List<String> cores = new ArrayList<>();
    for (String core : stateCores) {
      cores.add(normalizeLabel(core));
    }
    return uniqueToggleRow(sheetRoot, labelViewId, nodes, /* variants= */ null, cores);
  }

  /**
   * The single row whose normalized label is in {@code variants} (when non-null) or carries one
   * of {@code cores} (when non-null); null when zero or several distinct rows match, so an
   * ambiguous sheet never produces a guess click.
   */
  private static @Nullable AccessibilityNodeInfoCompat uniqueToggleRow(
      AccessibilityNodeInfoCompat sheetRoot,
      String labelViewId,
      List<AccessibilityNodeInfoCompat> nodes,
      @Nullable Set<String> variants,
      @Nullable List<String> cores) {
    AccessibilityNodeInfoCompat found = null;
    for (AccessibilityNodeInfoCompat node : nodes) {
      if (!labelViewId.equals(node.getViewIdResourceName())) {
        continue;
      }
      CharSequence rawLabel = sheetNodeLabel(node);
      if (rawLabel == null) {
        continue;
      }
      String label = normalizeLabel(rawLabel);
      boolean matches =
          variants != null
              ? variants.contains(label)
              : cores != null && containsAnyCore(label, cores);
      if (!matches) {
        continue;
      }
      AccessibilityNodeInfoCompat row = clickableRow(sheetRoot, node);
      if (row == null || row.equals(found)) {
        continue;
      }
      if (found != null) {
        return null;
      }
      found = row;
    }
    return found;
  }

  private static boolean containsAnyCore(String label, List<String> cores) {
    for (String core : cores) {
      if (label.contains(core)) {
        return true;
      }
    }
    return false;
  }

  /** The label a sheet label-view node carries: its text, or its description when it has none. */
  private static @Nullable CharSequence sheetNodeLabel(AccessibilityNodeInfoCompat node) {
    CharSequence label = node.getText();
    if (TextUtils.isEmpty(label)) {
      label = node.getContentDescription();
    }
    return label;
  }

  /** The clickable row a sheet label lives in: its nearest clickable ancestor, or itself. */
  private static @Nullable AccessibilityNodeInfoCompat clickableRow(
      AccessibilityNodeInfoCompat sheetRoot, AccessibilityNodeInfoCompat labelNode) {
    for (AccessibilityNodeInfoCompat current = labelNode;
        current != null;
        current = current.getParent()) {
      if (current.isClickable()) {
        return current;
      }
      if (current.equals(sheetRoot)) {
        break;
      }
    }
    return null;
  }

  /** Case- and whitespace-insensitive form of a sheet label, for toggle matching. */
  private static String normalizeLabel(CharSequence label) {
    return label.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  /**
   * Re-resolves the originating row's representative inside a window root: a collapsed item
   * whose representative speaks the recorded label, preferring one whose item bounds still
   * match the recorded bounds. When several items carry the label and none matches the
   * bounds, the one whose bounds lie closest to the recorded bounds wins, so duplicate
   * titles cannot send the focus to an arbitrary namesake.
   */
  private static @Nullable AccessibilityNodeInfoCompat resolveOriginRepresentative(
      AccessibilityNodeInfoCompat windowRoot, Pending session) {
    List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    collect(windowRoot, nodes);
    AccessibilityNodeInfoCompat labelOnlyMatch = null;
    long labelOnlyDistance = Long.MAX_VALUE;
    for (AccessibilityNodeInfoCompat node : nodes) {
      if (!CollapseEngine.isCollectionItem(node)) {
        continue;
      }
      CollapsedItem item = CollapseEngine.matchItemRoot(node, CollapseScripts.all());
      if (item == null) {
        continue;
      }
      // A composite item's representative speaks nothing itself; its identity is the
      // label the engine composed from its fields, so tray sessions match on that.
      CharSequence label =
          session.trayMode ? item.composedLabel() : ItemScan.spokenLabel(item.representative());
      if (label == null || !session.originLabel.equals(label.toString())) {
        continue;
      }
      Rect bounds = new Rect();
      item.itemRoot().getBoundsInScreen(bounds);
      if (boundsClose(bounds, session.originBounds)) {
        return item.representative();
      }
      long distance = boundsDistance(bounds, session.originBounds);
      if (distance < labelOnlyDistance) {
        labelOnlyDistance = distance;
        labelOnlyMatch = item.representative();
      }
    }
    return labelOnlyMatch;
  }

  private static long boundsDistance(Rect a, Rect b) {
    return Math.abs((long) a.centerX() - b.centerX())
        + Math.abs((long) a.centerY() - b.centerY());
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
