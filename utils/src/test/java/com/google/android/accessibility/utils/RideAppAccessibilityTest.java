package com.google.android.accessibility.utils;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RideAppAccessibilityTest {
  private AccessibilityNodeInfoCompat control(String app) {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain());
    node.setPackageName(app);
    node.setClassName("android.widget.Button");
    node.setEnabled(true);
    node.setVisibleToUser(true);
    node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
    return node;
  }

  @Test
  public void includeUnimportantViewsOnlyInPassengerAppsAndPreserveOtherFlags() {
    int original = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
    int included = RideAppAccessibility.serviceFlags(original, "xyz.be.customer");
    assertTrue((included & android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0);
    assertEquals(original, RideAppAccessibility.serviceFlags(included, "com.example.app"));
  }

  @Test
  public void supportsOnlyPassengerApps() {
    assertTrue(RideAppAccessibility.isSupported("xyz.be.customer"));
    assertTrue(RideAppAccessibility.isSupported("com.grabtaxi.passenger"));
    assertFalse(RideAppAccessibility.isSupported("com.grabtaxi.driver2"));
    assertFalse(RideAppAccessibility.isSupported(null));
    assertFalse(RideAppAccessibility.isActionControl(control("com.example.app")));
  }

  @Test
  public void visibleEnabledActionRemainsFocusableWithDecorativeChildren() {
    for (String app : new String[] {"xyz.be.customer", "com.grabtaxi.passenger"}) {
      AccessibilityNodeInfoCompat node = control(app);
      AccessibilityNodeInfoCompat icon = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain());
      icon.setVisibleToUser(true);
      shadowOf(node.unwrap()).addChild(icon.unwrap());
      assertTrue(RideAppAccessibility.isActionControl(node));
      node.setVisibleToUser(false);
      assertFalse(RideAppAccessibility.isActionControl(node));
      node.setVisibleToUser(true);
      node.setEnabled(false);
      assertFalse(RideAppAccessibility.isActionControl(node));
    }
  }

  @Test
  public void parentDoesNotReplaceItsIndependentlyActionableButtons() {
    AccessibilityNodeInfoCompat parent = control("com.grabtaxi.passenger");
    shadowOf(parent.unwrap()).addChild(control("com.grabtaxi.passenger").unwrap());
    assertFalse(RideAppAccessibility.isActionControl(parent));
  }

  @Test
  public void malformedCycleCannotHangNavigation() {
    AccessibilityNodeInfoCompat parent = control("xyz.be.customer");
    AccessibilityNodeInfoCompat child = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain());
    child.setVisibleToUser(true);
    shadowOf(parent.unwrap()).addChild(child.unwrap());
    shadowOf(child.unwrap()).addChild(parent.unwrap());
    assertFalse(RideAppAccessibility.isActionControl(parent));
  }
}
