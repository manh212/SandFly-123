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

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseScript.Activation;
import com.google.android.accessibility.talkback.collapse.CollapseScript.Field;
import com.google.android.accessibility.talkback.collapse.CollapsedItem.ChildAction;
import com.google.android.accessibility.talkback.compositor.CompositorUtils;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The shared engine behind per-app list item collapsing. Every entry point first checks the
 * user switch and the package gate: packages without a script, and all packages while the
 * switch is off, keep upstream behaviour exactly. Matching is fail-safe: any doubt means the
 * item is left untouched, never half-collapsed.
 */
public final class CollapseEngine {
  private static final int MAX_ITEM_SEARCH_DEPTH = 24;

  private CollapseEngine() {}

  public static boolean isEnabled(Context context) {
    if (context == null) {
      return false;
    }
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    return prefs.getBoolean(
        context.getString(R.string.pref_collapse_list_item_actions_key),
        context.getResources().getBoolean(R.bool.pref_collapse_list_item_actions_default));
  }

  /**
   * Composes the collapse rule into a swipe-navigation node filter: inside a collapsed item
   * only the representative passes; the representative itself passes on visibility alone,
   * because its spoken label may be composed by the engine rather than carried by the node.
   */
  public static Filter<AccessibilityNodeInfoCompat> composeNodeFilter(
      Context context, Filter<AccessibilityNodeInfoCompat> baseFilter) {
    if (!isEnabled(context)) {
      return baseFilter;
    }
    return composeNodeFilter(baseFilter, CollapseScripts.all());
  }

  static Filter<AccessibilityNodeInfoCompat> composeNodeFilter(
      Filter<AccessibilityNodeInfoCompat> baseFilter, Map<String, CollapseScript> scripts) {
    return new Filter<AccessibilityNodeInfoCompat>() {
      @Override
      public boolean accept(AccessibilityNodeInfoCompat node) {
        CollapsedItem item = matchContaining(node, scripts);
        if (item == null) {
          return baseFilter.accept(node);
        }
        if (node.equals(item.representative())) {
          return AccessibilityNodeInfoUtils.isVisible(node);
        }
        return false;
      }
    };
  }

  /** The composed spoken label for a focused composite representative, or null if none. */
  public static @Nullable CharSequence composedLabel(
      Context context, AccessibilityNodeInfoCompat node) {
    CollapsedItem item = matchRepresentative(context, node);
    return item == null ? null : item.composedLabel();
  }

  /** The node a touch on any part of a collapsed item should focus: its representative. */
  public static @Nullable AccessibilityNodeInfoCompat touchTarget(
      Context context, @Nullable AccessibilityNodeInfoCompat node) {
    if (node == null || !isEnabled(context)) {
      return node;
    }
    CollapsedItem item = matchContaining(node, CollapseScripts.all());
    return item == null ? node : item.representative();
  }

  /** The node a double-tap should click instead of the representative, or null for itself. */
  public static @Nullable AccessibilityNodeInfoCompat activationTarget(
      Context context, AccessibilityNodeInfoCompat node) {
    CollapsedItem item = matchRepresentative(context, node);
    return item == null ? null : item.activationTarget();
  }

  /** The node text selection should target instead of the representative, or null for itself. */
  public static @Nullable AccessibilityNodeInfoCompat selectionTarget(
      Context context, AccessibilityNodeInfoCompat node) {
    CollapsedItem item = matchRepresentative(context, node);
    return item == null ? null : item.selectionTarget();
  }

  /** The collapsed item whose representative is exactly this node, or null. */
  public static @Nullable CollapsedItem matchRepresentative(
      Context context, AccessibilityNodeInfoCompat node) {
    if (node == null || !isEnabled(context)) {
      return null;
    }
    CollapsedItem item = matchContaining(node, CollapseScripts.all());
    return (item != null && node.equals(item.representative())) ? item : null;
  }

  /** Finds the collapsed item containing a node, via its nearest collection-item ancestor. */
  static @Nullable CollapsedItem matchContaining(
      AccessibilityNodeInfoCompat node, Map<String, CollapseScript> scripts) {
    if (node == null) {
      return null;
    }
    CollapseScript script = scripts.get(packageName(node));
    if (script == null) {
      return null;
    }
    AccessibilityNodeInfoCompat current = node;
    for (int depth = 0; current != null && depth < MAX_ITEM_SEARCH_DEPTH; depth++) {
      if (isCollectionItem(current)) {
        return match(script, current);
      }
      current = current.getParent();
    }
    return null;
  }

