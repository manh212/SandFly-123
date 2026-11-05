package com.google.android.accessibility.talkback.notifications;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;

public final class NotificationWindowPolicy {
  private NotificationWindowPolicy() {}

  public static boolean isAutomaticNotificationWindow(AccessibilityService service, int id) {
    for (AccessibilityWindowInfo window : service.getWindows()) {
      if (window.getId() != id || window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM
          || window.isFocused() || window.isAccessibilityFocused()) continue;
      AccessibilityNodeInfo root = window.getRoot();
      if (root == null || !"com.android.systemui".contentEquals(String.valueOf(root.getPackageName()))) continue;
      ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
      pending.add(root);
      int visited = 0;
      while (!pending.isEmpty() && visited++ < 4096) {
        AccessibilityNodeInfo node = pending.removeFirst();
        String viewId = node.getViewIdResourceName();
        if (node.isVisibleToUser() && viewId != null
            && (viewId.endsWith("/expandableNotificationRow")
                || viewId.endsWith("/heads_up_status_bar_view"))) return true;
        for (int index = 0; index < node.getChildCount(); index++) {
          AccessibilityNodeInfo child = node.getChild(index);
          if (child != null) pending.addLast(child);
        }
      }
    }
    return false;
  }
}
