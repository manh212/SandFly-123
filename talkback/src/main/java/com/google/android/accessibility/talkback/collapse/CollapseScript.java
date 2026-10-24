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
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One app's declaration for collapsing the child buttons of its list items into the Actions
 * reading control. A script is pure declaration plus structure predicates for its own app; the
 * shared engine in {@link CollapseEngine} applies it. An app with no script is never touched.
 */
public abstract class CollapseScript {

  /** Which representative strategy the script uses. */
  public enum Pattern {
    /** The item has one primary child button that already speaks for the whole item. */
    PRIMARY_CHILD,
    /** The item root itself is the stop; its spoken label is composed from content fields. */
    COMPOSITE_ROOT
  }

  /** Where a double-tap on the representative goes. */
  public enum Activation {
    ROOT_CLICK,
    DESCENDANT_FIELD
  }

  /**
   * A declaration that one child button of an item opens an overflow menu whose items should be
   * offered directly as synthesized actions, in place of the button itself. The label resources
   * double as the match keys: at activation time the menu is opened and the row whose live label
   * equals the resolved label is clicked, so the strings must match the app's own menu labels in
   * every shipped locale.
   */
  public static final class MenuCatalog {
    /** Label resources of the menu items, in the order the app's menu shows them. */
    public final @StringRes int[] itemLabelResIds;
    /** View id of the label view inside one row of the app's open menu sheet. */
    public final String sheetItemLabelViewId;
    /** Label resource of the trailing fallback action that opens the menu as usual. */
    public final @StringRes int openMenuLabelResId;

    public MenuCatalog(
        @StringRes int[] itemLabelResIds,
        String sheetItemLabelViewId,
        @StringRes int openMenuLabelResId) {
      this.itemLabelResIds = itemLabelResIds;
      this.sheetItemLabelViewId = sheetItemLabelViewId;
      this.openMenuLabelResId = openMenuLabelResId;
    }
  }

  /** A named content field of a composite item, resolved against the item's subtree. */
  public static final class Field {
    public final String id;
    public final AccessibilityNodeInfoCompat node;
    public final CharSequence label;

    public Field(String id, AccessibilityNodeInfoCompat node, CharSequence label) {
      this.id = id;
      this.node = node;
      this.label = label;
    }
  }

  private final String packageName;
  private final Pattern pattern;
  private final int maxActions;

  protected CollapseScript(String packageName, Pattern pattern, int maxActions) {
    this.packageName = packageName;
    this.pattern = pattern;
    this.maxActions = maxActions;
  }

  public final String packageName() {
    return packageName;
  }

  public final Pattern pattern() {
    return pattern;
  }

  /** Maximum number of synthesized click actions for one item; above it the item is untouched. */
  public final int maxActions() {
    return maxActions;
  }

  /** Minimum fraction of the item's area the primary child must cover (pattern A). */
  public float primaryMinAreaFraction() {
    return 0.6f;
  }

  /** Maximum fraction of the item's area one secondary child may cover (pattern A). */
  public float auxMaxAreaFraction() {
    return 0.3f;
  }

  public Activation activation() {
    return Activation.ROOT_CLICK;
  }

  /** Field whose node receives the activation click when {@link #activation()} redirects. */
  public @Nullable String activationFieldId() {
    return null;
  }

  /** Field whose node text selection should target, or null to keep the default target. */
  public @Nullable String selectionFieldId() {
    return null;
  }

  /**
   * Whether a composite item root may be the representative without being clickable or
   * focusable itself. The engine only honours this for a root that is a top-level scroll
   * item — a direct child of a scrollable container, the shape TalkBack's own fallback
   * focusability covers — whose content fields resolve, and redirects its activation to
   * the field named by {@link #activationAnchorFieldId()}. Scripts that do not opt in keep
   * the strict rule that the root itself must be clickable and focusable.
   */
  public boolean allowsUnflaggedRepresentative() {
    return false;
  }

  /**
   * Field anchoring activation when the representative is unflagged: a double-tap clicks
   * the field node's nearest clickable ancestor inside the item (the wrapper that carries
   * the content on screen), because the representative itself cannot be clicked at all.
   */
  public @Nullable String activationAnchorFieldId() {
    return null;
  }

  /**
   * Resolves the ordered content fields of a composite item, or returns null when any required
   * field is missing (the item then stays untouched).
   */
  protected @Nullable List<Field> resolveFields(ItemScan scan) {
    return null;
  }

  /** Lets a script adjust the spoken label of one synthesized action. */
  protected CharSequence actionLabel(
      AccessibilityNodeInfoCompat node, CharSequence spokenLabel) {
    return spokenLabel;
  }

  /**
   * Whether the action for this node also gets a synthesized long-click action (for child buttons
   * whose long-press carries meaning, such as opening a reaction picker).
   */
  protected boolean longClickTwin(AccessibilityNodeInfoCompat node, ItemScan scan) {
    return false;
  }

  /** The overflow-menu expansion for items matched by this script, or null for none. */
  public @Nullable MenuCatalog menuCatalog() {
    return null;
  }

  /**
   * Whether a clickable child of the item is the overflow-menu button named by {@link
   * #menuCatalog()}. The engine expands the button into the catalog only when exactly one child
   * of an item qualifies; any other count leaves the item's actions untouched.
   */
  public boolean isMenuButton(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat itemRoot) {
    return false;
  }
}
