/*
 * Copyright (C) 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.gesture;

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.Role;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Helpers for extracting readable content from the currently focused accessibility node. */
public final class CopyTextUtils {

  private CopyTextUtils() {}

  /** Returns distinct, readable text in the focused node and its descendants, in tree order. */
  public static List<String> getTextItems(@Nullable AccessibilityNodeInfoCompat node) {
    LinkedHashSet<String> items = new LinkedHashSet<>();
    collectText(node, items);
    return new ArrayList<>(items);
  }

  /** Returns the node's own plain text or content description, excluding passwords and controls. */
  public static String getNodeText(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || node.isPassword() || isControl(node)) {
      return "";
    }

    CharSequence text = node.getText();
    if (TextUtils.isEmpty(text)) {
      text = node.getContentDescription();
    }
    return TextUtils.isEmpty(text) ? "" : text.toString().trim();
  }

  /** Whether a node represents a control that should not be copied as page content. */
  public static boolean isControl(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return false;
    }
    return switch (Role.getRole(node)) {
      case Role.ROLE_BUTTON,
          Role.ROLE_IMAGE_BUTTON,
          Role.ROLE_TOGGLE_BUTTON,
          Role.ROLE_CHECK_BOX,
          Role.ROLE_RADIO_BUTTON,
          Role.ROLE_SWITCH,
          Role.ROLE_DROP_DOWN_LIST,
          Role.ROLE_SEEK_CONTROL,
          Role.ROLE_FLOATING_ACTION_BUTTON,
          Role.ROLE_VOICE_DICTATION_BUTTON -> true;
      default -> false;
    };
  }

  private static void collectText(
      @Nullable AccessibilityNodeInfoCompat node, LinkedHashSet<String> items) {
    if (node == null || node.isPassword() || isControl(node)) {
      return;
    }

    String value = getNodeText(node);
    if (!value.isEmpty()) {
      items.add(value);
    }

    for (int i = 0; i < node.getChildCount(); i++) {
      collectText(node.getChild(i), items);
    }
  }
}
