package com.google.android.accessibility.utils;

import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public final class WindowCachePolicy {
  private WindowCachePolicy() {}

  public static boolean affectsOrder(AccessibilityEvent event, int cachedWindowId) {
    int windowId = event.getWindowId();
    if (windowId < 0 || cachedWindowId < 0) return true;
    if (windowId != cachedWindowId) return false;
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true;
    int structural = AccessibilityEvent.WINDOWS_CHANGE_ADDED
        | AccessibilityEvent.WINDOWS_CHANGE_REMOVED | AccessibilityEvent.WINDOWS_CHANGE_BOUNDS
        | AccessibilityEvent.WINDOWS_CHANGE_PARENT | AccessibilityEvent.WINDOWS_CHANGE_CHILDREN;
    return event.getWindowChanges() == 0 || (event.getWindowChanges() & structural) != 0;
  }

  public static boolean affectsRoot(AccessibilityEvent event, int cachedWindowId) {
    if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true;
    if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      int active = AccessibilityEvent.WINDOWS_CHANGE_ACTIVE | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED;
      if ((event.getWindowChanges() & active) != 0) return true;
    }
    return affectsOrder(event, cachedWindowId);
  }

  public static boolean isNonStructuralContent(AccessibilityEvent event) {
    int allowed = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        | AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION
        | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION;
    return event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        && event.getContentChangeTypes() != 0
        && (event.getContentChangeTypes() & ~allowed) == 0;
  }
}
