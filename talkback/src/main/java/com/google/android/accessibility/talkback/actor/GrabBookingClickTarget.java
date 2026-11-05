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

import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/**
 * Resolves Grab's booking label to its actual clickable control.
 *
 * <p>In Grab's transport confirmation layout the visible "Đặt xe" text is a sibling of
 * transportBookButton, not its descendant. This class only resolves an existing target in the
 * same booking container. It never performs a click: activation still requires a user action.
 */
final class GrabBookingClickTarget {
  private static final String PACKAGE = "com.grabtaxi.passenger";
  private static final String BOOKING = PACKAGE + ":id/bookButtonContainer";
  private static final String BOOKING_ROW = PACKAGE + ":id/transportBookButtonContainer";
  private static final String BOOK_BUTTON = PACKAGE + ":id/transportBookButton";
  private static final String BOOK_LABEL = PACKAGE + ":id/transportBookButtonLabel";
  private static final String LABEL_TEXT = "Đặt xe";

  private GrabBookingClickTarget() {}

  /** Returns a matching clickable booking button or null to leave normal click behavior intact. */
  static @Nullable AccessibilityNodeInfoCompat resolve(
      @Nullable AccessibilityNodeInfoCompat focused) {
    if (!isGrabNode(focused)) {
      return null;
    }

    String focusedId = focused.getViewIdResourceName();
    AccessibilityNodeInfoCompat row;
    if (BOOK_LABEL.equals(focusedId)) {
      // Only interpret this exact label, located beside the booking button.
      row = focused.getParent();
      if (!hasId(row, BOOKING_ROW)) {
        return null;
      }
    } else if (BOOKING_ROW.equals(focusedId)) {
      row = focused;
    } else if (BOOKING.equals(focusedId)) {
      // Grab nests the booking row in another ViewGroup below the button container.
      row = findDescendantWithId(focused, BOOKING_ROW, 3);
    } else {
      return null;
    }

    if (!isGrabNode(row) || !row.isVisibleToUser()) {
      return null;
    }

    AccessibilityNodeInfoCompat label = immediateChildWithId(row, BOOK_LABEL);
    AccessibilityNodeInfoCompat button = immediateChildWithId(row, BOOK_BUTTON);
    if (!isGrabNode(label) || !label.isVisibleToUser()
        || label.getText() == null
        || !LABEL_TEXT.equals(label.getText().toString().trim())) {
      return null;
    }

    // Do not redirect to GrabNow, an off-screen target, or a disabled control.
    if (!isGrabNode(button) || !button.isEnabled() || !button.isVisibleToUser()
        || !button.isClickable()) {
      return null;
    }
    return button;
  }

  private static boolean isGrabNode(@Nullable AccessibilityNodeInfoCompat node) {
    return node != null && node.getPackageName() != null
        && TextUtils.equals(PACKAGE, node.getPackageName());
  }

  private static boolean hasId(@Nullable AccessibilityNodeInfoCompat node, String id) {
    return node != null && id.equals(node.getViewIdResourceName());
  }

  private static @Nullable AccessibilityNodeInfoCompat immediateChildWithId(
      AccessibilityNodeInfoCompat parent, String id) {
    for (int index = 0; index < parent.getChildCount(); index++) {
      AccessibilityNodeInfoCompat child = parent.getChild(index);
      if (hasId(child, id)) {
        return child;
      }
    }
    return null;
  }

  private static @Nullable AccessibilityNodeInfoCompat findDescendantWithId(
      AccessibilityNodeInfoCompat root, String id, int remainingDepth) {
    if (hasId(root, id)) {
      return root;
    }
    if (remainingDepth <= 0) {
      return null;
    }
    for (int index = 0; index < root.getChildCount(); index++) {
      AccessibilityNodeInfoCompat child = root.getChild(index);
      if (!isGrabNode(child)) {
        continue;
      }
      AccessibilityNodeInfoCompat match = findDescendantWithId(child, id, remainingDepth - 1);
      if (match != null) {
        return match;
      }
    }
    return null;
  }
}
