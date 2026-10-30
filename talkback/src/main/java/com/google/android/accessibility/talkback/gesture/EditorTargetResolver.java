package com.google.android.accessibility.talkback.gesture;

import android.graphics.Rect;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Role;
import java.util.ArrayDeque;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class EditorTargetResolver {
  private EditorTargetResolver() {}

  public static @Nullable AccessibilityNodeInfoCompat find(
      @Nullable AccessibilityNodeInfoCompat focused,
      @Nullable AccessibilityNodeInfoCompat keyboardEditor,
      @Nullable AccessibilityNodeInfoCompat input,
      @Nullable AccessibilityNodeInfoCompat root) {
    if (editable(focused)) {
      return focused;
    }
    if (editable(keyboardEditor)) {
      return keyboardEditor;
    }
    if (editable(input) && (root == null || input.getWindowId() == root.getWindowId())) {
      return input;
    }
    if (root == null) {
      return null;
    }
    Rect origin = new Rect();
    if (focused != null) {
      focused.getBoundsInScreen(origin);
    }
    AccessibilityNodeInfoCompat nearest = null;
    long bestDistance = Long.MAX_VALUE;
    ArrayDeque<AccessibilityNodeInfoCompat> nodes = new ArrayDeque<>();
    nodes.add(root);
    int scanned = 0;
    while (!nodes.isEmpty() && scanned++ < 4096) {
      AccessibilityNodeInfoCompat current = nodes.removeFirst();
      if (editable(current)) {
        Rect bounds = new Rect();
        current.getBoundsInScreen(bounds);
        long dx = (long) bounds.centerX() - origin.centerX();
        long dy = (long) bounds.centerY() - origin.centerY();
        long distance = focused == null ? 0 : dx * dx + dy * dy;
        if (nearest == null || distance < bestDistance) {
          nearest = current;
          bestDistance = distance;
        }
      }
      for (int i = 0; i < current.getChildCount(); i++) {
        AccessibilityNodeInfoCompat child = current.getChild(i);
        if (child != null) {
          nodes.addLast(child);
        }
      }
    }
    return nearest;
  }

  private static boolean editable(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null
        && Role.getRole(node) == Role.ROLE_EDIT_TEXT
        && node.isEditable()
        && node.isEnabled()
        && node.isVisibleToUser();
  }
}
