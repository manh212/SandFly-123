package com.google.android.accessibility.talkback.gesture;

import android.content.Context;
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

public class CopyMoreCollector {

  public interface Callback {
    void onCopied(int count);

    void onNothing();
  }

  private static final int MAX_PASSES = 30;
  private static final int MAX_ITEMS = 2000;
  private static final long SCROLL_WAIT_MS = 600L;

  private final Context context;
  private final Callback callback;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final LinkedHashSet<String> seen = new LinkedHashSet<>();
  private final ArrayList<String> items = new ArrayList<>();
  private @Nullable AccessibilityNodeInfoCompat focusNode;
  private @Nullable AccessibilityNodeInfoCompat scrollableNode;
  private boolean started;
  private int passes;
  private int passesWithoutNewItems;

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
    int before = items.size();
    if (root != null) {
      started = false;
      collectFrom(root);
      if (!started && passes > 1) {
        started = true;
        collectFrom(root);
      }
    }
    if (items.size() == before) {
      passesWithoutNewItems++;
    } else {
      passesWithoutNewItems = 0;
    }
    if (passes >= MAX_PASSES || passesWithoutNewItems >= 2) {
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

  private void collectFrom(AccessibilityNodeInfoCompat node) {
    if (node == null || items.size() >= MAX_ITEMS) {
      return;
    }
    if (!started) {
      if (node.equals(focusNode)) {
        started = true;
      } else {
        for (int i = 0; i < node.getChildCount(); i++) {
          collectFrom(node.getChild(i));
          if (started) {
            break;
          }
        }
        return;
      }
    }
    addText(node);
    for (int i = 0; i < node.getChildCount(); i++) {
      collectFrom(node.getChild(i));
      if (items.size() >= MAX_ITEMS) {
        return;
      }
    }
  }

  private void addText(AccessibilityNodeInfoCompat node) {
    if (node.isPassword()) {
      return;
    }
    CharSequence text = node.getText();
    if (TextUtils.isEmpty(text)) {
      text = node.getContentDescription();
    }
    if (TextUtils.isEmpty(text)) {
      return;
    }
    String value = text.toString().trim();
    if (value.isEmpty() || seen.contains(value)) {
      return;
    }
    seen.add(value);
    items.add(value);
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
