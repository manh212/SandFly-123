/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.collapse;

import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Role;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Facebook comments: the comment's outer frame is clickable and focusable but has no label of
 * its own; the content is spread over child nodes (avatar, author, time, body) and every action
 * (reply, reactions, like, demote) is a child button. The frame stays the single stop, the label
 * is composed as author, time, body, and the child buttons become actions.
 *
 * <p>Feed posts intentionally do not match: their root is not clickable and one child already
 * carries the full description plus Facebook's own custom actions.
 */
final class FacebookCollapseScript extends CollapseScript {
  static final String PACKAGE_NAME = "com.facebook.katana";
  private static final String FIELD_AUTHOR = "author";
  private static final String FIELD_TIME = "time";
  private static final String FIELD_BODY = "body";

  FacebookCollapseScript() {
    super(PACKAGE_NAME, Pattern.COMPOSITE_ROOT, /* maxActions= */ 8);
  }

  @Override
  public @Nullable String selectionFieldId() {
    return FIELD_BODY;
  }

  @Override
  protected @Nullable List<Field> resolveFields(ItemScan scan) {
    AccessibilityNodeInfoCompat body = bodyNode(scan);
    AccessibilityNodeInfoCompat time = timeNode(scan, body);
    AccessibilityNodeInfoCompat author = authorNode(scan, body);
    if (body == null || time == null || author == null) {
      return null;
    }
    List<Field> fields = new ArrayList<>();
    fields.add(new Field(FIELD_AUTHOR, author, ItemScan.spokenLabel(author)));
    fields.add(new Field(FIELD_TIME, time, ItemScan.spokenLabel(time)));
    fields.add(new Field(FIELD_BODY, body, body.getText()));
    return fields;
  }

  @Override
  protected CharSequence actionLabel(
      AccessibilityNodeInfoCompat node, CharSequence spokenLabel) {
    // Some buttons carry a long instruction-style description ("Like button. Double-tap and
    // hold...") next to a short text ("Like"); the short text is the usable action name.
    CharSequence text = node.getText();
    CharSequence description = node.getContentDescription();
    if (!TextUtils.isEmpty(text)
        && !TextUtils.isEmpty(description)
        && text.length() < description.length()) {
      return text;
    }
    return spokenLabel;
  }

  @Override
  protected boolean longClickTwin(AccessibilityNodeInfoCompat node, ItemScan scan) {
    // Long-pressing a bottom-row button (Like) opens the reaction picker; the body itself is
    // excluded so its own long-press behaviour is not shadowed.
    if (!node.isLongClickable()) {
      return false;
    }
    AccessibilityNodeInfoCompat body = bodyNode(scan);
    return body != null && !node.equals(body) && scan.indexOf(node) > scan.indexOf(body);
  }

  /** The comment body: the clickable node carrying the longest text in the item. */
  private static @Nullable AccessibilityNodeInfoCompat bodyNode(ItemScan scan) {
    AccessibilityNodeInfoCompat body = null;
    int longest = 0;
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      CharSequence text = node.getText();
      if (!TextUtils.isEmpty(text) && text.length() > longest) {
        longest = text.length();
        body = node;
      }
    }
    return body;
  }

  /** The timestamp: a focusable, non-clickable, labelled node before the body. */
  private static @Nullable AccessibilityNodeInfoCompat timeNode(
      ItemScan scan, @Nullable AccessibilityNodeInfoCompat body) {
    if (body == null) {
      return null;
    }
    int bodyIndex = scan.indexOf(body);
    List<AccessibilityNodeInfoCompat> descendants = scan.descendants();
    for (int i = 0; i < bodyIndex && i < descendants.size(); i++) {
      AccessibilityNodeInfoCompat node = descendants.get(i);
      if (node.isFocusable()
          && !node.isClickable()
          && !TextUtils.isEmpty(ItemScan.spokenLabel(node))
          && !AccessibilityNodeInfoUtils.hasAncestor(body, node)) {
        return node;
      }
    }
    return null;
  }

  /** The author name: the first text-carrying clickable before the body, skipping images. */
  private static @Nullable AccessibilityNodeInfoCompat authorNode(
      ItemScan scan, @Nullable AccessibilityNodeInfoCompat body) {
    if (body == null) {
      return null;
    }
    int bodyIndex = scan.indexOf(body);
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if (scan.indexOf(node) >= bodyIndex) {
        break;
      }
      if (Role.getRole(node) != Role.ROLE_IMAGE_BUTTON && !TextUtils.isEmpty(node.getText())) {
        return node;
      }
    }
    return null;
  }
}
