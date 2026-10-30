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
      MENU_OPEN,
      /**
       * One button of the item's own action tray: {@link #node} is the absorbed in-item
       * counterpart the tray set replaced, and activation runs the tray sequence in {@link
       * MenuAutomation} — a long press opens the tray, then the tray's real button named by
       * {@link #trayLabels} is clicked — instead of a plain click on the node.
       */
      TRAY_ITEM
    }

    public final AccessibilityNodeInfoCompat node;
    public final boolean longClick;
    public final CharSequence label;
    public final Kind kind;
    /** Title resource for {@link Kind#MENU_ITEM} and {@link Kind#MENU_OPEN}; 0 for plain clicks. */
    public final @StringRes int labelResId;
    /** Sheet signature for {@link Kind#MENU_ITEM}: view id of the label view in a menu row. */
    public final @Nullable String sheetItemLabelViewId;
    /** Toggle {@link Kind#MENU_ITEM} only: the recorded state variants; null for plain items. */
    public final @Nullable String[] menuStateVariants;
    /** Toggle {@link Kind#MENU_ITEM} only: the stable match cores; null for plain items. */
    public final @Nullable String[] menuStateCores;
    public final @Nullable String sheetContainerViewId;
    public final boolean menuRestoreFocus;
    /**
     * {@link Kind#TRAY_ITEM} only: the tray button's recorded labels, Vietnamese then
     * English; the sequence clicks the tray button carrying either. Null otherwise.
     */
    public final @Nullable String[] trayLabels;
    /**
     * {@link Kind#TRAY_ITEM} only: whether the tray sequence restores focus to the item when
     * the tray closes, or hands over to the app's follow-up UI.
     */
    public final boolean trayRestoreFocus;

    public ChildAction(AccessibilityNodeInfoCompat node, boolean longClick, CharSequence label) {
      this.node = node;
      this.longClick = longClick;
      this.label = label;
      this.kind = Kind.CLICK;
      this.labelResId = 0;
      this.sheetItemLabelViewId = null;
      this.menuStateVariants = null;
      this.menuStateCores = null;
      this.sheetContainerViewId = null;
      this.menuRestoreFocus = true;
      this.trayLabels = null;
      this.trayRestoreFocus = false;
    }

    private ChildAction(
        AccessibilityNodeInfoCompat node,
        Kind kind,
        @StringRes int labelResId,
        @Nullable String sheetItemLabelViewId,
        @Nullable String[] menuStateVariants,
        @Nullable String[] menuStateCores,
        @Nullable String sheetContainerViewId,
        boolean longClick,
        boolean menuRestoreFocus) {
      this.node = node;
      this.longClick = longClick;
      this.label = "";
      this.kind = kind;
      this.labelResId = labelResId;
      this.sheetItemLabelViewId = sheetItemLabelViewId;
      this.menuStateVariants = menuStateVariants;
      this.menuStateCores = menuStateCores;
      this.sheetContainerViewId = sheetContainerViewId;
      this.menuRestoreFocus = menuRestoreFocus;
      this.trayLabels = null;
      this.trayRestoreFocus = false;
    }

    private ChildAction(
        AccessibilityNodeInfoCompat node,
        CharSequence label,
        @Nullable String[] trayLabels,
        boolean trayRestoreFocus) {
      this.node = node;
      this.longClick = false;
      this.label = label;
      this.kind = Kind.TRAY_ITEM;
      this.labelResId = 0;
      this.sheetItemLabelViewId = null;
      this.menuStateVariants = null;
      this.menuStateCores = null;
      this.sheetContainerViewId = null;
      this.menuRestoreFocus = true;
      this.trayLabels = trayLabels;
      this.trayRestoreFocus = trayRestoreFocus;
    }

    static ChildAction trayItem(
        AccessibilityNodeInfoCompat node,
        CharSequence label,
        String[] trayLabels,
        boolean trayRestoreFocus) {
      return new ChildAction(node, label, trayLabels, trayRestoreFocus);
    }

    static ChildAction menuItem(
        AccessibilityNodeInfoCompat menuButton,
        CollapseScript.MenuCatalog.Item item,
        CollapseScript.MenuCatalog catalog) {
      return new ChildAction(
          menuButton,
          Kind.MENU_ITEM,
          item.labelResId,
          catalog.sheetItemLabelViewId,
          item.stateVariants,
          item.stateCores,
          catalog.sheetContainerViewId,
          catalog.longClick,
          item.restoreFocusOnClose);
    }

    static ChildAction menuOpen(AccessibilityNodeInfoCompat menuButton, @StringRes int labelResId) {
      return new ChildAction(
          menuButton,
          Kind.MENU_OPEN,
          labelResId,
          /* sheetItemLabelViewId= */ null,
          /* menuStateVariants= */ null,
          /* menuStateCores= */ null,
          null,
          false,
          true);
    }
  }

  private final AccessibilityNodeInfoCompat itemRoot;
  private final AccessibilityNodeInfoCompat representative;
  private final List<ChildAction> actions;
  private final @Nullable CharSequence composedLabel;
  private final @Nullable AccessibilityNodeInfoCompat activationTarget;
  private final @Nullable AccessibilityNodeInfoCompat selectionTarget;
  private final CollapseScript.Pattern pattern;

  CollapsedItem(
      AccessibilityNodeInfoCompat itemRoot,
      AccessibilityNodeInfoCompat representative,
      List<ChildAction> actions,
      @Nullable CharSequence composedLabel,
      @Nullable AccessibilityNodeInfoCompat activationTarget,
      @Nullable AccessibilityNodeInfoCompat selectionTarget,
      CollapseScript.Pattern pattern) {
    this.itemRoot = itemRoot;
    this.representative = representative;
    this.actions = actions;
    this.composedLabel = composedLabel;
    this.activationTarget = activationTarget;
    this.selectionTarget = selectionTarget;
    this.pattern = pattern;
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

  /** The representative strategy of the script that matched this item. */
  public CollapseScript.Pattern pattern() {
    return pattern;
  }

  /** Whether the node is the item root or one of its descendants. */
  public boolean contains(AccessibilityNodeInfoCompat node) {
    return itemRoot.equals(node) || AccessibilityNodeInfoUtils.hasAncestor(node, itemRoot);
  }
}
