package com.google.android.accessibility.talkback.gesture;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.utils.ClipboardUtils;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Collects readable page content from the focused item through the end of its scrollable area. */
public class CopyMoreCollector {

  public interface Callback {
    void onCopied(int count);

    void onNothing();
  }

  private static final int MAX_PASSES = 100;
  private static final int MAX_ITEMS = 10000;
  private static final long SCROLL_WAIT_MS = 600L;

  private final Context context;
  private final Callback callback;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final LinkedHashSet<String> seen = new LinkedHashSet<>();
  private final ArrayList<String> items = new ArrayList<>();
  private @Nullable AccessibilityNodeInfoCompat focusNode;
  private @Nullable AccessibilityNodeInfoCompat scrollableNode;
  private boolean startedAtFocus;
  private int passes;
  private int passesWithoutProgress;
  private String lastContentSnapshot = "";

  public CopyMoreCollector(Context context, Callback callback) {
    this.context = context.getApplicationContext();
    this.callback = callback;
  }

  public void start(AccessibilityNodeInfoCompat focus) {
    if (focus == null) {
      callback.onNothing();
      return;
    }
    focusNode = focus;
    scrollableNode = findScrollableAncestor(focus);
    collectPass();
  }

  private void collectPass() {
    passes++;
    AccessibilityNodeInfoCompat root =
        focusNode == null ? null : AccessibilityNodeInfoUtils.getRoot(focusNode);
    if (root == null && scrollableNode != null) {
      root = AccessibilityNodeInfoUtils.getRoot(scrollableNode);
    }
    int before = items.size();
    if (root != null) {
      if (startedAtFocus) {
        collectSubtree(root);
      } else {
        startedAtFocus = collectFrom(root);
      }
    }
    String contentSnapshot = root == null ? "" : createContentSnapshot(root);
    boolean progressed = items.size() > before || !contentSnapshot.equals(lastContentSnapshot);
    if (progressed) {
      passesWithoutProgress = 0;
    } else {
      passesWithoutProgress++;
    }
    lastContentSnapshot = contentSnapshot;
    if (passes >= MAX_PASSES || passesWithoutProgress >= 2) {
      finish();
      return;
    }
    if (scrollableNode == null || !scrollableNode.refresh()) {
      finish();
      return;
    }
    boolean scrolled =
        scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    if (!scrolled) {
      finish();
      return;
    }
    handler.postDelayed(this::collectPass, SCROLL_WAIT_MS);
  }

  private boolean collectFrom(AccessibilityNodeInfoCompat node) {
    if (node == null || items.size() >= MAX_ITEMS) {
      return false;
    }
    if (node.equals(focusNode)) {
      startedAtFocus = true;
      collectSubtree(node);
      return true;
    }
    if (CopyTextUtils.isControl(node)) {
      return false;
    }
    markTextAsSeen(node);
    int childCount = node.getChildCount();
    for (int i = 0; i < childCount; i++) {
      if (collectFrom(node.getChild(i))) {
        for (int next = i + 1; next < childCount && items.size() < MAX_ITEMS; next++) {
          collectSubtree(node.getChild(next));
        }
        return true;
      }
    }
    return false;
  }

  private void collectSubtree(AccessibilityNodeInfoCompat node) {
    if (node == null || items.size() >= MAX_ITEMS || CopyTextUtils.isControl(node)) {
      return;
    }
    addText(node);
    for (int i = 0; i < node.getChildCount(); i++) {
      collectSubtree(node.getChild(i));
      if (items.size() >= MAX_ITEMS) {
        return;
      }
    }
  }

  private void addText(AccessibilityNodeInfoCompat node) {
    String value = CopyTextUtils.getNodeText(node);
    if (value.isEmpty() || seen.contains(value)) {
      return;
    }
    seen.add(value);
    items.add(value);
  }

  private void markTextAsSeen(AccessibilityNodeInfoCompat node) {
    String value = CopyTextUtils.getNodeText(node);
    if (!value.isEmpty()) {
      seen.add(value);
    }
  }

  private String createContentSnapshot(AccessibilityNodeInfoCompat node) {
    StringBuilder snapshot = new StringBuilder();
    appendContentSnapshot(node, snapshot);
    return snapshot.toString();
  }

  private void appendContentSnapshot(AccessibilityNodeInfoCompat node, StringBuilder snapshot) {
    if (node == null || CopyTextUtils.isControl(node) || node.isPassword()) {
      return;
    }
    String text = CopyTextUtils.getNodeText(node);
    if (!text.isEmpty()) {
      Rect bounds = new Rect();
      node.getBoundsInScreen(bounds);
      snapshot
          .append(text.length())
          .append(':')
          .append(text)
          .append('@')
          .append(bounds.left)
          .append(',')
          .append(bounds.top)
          .append(',')
          .append(bounds.right)
          .append(',')
          .append(bounds.bottom)
          .append(';');
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      appendContentSnapshot(node.getChild(i), snapshot);
    }
  }

  private @Nullable AccessibilityNodeInfoCompat findScrollableAncestor(
      AccessibilityNodeInfoCompat node) {
    AccessibilityNodeInfoCompat current = node;
    while (current != null) {
      if (current.isScrollable()
          && AccessibilityNodeInfoUtils.supportsAction(
              current, AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private void finish() {
    handler.removeCallbacksAndMessages(null);
    if (items.isEmpty()) {
      callback.onNothing();
      return;
    }
    String joined = TextUtils.join("\n", items);
    if (ClipboardUtils.copyToClipboard(context, joined)) {
      callback.onCopied(items.size());
    } else {
      callback.onNothing();
    }
  }
}
