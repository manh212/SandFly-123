package com.google.android.accessibility.talkback.focusmanagement;

import android.content.Context;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import org.checkerframework.checker.nullness.qual.Nullable;

public class FocusMonitor {

  public interface Announcer {
    void announce(String text);
  }

  private final Context context;
  private final Announcer announcer;
  private @Nullable AccessibilityNodeInfoCompat monitoredNode;
  private @Nullable String lastText;
  private boolean active;

  public FocusMonitor(Context context, Announcer announcer) {
    this.context = context.getApplicationContext();
    this.announcer = announcer;
  }

  public boolean isActive() {
    return active;
  }

  public boolean toggle(AccessibilityNodeInfoCompat node) {
    if (active) {
      stop();
      return false;
    }
    if (node == null) {
      return false;
    }
    monitoredNode = node;
    lastText = textOf(node);
    active = true;
    return true;
  }

  public void stop() {
    active = false;
    monitoredNode = null;
    lastText = null;
  }

  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (!active || monitoredNode == null) {
      return;
    }
    int type = event.getEventType();
    if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        && type != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        && type != AccessibilityEvent.TYPE_VIEW_SELECTED) {
      return;
    }
    if (!monitoredNode.refresh()) {
      stop();
      announcer.announce(context.getString(R.string.monitor_stopped));
      return;
    }
    String text = textOf(monitoredNode);
    if (!TextUtils.isEmpty(text) && !text.equals(lastText)) {
      lastText = text;
      announcer.announce(text);
    }
  }

  private @Nullable String textOf(AccessibilityNodeInfoCompat node) {
    CharSequence text = node.getText();
    if (TextUtils.isEmpty(text)) {
      text = node.getContentDescription();
    }
    if (TextUtils.isEmpty(text)) {
      return null;
    }
    return text.toString().trim();
  }
}
