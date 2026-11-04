package com.google.android.accessibility.utils;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.accessibilityservice.AccessibilityServiceInfo;
import java.util.HashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class RideAppAccessibility {
  private RideAppAccessibility() {}

  public static boolean isSupported(@Nullable CharSequence packageName) {
    return "xyz.be.customer".contentEquals(packageName == null ? "" : packageName)
        || "com.grabtaxi.passenger".contentEquals(packageName == null ? "" : packageName);
  }

  public static int serviceFlags(int flags, @Nullable CharSequence packageName) {
    int include = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
    return isSupported(packageName) ? flags | include : flags & ~include;
  }

  public static boolean isActionControl(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && isSupported(node.getPackageName()) && node.isEnabled()
        && node.isVisibleToUser()
        && AccessibilityNodeInfoUtils.supportsAction(node, AccessibilityNodeInfoCompat.ACTION_CLICK)
        && !hasActionableDescendant(node, new HashSet<>(), 0);
  }

  private static boolean hasActionableDescendant(
      AccessibilityNodeInfoCompat node, Set<AccessibilityNodeInfoCompat> visited, int depth) {
    if (!visited.add(node) || depth >= 12) return true;
    for (int index = 0; index < node.getChildCount(); index++) {
      AccessibilityNodeInfoCompat child = node.getChild(index);
      if (child == null || !child.isVisibleToUser()) continue;
      if (AccessibilityNodeInfoUtils.supportsAction(child, AccessibilityNodeInfoCompat.ACTION_CLICK)
          || hasActionableDescendant(child, visited, depth + 1)) return true;
    }
    return false;
  }
}
