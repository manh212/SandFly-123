package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class OffscreenContentTest {
  @Test
  public void findsNearestOffscreenNodeInBothDirections() {
    AccessibilityNodeInfo root = node(null, true);
    AccessibilityNodeInfo before = node("before", false);
    AccessibilityNodeInfo group = node(null, true);
    AccessibilityNodeInfo pivot = node("pivot", true);
    AccessibilityNodeInfo after = node("after", false);
    AccessibilityNodeInfo later = node("later", false);
    addChildren(group, pivot, after);
    addChildren(root, before, group, later);
    seal(root, before, group, pivot, after, later);

    assertEquals(wrap(after), OffscreenContent.findNext(wrap(root), wrap(pivot), true));
    assertEquals(wrap(before), OffscreenContent.findNext(wrap(root), wrap(pivot), false));
  }

  @Test
  public void skipsDescendantsOfPivotAndNestedScrollableContainers() {
    AccessibilityNodeInfo root = node(null, true);
    AccessibilityNodeInfo pivot = node("pivot", true);
    AccessibilityNodeInfo pivotChild = node("pivot child", false);
    AccessibilityNodeInfo nested = node(null, true);
    nested.setScrollable(true);
    AccessibilityNodeInfo nestedChild = node("nested child", false);
    AccessibilityNodeInfo next = node("next", false);
    addChildren(pivot, pivotChild);
    addChildren(nested, nestedChild);
    addChildren(root, pivot, nested, next);
    seal(root, pivot, pivotChild, nested, nestedChild, next);

    assertEquals(wrap(next), OffscreenContent.findNext(wrap(root), wrap(pivot), true));
  }

  @Test
  public void givesUpWhenLocatingPivotExceedsQueryBudget() {
    AccessibilityNodeInfo root = node(null, true);
    for (int i = 0; i < 60; i++) {
      AccessibilityNodeInfo filler = node(null, true);
      addChildren(root, filler);
      seal(filler);
    }
    AccessibilityNodeInfo pivot = node("pivot", true);
    AccessibilityNodeInfo next = node("next", false);
    addChildren(root, pivot, next);
    seal(root, pivot, next);

    assertNull(OffscreenContent.findNext(wrap(root), wrap(pivot), true));
  }

  private static AccessibilityNodeInfo node(String text, boolean visible) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setText(text);
    node.setVisibleToUser(visible);
    node.setBoundsInParent(new Rect(0, 0, 100, 40));
    node.setBoundsInScreen(new Rect(0, 0, 100, 40));
    return node;
  }

  private static void addChildren(
      AccessibilityNodeInfo parent, AccessibilityNodeInfo... children) {
    for (AccessibilityNodeInfo child : children) {
      shadowOf(parent).addChild(child);
    }
  }

  private static void seal(AccessibilityNodeInfo... nodes) {
    for (AccessibilityNodeInfo node : nodes) {
      ReflectionHelpers.callInstanceMethod(
          node, "setSealed", ClassParameter.from(boolean.class, true));
    }
  }

  private static AccessibilityNodeInfoCompat wrap(AccessibilityNodeInfo node) {
    return AccessibilityNodeInfoCompat.wrap(node);
  }
}
