/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.actor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Checks the exact, explicit-action-only fallback for Grab's booking label. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class GrabBookingClickTargetTest {
  private static final String PACKAGE = "com.grabtaxi.passenger";
  private static final String BASE = PACKAGE + ":id/";

  private static final class BookingTree {
    AccessibilityNodeInfo outer;
    AccessibilityNodeInfo row;
    AccessibilityNodeInfo button;
    AccessibilityNodeInfo label;
    AccessibilityNodeInfo grabNow;
  }

  @Test
  public void labelResolvesToBookingButtonNotToSiblingGrabNow() {
    BookingTree tree = bookingTree();
    assertBookingButton(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(tree.label)));
    assertBookingButton(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(tree.row)));
    assertBookingButton(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(tree.outer)));
    assertNull(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(tree.grabNow)));
  }

  @Test
  public void doesNotTargetOtherAppsOrLabelsOutsideGrabBookingRow() {
    BookingTree tree = bookingTree();
    AccessibilityNodeInfo wrongApp = create("transportBookButtonLabel", "Đặt xe", false);
    wrongApp.setPackageName("com.example.app");
    assertNull(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(wrongApp)));

    AccessibilityNodeInfo unrelatedLabel = create("transportBookButtonLabel", "Đặt xe", false);
    assertNull(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(unrelatedLabel)));
  }

  @Test
  public void refusesDisabledOrOffscreenBookingButton() {
    BookingTree disabled = bookingTree();
    disabled.button.setEnabled(false);
    assertNull(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(disabled.label)));

    BookingTree offscreen = bookingTree();
    offscreen.button.setVisibleToUser(false);
    assertNull(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(offscreen.label)));
  }

  @Test
  public void refusesUnrelatedLabelTextEvenWithinBookingRow() {
    BookingTree tree = bookingTree();
    tree.label.setText("GrabNow");
    assertNull(GrabBookingClickTarget.resolve(AccessibilityNodeInfoCompat.wrap(tree.label)));
  }

  private static void assertBookingButton(AccessibilityNodeInfoCompat result) {
    assertNotNull(result);
    assertEquals(BASE + "transportBookButton", result.getViewIdResourceName());
  }

  private static BookingTree bookingTree() {
    BookingTree tree = new BookingTree();
    tree.outer = create("bookButtonContainer", null, true);
    AccessibilityNodeInfo group = create(null, null, false);
    tree.row = create("transportBookButtonContainer", null, false);
    tree.button = create("transportBookButton", null, true);
    tree.label = create("transportBookButtonLabel", "Đặt xe", false);
    tree.grabNow = create("transportGrabNowVariantBButton", null, true);

    Shadows.shadowOf(tree.outer).addChild(group);
    Shadows.shadowOf(group).addChild(tree.grabNow);
    Shadows.shadowOf(group).addChild(tree.row);
    Shadows.shadowOf(tree.row).addChild(tree.button);
    Shadows.shadowOf(tree.row).addChild(tree.label);
    // Accessibility framework returns sealed node snapshots when queried by a screen reader.
    for (AccessibilityNodeInfo node :
        new AccessibilityNodeInfo[] {
          tree.outer, group, tree.row, tree.button, tree.label, tree.grabNow
        }) {
      ReflectionHelpers.callInstanceMethod(
          node, "setSealed", ClassParameter.from(boolean.class, true));
    }
    return tree;
  }

  private static AccessibilityNodeInfo create(String localId, String text, boolean clickable) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setPackageName(PACKAGE);
    if (localId != null) {
      node.setViewIdResourceName(BASE + localId);
    }
    if (text != null) {
      node.setText(text);
    }
    node.setEnabled(true);
    node.setClickable(clickable);
    node.setVisibleToUser(true);
    return node;
  }
}
