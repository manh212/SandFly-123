package com.google.android.accessibility.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

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
    AccessibilityNodeInfoCompat node = mock(AccessibilityNodeInfoCompat.class);
    when(node.getPackageName()).thenReturn(app);
    when(node.isEnabled()).thenReturn(true);
    when(node.isVisibleToUser()).thenReturn(true);
    when(node.getActionList()).thenReturn(Collections.singletonList(
        AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK));
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
      AccessibilityNodeInfoCompat icon = mock(AccessibilityNodeInfoCompat.class);
      when(icon.isVisibleToUser()).thenReturn(true);
      when(node.getChildCount()).thenReturn(1);
      when(node.getChild(0)).thenReturn(icon);
      assertTrue(RideAppAccessibility.isActionControl(node));
      when(node.isVisibleToUser()).thenReturn(false);
      assertFalse(RideAppAccessibility.isActionControl(node));
      when(node.isVisibleToUser()).thenReturn(true);
      when(node.isEnabled()).thenReturn(false);
      assertFalse(RideAppAccessibility.isActionControl(node));
    }
  }

  @Test
  public void parentDoesNotReplaceItsIndependentlyActionableButtons() {
    AccessibilityNodeInfoCompat parent = control("com.grabtaxi.passenger");
    when(parent.getChildCount()).thenReturn(1);
    when(parent.getChild(0)).thenReturn(control("com.grabtaxi.passenger"));
    assertFalse(RideAppAccessibility.isActionControl(parent));
  }

  @Test
  public void malformedCycleCannotHangNavigation() {
    AccessibilityNodeInfoCompat parent = control("xyz.be.customer");
    AccessibilityNodeInfoCompat child = mock(AccessibilityNodeInfoCompat.class);
    when(parent.getChildCount()).thenReturn(1);
    when(parent.getChild(0)).thenReturn(child);
    when(child.isVisibleToUser()).thenReturn(true);
    when(child.getChildCount()).thenReturn(1);
    when(child.getChild(0)).thenReturn(parent);
    assertFalse(RideAppAccessibility.isActionControl(parent));
  }
}
