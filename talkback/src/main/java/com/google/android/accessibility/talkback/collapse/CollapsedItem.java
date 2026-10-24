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

import androidx.annotation.StringRes;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** The result of matching one list item against a {@link CollapseScript}. */
public final class CollapsedItem {

  /** One synthesized action of a collapsed item. */
  public static final class ChildAction {

    /** How the action behaves when the user activates it. */
    public enum Kind {
      /** Clicks (or long-clicks) {@link #node} directly. */
      CLICK,
      /**
       * One item of the item's own overflow menu: {@link #node} is the menu button, and
       * activation runs the automated sequence in {@link MenuAutomation} instead of a plain
       * click.
       */
      MENU_ITEM,
      /** Opens the item's overflow menu as usual: a plain click on {@link #node}. */
      MENU_OPEN
    }

    public final AccessibilityNodeInfoCompat node;
    public final boolean longClick;
    public final CharSequence label;
    public final Kind kind;
    /** Title resource for {@link Kind#MENU_ITEM} and {@link Kind#MENU_OPEN}; 0 for plain clicks. */
    public final @StringRes int labelResId;
    /** Sheet signature for {@link Kind#MENU_ITEM}: view id of the label view in a menu row. */
    public final @Nullable String sheetItemLabelViewId;

    public ChildAction(AccessibilityNodeInfoCompat node, boolean longClick, CharSequence label) {
      this.node = node;
      this.longClick = longClick;
      this.label = label;
      this.kind = Kind.CLICK;
      this.labelResId = 0;
      this.sheetItemLabelViewId = null;
    }

    private ChildAction(
        AccessibilityNodeInfoCompat node,
        Kind kind,
        @StringRes int labelResId,
        @Nullable String sheetItemLabelViewId) {
      this.node = node;
      this.longClick = false;
      this.label = "";
      this.kind = kind;
      this.labelResId = labelResId;
      this.sheetItemLabelViewId = sheetItemLabelViewId;
    }

    static ChildAction menuItem(
        AccessibilityNodeInfoCompat menuButton,
        @StringRes int labelResId,
        String sheetItemLabelViewId) {
      return new ChildAction(menuButton, Kind.MENU_ITEM, labelResId, sheetItemLabelViewId);
    }

    static ChildAction menuOpen(AccessibilityNodeInfoCompat menuButton, @StringRes int labelResId) {
      return new ChildAction(menuButton, Kind.MENU_OPEN, labelResId, /* sheetItemLabelViewId= */ null);
    }
  }

  private final AccessibilityNodeInfoCompat itemRoot;
  private final AccessibilityNodeInfoCompat representative;
  private final List<ChildAction> actions;
  private final @Nullable CharSequence composedLabel;
  private final @Nullable AccessibilityNodeInfoCompat activationTarget;
  private final @Nullable AccessibilityNodeInfoCompat selectionTarget;

  CollapsedItem(
      AccessibilityNodeInfoCompat itemRoot,
      AccessibilityNodeInfoCompat representative,
      List<ChildAction> actions,
      @Nullable CharSequence composedLabel,
      @Nullable AccessibilityNodeInfoCompat activationTarget,
      @Nullable AccessibilityNodeInfoCompat selectionTarget) {
    this.itemRoot = itemRoot;
    this.representative = representative;
    this.actions = actions;
    this.composedLabel = composedLabel;
    this.activationTarget = activationTarget;
    this.selectionTarget = selectionTarget;
  }

  public AccessibilityNodeInfoCompat itemRoot() {
    return itemRoot;
  }

  /** The single focus stop of the collapsed item. */
  public AccessibilityNodeInfoCompat representative() {
    return representative;
  }

  public List<ChildAction> actions() {
    return actions;
  }

  /** Label composed from content fields (composite items), or null to use the node's own. */
  public @Nullable CharSequence composedLabel() {
    return composedLabel;
  }

  /** Node that should receive the click instead of the representative, or null for the default. */
  public @Nullable AccessibilityNodeInfoCompat activationTarget() {
    return activationTarget;
  }

  /** Node that text selection should target instead of the representative, or null. */
  public @Nullable AccessibilityNodeInfoCompat selectionTarget() {
    return selectionTarget;
  }

  /** Whether the node is the item root or one of its descendants. */
  public boolean contains(AccessibilityNodeInfoCompat node) {
    return itemRoot.equals(node) || AccessibilityNodeInfoUtils.hasAncestor(node, itemRoot);
  }
}
