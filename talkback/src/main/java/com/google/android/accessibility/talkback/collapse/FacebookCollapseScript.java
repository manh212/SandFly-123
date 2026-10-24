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
 * <p>The body text and the node that can be tapped for it are two different nodes in the real
 * tree: the text sits on a plain child with no flags at all, wrapped by a clickable shell with
 * no text. Fields are therefore resolved by content, not by clickability: the body field is the
 * node carrying the text (also the text-selection target), while the shell is the node the
 * synthesized body action lands on.
 *
 * <p>The action blocks (reply, reactions, like, demote) are not always siblings of the header:
 * in one recorded shape they sit one level higher, as siblings of the header group inside a
 * shared container, where the Like block's long instruction label would win a whole-item
 * longest-label search and be spoken as the body. The body search is therefore anchored to the
 * lowest common ancestor of the avatar, author and timestamp — a subtree that only ever holds
 * header content — and prefers the subtree of the header's clickable, long-clickable shell
 * that wraps the body text.
 *
 * <p>Some comment frames carry no clickable or focusable flags at all — the recorded replies
 * with an attached photo are shaped like that. This script opts in to those frames; the engine
 * then only accepts them as direct children of the scrollable comment list, and a double-tap
 * lands on the body shell instead of the frame. A comment that is only a photo still does not
 * match: it has no author, time or body text to compose a label from.
 *
 * <p>The action cap covers the heaviest recorded combination: a photo comment with a follow
 * button and a reactions block reaches eleven actions after de-duplication.
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
    super(PACKAGE_NAME, Pattern.COMPOSITE_ROOT, /* maxActions= */ 12);
  }

  @Override
  public @Nullable String selectionFieldId() {
    return FIELD_BODY;
  }

  @Override
  public boolean allowsUnflaggedRepresentative() {
    return true;
  }

  @Override
  public @Nullable String activationAnchorFieldId() {
    return FIELD_BODY;
  }

  @Override
  protected @Nullable List<Field> resolveFields(ItemScan scan) {
    AccessibilityNodeInfoCompat avatar = avatarNode(scan);
    AccessibilityNodeInfoCompat author = authorNode(scan, avatar);
    AccessibilityNodeInfoCompat time = timeNode(scan, avatar, author);
    AccessibilityNodeInfoCompat body = bodyNode(scan, avatar, author, time);
    if (body == null || time == null || author == null) {
      return null;
    }
    if (author.equals(time) || author.equals(body) || time.equals(body)) {
      // One node filling two fields means a field is really absent — with no timestamp, the
      // body itself is the first focusable, labelled, non-clickable node the timestamp
      // selector can find. Fail safe instead of composing a label with a repeated field.
      return null;
    }
    List<Field> fields = new ArrayList<>();
    fields.add(new Field(FIELD_AUTHOR, author, ItemScan.spokenLabel(author)));
    fields.add(new Field(FIELD_TIME, time, ItemScan.spokenLabel(time)));
    fields.add(new Field(FIELD_BODY, body, ItemScan.spokenLabel(body)));
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
    if (TextUtils.isEmpty(spokenLabel)) {
      // A clickable node with no label of its own can still be the tap target wrapping the
      // body text (the real tree splits the two); name its action after the text it wraps.
      CharSequence wrapped = longestDescendantLabel(node);
      if (!TextUtils.isEmpty(wrapped)) {
        return wrapped;
      }
    }
    return spokenLabel;
  }

  @Override
  protected boolean longClickTwin(AccessibilityNodeInfoCompat node, ItemScan scan) {
    // Long-pressing a bottom-row button (Like) opens the reaction picker; the body itself is
    // excluded so its own long-press behaviour is not shadowed. The body content node sits
    // inside its clickable shell, so the shell precedes it in reading order and is excluded
    // by the same comparison.
    if (!node.isLongClickable()) {
      return false;
    }
    // The attached-photo block is content, not a bottom-row action: its wrapper and the
    // image inside are both long-clickable and sit after the body in reading order, but
    // long-pressing them opens no picker, so they never earn a twin.
    if (isOrWrapsImage(node, scan)) {
      return false;
    }
    AccessibilityNodeInfoCompat avatar = avatarNode(scan);
    AccessibilityNodeInfoCompat author = authorNode(scan, avatar);
    AccessibilityNodeInfoCompat time = timeNode(scan, avatar, author);
    AccessibilityNodeInfoCompat body = bodyNode(scan, avatar, author, time);
    return body != null && !node.equals(body) && scan.indexOf(node) > scan.indexOf(body);
  }

  /** Whether a node is an image or wraps one — the shape of an attached-photo block. */
  private static boolean isOrWrapsImage(AccessibilityNodeInfoCompat node, ItemScan scan) {
    if (isImage(node)) {
      return true;
    }
    for (AccessibilityNodeInfoCompat descendant : scan.descendants()) {
      if (isWithin(descendant, node) && isImage(descendant)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isImage(AccessibilityNodeInfoCompat node) {
    int role = Role.getRole(node);
    return role == Role.ROLE_IMAGE || role == Role.ROLE_IMAGE_BUTTON;
  }

  /** The avatar: the first clickable image button in the item. */
  private static @Nullable AccessibilityNodeInfoCompat avatarNode(ItemScan scan) {
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if (Role.getRole(node) == Role.ROLE_IMAGE_BUTTON) {
        return node;
      }
    }
    return null;
  }

  /** The author name: the first labelled clickable after the avatar, skipping images. */
  private static @Nullable AccessibilityNodeInfoCompat authorNode(
      ItemScan scan, @Nullable AccessibilityNodeInfoCompat avatar) {
    if (avatar == null) {
      return null;
    }
    int avatarIndex = scan.indexOf(avatar);
    for (AccessibilityNodeInfoCompat node : scan.clickables()) {
      if (scan.indexOf(node) <= avatarIndex) {
        continue;
      }
      if (Role.getRole(node) != Role.ROLE_IMAGE_BUTTON
          && !TextUtils.isEmpty(ItemScan.spokenLabel(node))) {
        return node;
      }
    }
    return null;
  }

  /**
   * The timestamp: a focusable, non-clickable, labelled node in the header cluster (the
   * subtree of the avatar/author common ancestor), so a look-alike wrapper from an action
   * block can never stand in for it.
   */
  private static @Nullable AccessibilityNodeInfoCompat timeNode(
      ItemScan scan,
      @Nullable AccessibilityNodeInfoCompat avatar,
      @Nullable AccessibilityNodeInfoCompat author) {
    if (avatar == null || author == null) {
      return null;
    }
    AccessibilityNodeInfoCompat cluster = lowestCommonAncestor(avatar, author);
    if (cluster == null) {
      return null;
    }
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (!isWithin(node, cluster) || node.equals(author)) {
        continue;
      }
      if (node.isFocusable()
          && !node.isClickable()
          && !TextUtils.isEmpty(ItemScan.spokenLabel(node))) {
        return node;
      }
    }
    return null;
  }

  /**
   * The comment body: a content node inside the header region — the subtree of the lowest
   * common ancestor of avatar, author and timestamp, which never contains an action block in
   * either recorded nesting shape. The header's one clickable, long-clickable node is the
   * shell wrapping the body text, so the body is the longest spoken label in the shell's
   * subtree; without such a shell it is the longest spoken label in the region, excluding
   * the avatar, author and timestamp and anything above or below them. The tap target for
   * the body (its clickable shell) already appears among the item's clickables on its own.
   */
  private static @Nullable AccessibilityNodeInfoCompat bodyNode(
      ItemScan scan,
      @Nullable AccessibilityNodeInfoCompat avatar,
      @Nullable AccessibilityNodeInfoCompat author,
      @Nullable AccessibilityNodeInfoCompat time) {
    if (avatar == null || author == null || time == null) {
      return null;
    }
    AccessibilityNodeInfoCompat cluster = lowestCommonAncestor(avatar, author);
    AccessibilityNodeInfoCompat region =
        cluster == null ? null : lowestCommonAncestor(cluster, time);
    if (region == null) {
      return null;
    }
    AccessibilityNodeInfoCompat shell = null;
    int shells = 0;
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (isWithin(node, region) && node.isClickable() && node.isLongClickable()) {
        shell = node;
        shells++;
      }
    }
    if (shells == 1) {
      return longestLabelIn(scan, shell);
    }
    AccessibilityNodeInfoCompat body = null;
    int longest = 0;
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (!isWithin(node, region) || isFieldOrKin(node, avatar, author, time)) {
        continue;
      }
      CharSequence label = ItemScan.spokenLabel(node);
      if (!TextUtils.isEmpty(label) && label.length() > longest) {
        longest = label.length();
        body = node;
      }
    }
    return body;
  }

  /** The node with the longest spoken label in a subtree, or null if it carries none. */
  private static @Nullable AccessibilityNodeInfoCompat longestLabelIn(
      ItemScan scan, AccessibilityNodeInfoCompat subtreeRoot) {
    AccessibilityNodeInfoCompat best = null;
    int longest = 0;
    for (AccessibilityNodeInfoCompat node : scan.descendants()) {
      if (!isWithin(node, subtreeRoot)) {
        continue;
      }
      CharSequence label = ItemScan.spokenLabel(node);
      if (!TextUtils.isEmpty(label) && label.length() > longest) {
        longest = label.length();
        best = node;
      }
    }
    return best;
  }

  /** Whether a node is one of the resolved fields, or an ancestor or descendant of one. */
  private static boolean isFieldOrKin(
      AccessibilityNodeInfoCompat node,
      AccessibilityNodeInfoCompat avatar,
      AccessibilityNodeInfoCompat author,
      AccessibilityNodeInfoCompat time) {
    AccessibilityNodeInfoCompat[] fields = {avatar, author, time};
    for (AccessibilityNodeInfoCompat field : fields) {
      if (node.equals(field)
          || AccessibilityNodeInfoUtils.hasAncestor(node, field)
          || AccessibilityNodeInfoUtils.hasAncestor(field, node)) {
        return true;
      }
    }
    return false;
  }

  /** Whether a node is the subtree root itself or one of its descendants. */
  private static boolean isWithin(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat subtreeRoot) {
    return node.equals(subtreeRoot)
        || AccessibilityNodeInfoUtils.hasAncestor(node, subtreeRoot);
  }

  /** The lowest common ancestor of two nodes in the same tree, or null if there is none. */
  private static @Nullable AccessibilityNodeInfoCompat lowestCommonAncestor(
      AccessibilityNodeInfoCompat first, AccessibilityNodeInfoCompat second) {
    List<AccessibilityNodeInfoCompat> chain = new ArrayList<>();
    for (AccessibilityNodeInfoCompat node = first; node != null; node = node.getParent()) {
      chain.add(node);
    }
    for (AccessibilityNodeInfoCompat node = second; node != null; node = node.getParent()) {
      if (chain.contains(node)) {
        return node;
      }
    }
    return null;
  }

  /** The longest spoken label in a node's subtree, or empty if it carries none. */
  private static CharSequence longestDescendantLabel(AccessibilityNodeInfoCompat node) {
    CharSequence best = "";
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfoCompat child = node.getChild(i);
      if (child == null) {
        continue;
      }
      CharSequence label = ItemScan.spokenLabel(child);
      if (label.length() > best.length()) {
        best = label;
      }
      CharSequence nested = longestDescendantLabel(child);
      if (nested.length() > best.length()) {
        best = nested;
      }
    }
    return best;
  }
}
