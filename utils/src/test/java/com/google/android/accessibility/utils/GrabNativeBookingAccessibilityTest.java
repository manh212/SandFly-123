package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Regression tests for reaching Grab's original booking button, without creating an overlay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class GrabNativeBookingAccessibilityTest {
  private static final String GRAB = "com.grabtaxi.passenger";
  private static final String PREFIX = GRAB + ":id/";

  @Test
  public void findsAndLabelsActualBookingButtonInGrabWindow() {
    Tree tree = makeTree("Đặt xe", true, true);
    AccessibilityNodeInfoCompat actual =
        RideAppAccessibility.findGrabBookingButton(AccessibilityNodeInfoCompat.wrap(tree.root));
    assertNotNull(actual);
    assertEquals(PREFIX + "transportBookButton", actual.getViewIdResourceName());
    assertEquals("Đặt xe", RideAppAccessibility.grabBookingButtonLabel(actual));
    assertTrue(RideAppAccessibility.isActionControl(actual));
  }

  @Test
  public void grabNowAndOptionsAreOnlyNavigationAnchorsNotBookingTargets() {
    Tree tree = makeTree("Đặt xe", true, true);
    assertTrue(RideAppAccessibility.precedesGrabBookingButton(
        AccessibilityNodeInfoCompat.wrap(tree.grabNow)));
    assertTrue(RideAppAccessibility.precedesGrabBookingButton(
        AccessibilityNodeInfoCompat.wrap(tree.option)));
    assertFalse(RideAppAccessibility.precedesGrabBookingButton(
        AccessibilityNodeInfoCompat.wrap(tree.booking)));
    assertNull(RideAppAccessibility.grabBookingButtonLabel(
        AccessibilityNodeInfoCompat.wrap(tree.grabNow)));
  }

  @Test
  public void refusesDifferentLabelAndDisabledOrHiddenButton() {
    assertNull(RideAppAccessibility.findGrabBookingButton(
        AccessibilityNodeInfoCompat.wrap(makeTree("GrabNow", true, true).root)));
    assertNull(RideAppAccessibility.findGrabBookingButton(
        AccessibilityNodeInfoCompat.wrap(makeTree("Đặt xe", false, true).root)));
    assertNull(RideAppAccessibility.findGrabBookingButton(
        AccessibilityNodeInfoCompat.wrap(makeTree("Đặt xe", true, false).root)));
  }

  @Test
  public void neverTargetsOtherApplication() {
    AccessibilityNodeInfo otherApp = node("transportBookButton", null, false);
    otherApp.setPackageName("com.example.app");
    otherApp.setEnabled(true);
    otherApp.setVisibleToUser(true);
    otherApp.addAction(AccessibilityNodeInfo.ACTION_CLICK);
    assertNull(RideAppAccessibility.grabBookingButtonLabel(
        AccessibilityNodeInfoCompat.wrap(otherApp)));
    assertFalse(RideAppAccessibility.precedesGrabBookingButton(
        AccessibilityNodeInfoCompat.wrap(otherApp)));
  }

  private static final class Tree {
    AccessibilityNodeInfo root;
    AccessibilityNodeInfo booking;
    AccessibilityNodeInfo grabNow;
    AccessibilityNodeInfo option;
  }

  private static Tree makeTree(String labelText, boolean enabled, boolean visible) {
    Tree tree = new Tree();
    tree.root = node("bookButtonContainer", null, true);
    tree.grabNow = node("transportGrabNowVariantBButton", null, true);
    tree.option = node("optionContainer", null, true);
    AccessibilityNodeInfo row = node("transportBookButtonContainer", null, false);
    tree.booking = node("transportBookButton", null, true);
    tree.booking.setEnabled(enabled);
    tree.booking.setVisibleToUser(visible);
    AccessibilityNodeInfo label = node("transportBookButtonLabel", labelText, false);
    shadowOf(tree.root).addChild(tree.grabNow);
    shadowOf(tree.root).addChild(tree.option);
    shadowOf(tree.root).addChild(row);
    shadowOf(row).addChild(tree.booking);
    shadowOf(row).addChild(label);
    for (AccessibilityNodeInfo node :
        new AccessibilityNodeInfo[] {
          tree.root, tree.grabNow, tree.option, row, tree.booking, label
        }) {
      ReflectionHelpers.callInstanceMethod(
          node, "setSealed", ClassParameter.from(boolean.class, true));
    }
    return tree;
  }

  private static AccessibilityNodeInfo node(String id, String text, boolean clickable) {
    AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain();
    result.setPackageName(GRAB);
    result.setViewIdResourceName(PREFIX + id);
    if (text != null) {
      result.setText(text);
    }
    result.setEnabled(true);
    result.setVisibleToUser(true);
    result.setFocusable(clickable);
    result.setClickable(clickable);
    if (clickable) {
      result.addAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    return result;
  }
}
