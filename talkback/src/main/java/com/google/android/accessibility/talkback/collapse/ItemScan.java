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
 * A bounded pre-order scan of one candidate item's subtree, shared by signature matching and by
 * the per-app field selectors. If the subtree exceeds the scan limits the scan fails and the item
 * is left untouched.
 */
public final class ItemScan {
  private static final int MAX_DEPTH = 12;
  private static final int MAX_NODES = 250;

  private final AccessibilityNodeInfoCompat itemRoot;
  private final List<AccessibilityNodeInfoCompat> descendants;
  private final List<AccessibilityNodeInfoCompat> clickables;
  private final boolean hasEssentialDescendant;
  private final boolean hasScrollableDescendant;

  private ItemScan(
      AccessibilityNodeInfoCompat itemRoot,
      List<AccessibilityNodeInfoCompat> descendants,
      List<AccessibilityNodeInfoCompat> clickables,
      boolean hasEssentialDescendant,
      boolean hasScrollableDescendant) {
    this.itemRoot = itemRoot;
    this.descendants = descendants;
    this.clickables = clickables;
    this.hasEssentialDescendant = hasEssentialDescendant;
    this.hasScrollableDescendant = hasScrollableDescendant;
  }

  static @Nullable ItemScan of(AccessibilityNodeInfoCompat itemRoot) {
    List<AccessibilityNodeInfoCompat> descendants = new ArrayList<>();
    if (!collect(itemRoot, /* depth= */ 1, descendants)) {
      return null;
    }
    List<AccessibilityNodeInfoCompat> clickables = new ArrayList<>();
    boolean hasEssential = false;
    boolean hasScrollable = false;
    for (AccessibilityNodeInfoCompat node : descendants) {
      if (node.isClickable() && node.isVisibleToUser()) {
        clickables.add(node);
      }
      int role = Role.getRole(node);
      if (role == Role.ROLE_EDIT_TEXT
          || role == Role.ROLE_SEEK_CONTROL
          || node.isCheckable()
          || AccessibilityNodeInfoUtils.isTextSelectable(node)) {
        hasEssential = true;
      }
      if (node.isScrollable()) {
        hasScrollable = true;
      }
    }
    return new ItemScan(itemRoot, descendants, clickables, hasEssential, hasScrollable);
  }

  private static boolean collect(
      AccessibilityNodeInfoCompat parent, int depth, List<AccessibilityNodeInfoCompat> out) {
    if (depth > MAX_DEPTH) {
      return false;
    }
    for (int i = 0; i < parent.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = parent.getChild(i);
      if (child == null) {
        continue;
      }
      out.add(child);
      if (out.size() > MAX_NODES) {
        return false;
      }
      if (!collect(child, depth + 1, out)) {
        return false;
      }
    }
    return true;
  }

  public AccessibilityNodeInfoCompat itemRoot() {
    return itemRoot;
  }

  /** All descendants in reading (pre-order) order, excluding the item root itself. */
  public List<AccessibilityNodeInfoCompat> descendants() {
    return descendants;
  }

  /** Clickable, visible descendants in reading order. */
  public List<AccessibilityNodeInfoCompat> clickables() {
    return clickables;
  }

  public boolean hasEssentialDescendant() {
    return hasEssentialDescendant;
  }

  public boolean hasScrollableDescendant() {
    return hasScrollableDescendant;
  }

  /** Reading-order index of a node within {@link #descendants()}, or -1. */
  public int indexOf(AccessibilityNodeInfoCompat node) {
    for (int i = 0; i < descendants.size(); i++) {
      if (descendants.get(i).equals(node)) {
        return i;
      }
    }
    return -1;
  }

  /** The label TalkBack would speak for a node: its content description, else its text. */
  public static CharSequence spokenLabel(AccessibilityNodeInfoCompat node) {
    CharSequence description = node.getContentDescription();
    if (!TextUtils.isEmpty(description)) {
      return description;
    }
    CharSequence text = node.getText();
    return text == null ? "" : text;
  }
}