  /** Matches a node treated as a collection item root against its package's script. */
  static @Nullable CollapsedItem matchItemRoot(
      AccessibilityNodeInfoCompat itemRoot, Map<String, CollapseScript> scripts) {
    if (itemRoot == null) {
      return null;
    }
    CollapseScript script = scripts.get(packageName(itemRoot));
    return script == null ? null : match(script, itemRoot);
  }

  static @Nullable CollapsedItem match(
      CollapseScript script, AccessibilityNodeInfoCompat itemRoot) {
    if (itemRoot == null || !isCollectionItem(itemRoot)) {
      return null;
    }
    if (!script.packageName().contentEquals(packageName(itemRoot))) {
      return null;
    }
    if (AccessibilityNodeInfoUtils.isSelfOrAncestorRoleWebView(itemRoot)) {
      return null;
    }
    ItemScan scan = ItemScan.of(itemRoot);
    if (scan == null || scan.hasEssentialDescendant() || scan.hasScrollableDescendant()) {
      return null;
    }
    return switch (script.pattern()) {
      case PRIMARY_CHILD -> matchPrimaryChild(script, itemRoot, scan);
      case COMPOSITE_ROOT -> matchCompositeRoot(script, itemRoot, scan);
    };
  }

  private static @Nullable CollapsedItem matchPrimaryChild(
      CollapseScript script, AccessibilityNodeInfoCompat itemRoot, ItemScan scan) {
    long itemArea = area(itemRoot);
    if (itemArea <= 0) {
      return null;
    }
    AccessibilityNodeInfoCompat primary = null;
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if ((double) area(node) >= script.primaryMinAreaFraction() * itemArea
          && !TextUtils.isEmpty(ItemScan.spokenLabel(node))) {
        if (primary != null) {
          return null;
        }
        primary = node;
      }
    }
    if (primary == null) {
      return null;
    }
    List<ChildAction> clickActions = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if (node.equals(primary)) {
        continue;
      }
      if (AccessibilityNodeInfoUtils.hasAncestor(primary, node)) {
        return null;
      }
      if (TextUtils.isEmpty(ItemScan.spokenLabel(node))
          || (double) area(node) > script.auxMaxAreaFraction() * itemArea) {
        return null;
      }
      clickActions.add(
          new ChildAction(node, /* longClick= */ false, actionLabel(script, node)));
    }
    clickActions = dedupNestedSameLabel(clickActions);
    if (clickActions.isEmpty() || clickActions.size() > script.maxActions()) {
      return null;
    }
    return new CollapsedItem(
        itemRoot,
        primary,
        withLongClickTwins(script, scan, expandMenu(script, itemRoot, clickActions)),
        /* composedLabel= */ null,
        /* activationTarget= */ null,
        /* selectionTarget= */ null,
        script.pattern());
  }

  /**
   * Replaces the item's overflow-menu button with the catalog of its menu items, plus a trailing
   * fallback that opens the menu as usual. The action cap above counts real child buttons only;
   * the catalog is a fixed declaration, not scanned children. Expansion happens only when
   * exactly one child qualifies as the menu button — any doubt leaves the actions untouched.
   */
  private static List<ChildAction> expandMenu(
      CollapseScript script,
      AccessibilityNodeInfoCompat itemRoot,
      List<ChildAction> clickActions) {
    CollapseScript.MenuCatalog catalog = script.menuCatalog();
    if (catalog == null) {
      return clickActions;
    }
    int menuIndex = -1;
    for (int i = 0; i < clickActions.size(); i++) {
      if (script.isMenuButton(clickActions.get(i).node, itemRoot)) {
        if (menuIndex >= 0) {
          return clickActions;
        }
        menuIndex = i;
      }
    }
    if (menuIndex < 0) {
      return clickActions;
    }
    AccessibilityNodeInfoCompat menuButton = clickActions.get(menuIndex).node;
    List<ChildAction> expanded = new ArrayList<>();
    for (int i = 0; i < clickActions.size(); i++) {
      if (i != menuIndex) {
        expanded.add(clickActions.get(i));
        continue;
      }
      for (CollapseScript.MenuCatalog.Item item : catalog.items) {
        expanded.add(ChildAction.menuItem(menuButton, item, catalog.sheetItemLabelViewId));
      }
    }
    expanded.add(ChildAction.menuOpen(menuButton, catalog.openMenuLabelResId));
    return expanded;
  }

  private static @Nullable CollapsedItem matchCompositeRoot(
      CollapseScript script, AccessibilityNodeInfoCompat itemRoot, ItemScan scan) {
    boolean unflaggedRepresentative = false;
    if (!itemRoot.isClickable() || !itemRoot.isFocusable()) {
      // A script may opt in to a representative that carries no flags of its own. The only
      // thing that can still land focus on such a node is TalkBack's fallback focusability,
      // so the opt-in is honoured solely for its exact shape — a top-level scroll item.
      // That the item also speaks is proven below: without resolvable labelled fields and
      // a non-empty composed label there is no match.
      if (!script.allowsUnflaggedRepresentative()
          || !AccessibilityNodeInfoUtils.isTopLevelScrollItem(itemRoot)) {
        return null;
      }
      unflaggedRepresentative = true;
    }
    if (!TextUtils.isEmpty(ItemScan.spokenLabel(itemRoot))) {
      return null;
    }
    if (scan.clickables().size() < 2) {
      return null;
    }
    List<Field> fields = script.resolveFields(scan);
    if (fields == null || fields.isEmpty()) {
      return null;
    }
    StringBuilder label = new StringBuilder();
    for (Field field : fields) {
      if (TextUtils.isEmpty(field.label)) {
        continue;
      }
      if (label.length() > 0) {
        label.append(CompositorUtils.getSeparator());
      }
      label.append(field.label);
    }
    if (label.length() == 0) {
      return null;
    }
    List<ChildAction> clickActions = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      CharSequence childLabel = actionLabel(script, node);
      if (TextUtils.isEmpty(childLabel)) {
        // A clickable that neither speaks nor wraps labelled content (an empty container
        // in Facebook's comment header) is not an action; listing it would add a blank
        // entry to the Actions control and waste a slot under the cap.
        continue;
      }
      clickActions.add(new ChildAction(node, /* longClick= */ false, childLabel));
    }
    clickActions = dedupNestedSameLabel(clickActions);
    if (clickActions.isEmpty() || clickActions.size() > script.maxActions()) {
      return null;
    }
    AccessibilityNodeInfoCompat activationTarget = null;
    if (script.activation() == Activation.DESCENDANT_FIELD) {
      activationTarget = fieldNode(fields, script.activationFieldId());
      if (activationTarget == null) {
        return null;
      }
    } else if (unflaggedRepresentative) {
      // The representative cannot be clicked at all, so a double-tap goes to the nearest
      // clickable ancestor of the main content field — for Facebook, the shell wrapping
      // the body text. Without one there is nothing meaningful to activate: no match.
      AccessibilityNodeInfoCompat anchor = fieldNode(fields, script.activationAnchorFieldId());
      activationTarget = anchor == null ? null : closestClickable(anchor, itemRoot);
      if (activationTarget == null) {
        return null;
      }
    }
    return new CollapsedItem(
        itemRoot,
        itemRoot,
        expandTray(
            script, retargetActions(script, scan, withLongClickTwins(script, scan, clickActions))),
        label.toString(),
        activationTarget,
        fieldNode(fields, script.selectionFieldId()),
        script.pattern());
  }

  /**
   * Replaces the scanned actions an item's action tray duplicates with the tray's full
   * declared contents, in tray order, at the position of the first replaced action. The
   * action cap above counts real child buttons only; the tray set is a fixed declaration,
   * not scanned children. Expansion happens only when at least one scanned action is
   * absorbed; any other shape leaves the actions untouched. The tray is offered under the
   * label set of the language the absorbed actions were read in — the live item is the
   * ground truth for the app's current language.
   */
  private static List<ChildAction> expandTray(CollapseScript script, List<ChildAction> actions) {
    CollapseScript.TrayCatalog catalog = script.trayCatalog();
    if (catalog == null) {
      return actions;
    }
    List<ChildAction> kept = new ArrayList<>();
    int insertAt = -1;
    AccessibilityNodeInfoCompat backing = null;
    Boolean vietnamese = null;
    for (ChildAction action : actions) {
      CollapseScript.TrayCatalog.Entry absorbed =
          action.kind == ChildAction.Kind.CLICK ? absorbingEntry(catalog, action.label) : null;
      if (absorbed == null) {
        kept.add(action);
        continue;
      }
      if (insertAt < 0) {
        insertAt = kept.size();
        backing = action.node;
      }
      if (vietnamese == null) {
        String label = action.label.toString().trim();
        if (label.equals(absorbed.labelVi)) {
          vietnamese = true;
        } else if (label.equals(absorbed.labelEn)) {
          vietnamese = false;
        }
      }
    }
    if (insertAt < 0) {
      return actions;
    }
    boolean vi = vietnamese != null ? vietnamese : "vi".equals(Locale.getDefault().getLanguage());
    List<ChildAction> trayActions = new ArrayList<>();
    for (CollapseScript.TrayCatalog.Entry entry : catalog.entries) {
      trayActions.add(
          ChildAction.trayItem(
              backing,
              vi ? entry.labelVi : entry.labelEn,
              new String[] {entry.labelVi, entry.labelEn},
              entry.restoreFocusOnClose));
    }
    kept.addAll(insertAt, trayActions);
    return kept;
  }

  /** The catalog entry whose scanned counterpart carries this label, or null if none does. */
  private static @Nullable CollapseScript.TrayCatalog.Entry absorbingEntry(
      CollapseScript.TrayCatalog catalog, CharSequence label) {
    if (label == null) {
      return null;
    }
    String trimmed = label.toString().trim();
    for (CollapseScript.TrayCatalog.Entry entry : catalog.entries) {
      if (entry.absorbsScanned
          && (trimmed.equals(entry.labelVi) || trimmed.equals(entry.labelEn))) {
        return entry;
      }
    }
    return null;
  }

  /** The node itself if clickable, else its nearest clickable ancestor up to the item root. */
  private static @Nullable AccessibilityNodeInfoCompat closestClickable(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat itemRoot) {
    for (AccessibilityNodeInfoCompat current = node;
        current != null;
        current = current.getParent()) {
      if (current.isClickable()) {
        return current;
      }
      if (current.equals(itemRoot)) {
        return null;
      }
    }
    return null;
  }

  private static List<ChildAction> withLongClickTwins(
      CollapseScript script, ItemScan scan, List<ChildAction> clickActions) {
    List<ChildAction> actions = new ArrayList<>();
    for (ChildAction action : clickActions) {
      actions.add(action);
      if (action.kind == ChildAction.Kind.CLICK && script.longClickTwin(action.node, scan)) {
        actions.add(new ChildAction(action.node, /* longClick= */ true, action.label));
      }
    }
    return actions;
  }

  /**
   * Points each action at the node its script names for it. This runs last: labels and
   * long-click twins are settled from the scanned children first, so a retargeted action
   * keeps the name and the twin its original child earned — only the node the press lands
   * on changes.
   */
  private static List<ChildAction> retargetActions(
      CollapseScript script, ItemScan scan, List<ChildAction> actions) {
    List<ChildAction> retargeted = new ArrayList<>();
    for (ChildAction action : actions) {
      AccessibilityNodeInfoCompat target = script.actionTargetNode(action.node, scan);
      retargeted.add(
          target == null || target.equals(action.node)
              ? action
              : new ChildAction(target, action.longClick, action.label));
    }
    return retargeted;
  }

  private static List<ChildAction> dedupNestedSameLabel(List<ChildAction> actions) {
    List<ChildAction> kept = new ArrayList<>();
    for (ChildAction candidate : actions) {
      boolean duplicate = false;
      for (ChildAction earlier : kept) {
        if (TextUtils.equals(earlier.label, candidate.label)
            && AccessibilityNodeInfoUtils.hasAncestor(candidate.node, earlier.node)) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        kept.add(candidate);
      }
    }
    return kept;
  }

  private static CharSequence actionLabel(
      CollapseScript script, AccessibilityNodeInfoCompat node) {
    return script.actionLabel(node, ItemScan.spokenLabel(node));
  }

  private static @Nullable AccessibilityNodeInfoCompat fieldNode(
      List<Field> fields, @Nullable String fieldId) {
    if (fieldId == null) {
      return null;
    }
    for (Field field : fields) {
      if (field.id.equals(fieldId)) {
        return field.node;
      }
    }
    return null;
  }

  static boolean isCollectionItem(AccessibilityNodeInfoCompat node) {
    if (node.getCollectionItemInfo() != null) {
      return true;
    }
    AccessibilityNodeInfoCompat parent = node.getParent();
    if (parent == null) {
      return false;
    }
    int parentRole = Role.getRole(parent);
    return parent.getCollectionInfo() != null
        || parentRole == Role.ROLE_LIST
        || parentRole == Role.ROLE_GRID;
  }

  private static long area(AccessibilityNodeInfoCompat node) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    return (long) bounds.width() * bounds.height();
  }

  private static String packageName(AccessibilityNodeInfoCompat node) {
    CharSequence packageName = node.getPackageName();
    return packageName == null ? "" : packageName.toString();
  }
}
