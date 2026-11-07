package com.google.android.accessibility.utils;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.SystemClock;
import com.google.android.accessibility.utils.traversal.TraversalBudget;
import java.util.ArrayDeque;
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
        && !new DescendantSearch(GRAB.contentEquals(node.getPackageName())).hasActionable(node, 0);
  }

  private static final String GRAB = "com.grabtaxi.passenger";
  private static final String BOOK_BUTTON = GRAB + ":id/transportBookButton";
  private static final String BOOK_ROW = GRAB + ":id/transportBookButtonContainer";
  private static final String BOOK_LABEL = GRAB + ":id/transportBookButtonLabel";

  /**
   * The real Grab booking button has no accessible text; its "Đặt xe" label is a sibling.
   * Only accept the original app button, never GrabNow, a disabled action, or an offscreen view.
   */
  public static @Nullable CharSequence grabBookingButtonLabel(
      @Nullable AccessibilityNodeInfoCompat node) {
    if (node == null
        || !GRAB.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
        || !BOOK_BUTTON.equals(node.getViewIdResourceName())
        || !node.isEnabled() || !node.isVisibleToUser()
        || !AccessibilityNodeInfoUtils.supportsAction(
            node, AccessibilityNodeInfoCompat.ACTION_CLICK)) {
      return null;
    }
    AccessibilityNodeInfoCompat row = node.getParent();
    if (row == null || !BOOK_ROW.equals(row.getViewIdResourceName())) {
      return null;
    }
    for (int index = 0; index < row.getChildCount(); index++) {
      AccessibilityNodeInfoCompat sibling = row.getChild(index);
      if (sibling != null
          && BOOK_LABEL.equals(sibling.getViewIdResourceName())
          && sibling.isVisibleToUser()
          && sibling.getText() != null
          && "Đặt xe".contentEquals(sibling.getText().toString().trim())) {
        return sibling.getText();
      }
    }
    return null;
  }

  /** A nearby native Grab control from which the next swipe should reach the original button. */
  public static boolean precedesGrabBookingButton(@Nullable AccessibilityNodeInfoCompat pivot) {
    if (pivot == null
        || !GRAB.contentEquals(pivot.getPackageName() == null ? "" : pivot.getPackageName())) {
      return false;
    }
    String id = pivot.getViewIdResourceName();
    return (GRAB + ":id/transportGrabNowVariantBButton").equals(id)
        || (GRAB + ":id/optionContainer").equals(id)
        || (GRAB + ":id/transportBookButtonContainer").equals(id)
        || (GRAB + ":id/bookButtonContainer").equals(id);
  }

  /** Finds only the real native button; this never creates or activates another view. */
  public static @Nullable AccessibilityNodeInfoCompat findGrabBookingButton(
      @Nullable AccessibilityNodeInfoCompat root) {
    if (root == null
        || !GRAB.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
      return null;
    }
    for (AccessibilityNodeInfoCompat candidate : root.findAccessibilityNodeInfosByViewId(BOOK_BUTTON)) {
      if (grabBookingButtonLabel(candidate) != null) {
        return candidate;
      }
    }
    ArrayDeque<AccessibilityNodeInfoCompat> queue = new ArrayDeque<>();
    queue.add(root);
    long deadline = SystemClock.uptimeMillis() + 24;
    int queries = 0;
    while (!queue.isEmpty() && SystemClock.uptimeMillis() < deadline) {
      AccessibilityNodeInfoCompat node = queue.removeFirst();
      if (BOOK_BUTTON.equals(node.getViewIdResourceName())
          && grabBookingButtonLabel(node) != null) {
        return node;
      }
      for (int index = 0;
          index < node.getChildCount() && queries < 64 && SystemClock.uptimeMillis() < deadline;
          index++, queries++) {
        AccessibilityNodeInfoCompat child = node.getChild(index);
        if (child != null && child.isVisibleToUser()) {
          queue.addLast(child);
        }
      }
    }
    return null;
  }

  private static final class DescendantSearch {
    private final Set<AccessibilityNodeInfoCompat> visited = new HashSet<>();
    private final long deadline;
    private int queriesRemaining;

    DescendantSearch(boolean bounded) {
      deadline = bounded ? SystemClock.uptimeMillis() + 16 : Long.MAX_VALUE;
      queriesRemaining = bounded ? 32 : Integer.MAX_VALUE;
    }

    boolean hasActionable(AccessibilityNodeInfoCompat node, int depth) {
      if (!visited.add(node) || depth >= 12 || SystemClock.uptimeMillis() >= deadline) {
        return true;
      }
      int count = node.getChildCount();
      for (int index = 0; index < count; index++) {
        TraversalBudget.check();
        if (queriesRemaining-- <= 0 || SystemClock.uptimeMillis() >= deadline) {
          return true;
        }
        AccessibilityNodeInfoCompat child = node.getChild(index);
        if (child != null && child.isVisibleToUser()
            && (AccessibilityNodeInfoUtils.supportsAction(
                    child, AccessibilityNodeInfoCompat.ACTION_CLICK)
                || hasActionable(child, depth + 1))) {
          return true;
        }
      }
      return SystemClock.uptimeMillis() >= deadline;
    }
  }
}
