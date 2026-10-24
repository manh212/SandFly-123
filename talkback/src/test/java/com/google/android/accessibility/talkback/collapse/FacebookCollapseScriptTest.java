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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.FacebookCommentTree;
import com.google.android.accessibility.talkback.collapse.CollapsedItem.ChildAction;
import com.google.android.accessibility.talkback.compositor.CompositorUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FacebookCollapseScriptTest {
  private static final Map<String, CollapseScript> SCRIPTS =
      Map.of(CollapseTestTrees.FACEBOOK_PACKAGE, new FacebookCollapseScript());

  @Test
  public void comment_matches_labelIsAuthorTimeBody_actionsTargetTheSplitBodyShell() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    assertEquals(tree.root, item.representative());
    String separator = CompositorUtils.getSeparator();
    assertEquals(
        "Nguyen Van A" + separator + "1 day" + separator + "This is a great comment",
        item.composedLabel());
    List<String> clickLabels = new ArrayList<>();
    int longClickTwins = 0;
    for (ChildAction action : item.actions()) {
      if (action.longClick) {
        longClickTwins++;
        assertEquals(tree.like, action.node);
      } else {
        clickLabels.add(action.label.toString());
        if ("This is a great comment".contentEquals(action.label)) {
          // The text itself is not clickable in the real tree; its action lands on the shell.
          assertEquals(tree.bodyActionTarget, action.node);
        }
      }
    }
    assertEquals(
        List.of(
            "Profile picture of Nguyen Van A",
            "Nguyen Van A",
            "This is a great comment",
            "Reply",
            "5 reactions",
            "Like",
            "Demote comment"),
        clickLabels);
    assertEquals(1, longClickTwins);
    // Text selection still targets the node carrying the text, not the shell.
    assertEquals(tree.body, item.selectionTarget());
    assertNull(item.activationTarget());
  }

  @Test
  public void commentWithFollowButton_stillMatches_atTheActionCap() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentWithFollow(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    List<String> clickLabels = new ArrayList<>();
    for (ChildAction action : item.actions()) {
      if (!action.longClick) {
        clickLabels.add(action.label.toString());
      }
    }
    assertEquals(
        List.of(
            "Profile picture of Nguyen Van A",
            "Nguyen Van A",
            " · Follow",
            "This is a great comment",
            "Reply",
            "5 reactions",
            "Like",
            "Demote comment"),
        clickLabels);
  }

  @Test
  public void commentWithClickableBody_stillMatches() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentClickableBody(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    String separator = CompositorUtils.getSeparator();
    assertEquals(
        "Nguyen Van A" + separator + "1 day" + separator + "This is a great comment",
        item.composedLabel());
    List<String> clickLabels = new ArrayList<>();
    for (ChildAction action : item.actions()) {
      if (!action.longClick) {
        clickLabels.add(action.label.toString());
        if ("This is a great comment".contentEquals(action.label)) {
          assertEquals(tree.body, action.node);
        }
      }
    }
    assertEquals(
        List.of(
            "Profile picture of Nguyen Van A",
            "Nguyen Van A",
            "This is a great comment",
            "See more",
            "Reply",
            "5 reactions",
            "Like",
            "Demote comment"),
        clickLabels);
    assertEquals(tree.body, item.selectionTarget());
  }

  @Test
  public void commentNestedActions_matches_labelIsAuthorTimeBody_notTheLikeInstructions() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentNestedActions(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    assertEquals(tree.root, item.representative());
    String separator = CompositorUtils.getSeparator();
    // The action blocks are nested inside the header's container in this shape; the Like
    // block's instruction label must never be spoken as the comment body.
    assertEquals(
        "Nguyen Van A" + separator + "1 day" + separator + "This is a great comment",
        item.composedLabel());
    assertFalse(item.composedLabel().toString().contains("reaction tray"));
    List<String> clickLabels = new ArrayList<>();
    int longClickTwins = 0;
    for (ChildAction action : item.actions()) {
      if (action.longClick) {
        longClickTwins++;
        assertEquals(tree.like, action.node);
      } else {
        clickLabels.add(action.label.toString());
        if ("This is a great comment".contentEquals(action.label)) {
          assertEquals(tree.bodyActionTarget, action.node);
        }
      }
    }
    assertEquals(
        List.of(
            "Profile picture of Nguyen Van A",
            "Nguyen Van A",
            "This is a great comment",
            "Reply",
            "5 reactions",
            "Like",
            "Demote comment"),
        clickLabels);
    assertEquals(1, longClickTwins);
    assertEquals(tree.body, item.selectionTarget());
    assertNull(item.activationTarget());
  }

  @Test
  public void commentNestedShortBodyWithFollow_stillMatches_bodyIsTheShortText() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentNestedShortBodyWithFollow(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    String separator = CompositorUtils.getSeparator();
    // The body ("Cool", 4 chars) is shorter than the follow button's label; it still wins
    // because the body is searched inside the body shell's subtree only.
    assertEquals("Nguyen Van A" + separator + "1 day" + separator + "Cool", item.composedLabel());
    List<String> clickLabels = new ArrayList<>();
    for (ChildAction action : item.actions()) {
      if (!action.longClick) {
        clickLabels.add(action.label.toString());
      }
    }
    assertEquals(
        List.of(
            "Profile picture of Nguyen Van A",
            "Nguyen Van A",
            " · Follow",
            "Cool",
            "Reply",
            "5 reactions",
            "Like",
            "Demote comment"),
        clickLabels);
    assertEquals(tree.body, item.selectionTarget());
  }

  @Test
  public void commentNestedWithBadge_stillMatches_labelIsAuthorTimeBody() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentNestedWithBadge(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    String separator = CompositorUtils.getSeparator();
    assertEquals(
        "Nguyen Van A" + separator + "1 day" + separator + "This is a great comment",
        item.composedLabel());
    List<String> clickLabels = new ArrayList<>();
    for (ChildAction action : item.actions()) {
      if (!action.longClick) {
        clickLabels.add(action.label.toString());
        if ("Fan cứng".contentEquals(action.label)) {
          assertEquals(tree.badge, action.node);
        }
      }
    }
    assertEquals(
        List.of(
            "Profile picture of Nguyen Van A",
            "Nguyen Van A",
            "Fan cứng",
            "This is a great comment",
            "Reply",
            "5 reactions",
            "Like",
            "Demote comment"),
        clickLabels);
    assertEquals(tree.body, item.selectionTarget());
  }

  @Test
  public void emptyComment_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    AccessibilityNodeInfoCompat root = CollapseTestTrees.item(list);
    root.setClickable(true);
    root.setFocusable(true);
    CollapseTestTrees.bounds(root, 0, 0, 1080, 200);
    AccessibilityNodeInfoCompat placeholder =
        CollapseTestTrees.node(root, "android.view.ViewGroup");
    placeholder.setClickable(true);
    placeholder.setLongClickable(true);
    placeholder.setFocusable(true);
    CollapseTestTrees.bounds(placeholder, 0, 0, 1080, 200);
    CollapseTestTrees.applyPackage(root, CollapseTestTrees.FACEBOOK_PACKAGE);

    assertNull(CollapseEngine.matchItemRoot(root, SCRIPTS));
  }

  @Test
  public void feedPost_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    AccessibilityNodeInfoCompat post = CollapseTestTrees.facebookPost(list);

    assertNull(CollapseEngine.matchItemRoot(post, SCRIPTS));
    assertNull(CollapseEngine.matchContaining(post.getChild(0), SCRIPTS));
  }

  @Test
  public void commentWithNineClickableChildren_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    for (int i = 0; i < 2; i++) {
      AccessibilityNodeInfoCompat attachment =
          CollapseTestTrees.node(tree.root, "android.widget.ImageButton");
      attachment.setClickable(true);
      attachment.setFocusable(true);
      attachment.setContentDescription("Attached photo");
      CollapseTestTrees.bounds(attachment, 0, 400 + i * 200, 200, 600 + i * 200);
      attachment.setPackageName(CollapseTestTrees.FACEBOOK_PACKAGE);
    }

    assertNull(CollapseEngine.matchItemRoot(tree.root, SCRIPTS));
  }

  @Test
  public void commentWithoutTimestamp_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    tree.time.setText(null);
    tree.time.setContentDescription(null);

    assertNull(CollapseEngine.matchItemRoot(tree.root, SCRIPTS));
  }

  @Test
  public void commentWithCheckableChild_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    AccessibilityNodeInfoCompat checkbox =
        CollapseTestTrees.node(tree.root, "android.widget.CheckBox");
    checkbox.setCheckable(true);
    checkbox.setPackageName(CollapseTestTrees.FACEBOOK_PACKAGE);

    assertNull(CollapseEngine.matchItemRoot(tree.root, SCRIPTS));
  }

  @Test
  public void reactionsListRow_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    AccessibilityNodeInfoCompat row = CollapseTestTrees.item(list);
    row.setClickable(true);
    row.setFocusable(true);
    CollapseTestTrees.bounds(row, 0, 0, 1080, 200);
    AccessibilityNodeInfoCompat avatar =
        CollapseTestTrees.node(row, "android.widget.ImageButton");
    avatar.setClickable(true);
    avatar.setFocusable(true);
    avatar.setContentDescription("Profile picture of Nguyen Van A");
    CollapseTestTrees.bounds(avatar, 0, 0, 120, 120);
    AccessibilityNodeInfoCompat name = CollapseTestTrees.node(row, "android.view.ViewGroup");
    name.setClickable(true);
    name.setFocusable(true);
    name.setText("Nguyen Van A");
    CollapseTestTrees.bounds(name, 130, 0, 600, 100);
    CollapseTestTrees.applyPackage(row, CollapseTestTrees.FACEBOOK_PACKAGE);

    assertNull(CollapseEngine.matchItemRoot(row, SCRIPTS));
  }
}
