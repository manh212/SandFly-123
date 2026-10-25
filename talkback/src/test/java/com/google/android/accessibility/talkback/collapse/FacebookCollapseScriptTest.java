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
import static org.junit.Assert.assertTrue;

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

  /** The collapsed item's tray actions, in tray order, under the tray's English labels. */
  private static final List<String> TRAY_LABELS_EN =
      List.of(
          "Like",
          "Love",
          "Care",
          "Haha",
          "Wow",
          "Sad",
          "Angry",
          "Share comment",
          "Report comment",
          "Reply",
          "Copy",
          "Hide comment");

  /** The expected non-twin action labels: {@code before}, the tray set, {@code after}. */
  private static List<String> labelsAroundTray(List<String> before, List<String> after) {
    List<String> all = new ArrayList<>(before);
    all.addAll(TRAY_LABELS_EN);
    all.addAll(after);
    return all;
  }

  /** The item's tray actions, in the order they are offered. */
  private static List<ChildAction> trayActionsOf(CollapsedItem item) {
    List<ChildAction> tray = new ArrayList<>();
    for (ChildAction action : item.actions()) {
      if (action.kind == ChildAction.Kind.TRAY_ITEM) {
        tray.add(action);
      }
    }
    return tray;
  }

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
      } else {
        clickLabels.add(action.label.toString());
        if ("This is a great comment".contentEquals(action.label)) {
          // The text itself is not clickable in the real tree; its action lands on the shell.
          assertEquals(tree.bodyActionTarget, action.node);
        }
      }
    }
    // The scanned Reply and Like (and Like's long-press twin) are absorbed into the tray
    // set, which stands where the Reply/Like cluster stood, in the tray's own order.
    assertEquals(
        labelsAroundTray(
            List.of("Profile picture of Nguyen Van A", "Nguyen Van A", "This is a great comment"),
            List.of("5 reactions", "Demote comment")),
        clickLabels);
    assertEquals(0, longClickTwins);
    List<ChildAction> tray = trayActionsOf(item);
    assertEquals(12, tray.size());
    for (int i = 0; i < tray.size(); i++) {
      // Every tray action is backed by the first absorbed action's node and carries the
      // button's recorded labels in both languages for the tray-side match.
      assertEquals(tree.replyWrap, tray.get(i).node);
      assertEquals(2, tray.get(i).trayLabels.length);
    }
    // Reactions and Copy restore focus to the comment; Reply, Share, Report and Hide hand
    // over to Facebook's follow-up UI.
    boolean[] restores = {
      true, true, true, true, true, true, true, false, false, false, true, false
    };
    for (int i = 0; i < tray.size(); i++) {
      assertEquals(restores[i], tray.get(i).trayRestoreFocus);
    }
    assertTrue(tray.get(0).trayLabels[0].equals("Thích"));
    // Text selection still targets the node carrying the text, not the shell.
    assertEquals(tree.body, item.selectionTarget());
    assertNull(item.activationTarget());
  }

  @Test
  public void commentWithFollowButton_stillMatches() {
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
        labelsAroundTray(
            List.of(
                "Profile picture of Nguyen Van A",
                "Nguyen Van A",
                " · Follow",
                "This is a great comment"),
            List.of("5 reactions", "Demote comment")),
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
        labelsAroundTray(
            List.of(
                "Profile picture of Nguyen Van A",
                "Nguyen Van A",
                "This is a great comment",
                "See more"),
            List.of("5 reactions", "Demote comment")),
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
      } else {
        clickLabels.add(action.label.toString());
        if ("This is a great comment".contentEquals(action.label)) {
          assertEquals(tree.bodyActionTarget, action.node);
        }
      }
    }
    assertEquals(
        labelsAroundTray(
            List.of("Profile picture of Nguyen Van A", "Nguyen Van A", "This is a great comment"),
            List.of("5 reactions", "Demote comment")),
        clickLabels);
    assertEquals(0, longClickTwins);
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
        labelsAroundTray(
            List.of("Profile picture of Nguyen Van A", "Nguyen Van A", " · Follow", "Cool"),
            List.of("5 reactions", "Demote comment")),
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
        labelsAroundTray(
            List.of(
                "Profile picture of Nguyen Van A",
                "Nguyen Van A",
                "Fan cứng",
                "This is a great comment"),
            List.of("5 reactions", "Demote comment")),
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
  public void commentWithNineClickableChildren_matches() {
    // This used to exceed the action cap of 8; the cap is 12 since the recorded photo
    // comments showed real items reaching eleven actions after de-duplication.
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

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    int clickActions = 0;
    for (ChildAction action : item.actions()) {
      if (!action.longClick) {
        clickActions++;
      }
    }
    // Nine scanned click actions, of which Like and Reply are absorbed into the tray
    // set of twelve: 9 - 2 + 12. The cap counts the scanned children, not the tray set.
    assertEquals(19, clickActions);
  }

  @Test
  public void commentWithThirteenActions_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    for (int i = 0; i < 6; i++) {
      AccessibilityNodeInfoCompat extra =
          CollapseTestTrees.node(tree.root, "android.widget.Button");
      extra.setClickable(true);
      extra.setFocusable(true);
      extra.setContentDescription("Extra button " + i);
      CollapseTestTrees.bounds(extra, 0, 400 + i * 50, 200, 450 + i * 50);
      extra.setPackageName(CollapseTestTrees.FACEBOOK_PACKAGE);
    }

    assertNull(CollapseEngine.matchItemRoot(tree.root, SCRIPTS));
  }

  @Test
  public void commentWithPhoto_unflaggedRoot_matches_labelActionsAndActivation() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    list.setScrollable(true);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentWithPhoto(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    assertEquals(tree.root, item.representative());
    String separator = CompositorUtils.getSeparator();
    assertEquals(
        "Nguyen Van A" + separator + "1 day" + separator + "Hoang Tuan looks great in this photo",
        item.composedLabel());
    List<String> clickLabels = new ArrayList<>();
    int longClickTwins = 0;
    for (ChildAction action : item.actions()) {
      if (action.longClick) {
        longClickTwins++;
      } else {
        // No blank entries: the header's unlabelled clickable container is not an action.
        assertFalse(action.label.toString().isEmpty());
        clickLabels.add(action.label.toString());
        if ("Hoang Tuan looks great in this photo".contentEquals(action.label)) {
          assertEquals(tree.bodyActionTarget, action.node);
        } else if ("Hoang Tuan".contentEquals(action.label)) {
          // A mention inside the body text keeps its own target: the text node around it
          // is a content field, not an action block's wrapper.
          assertEquals(tree.mention, action.node);
        } else if ("May be an image of text".contentEquals(action.label)) {
          // Exactly one photo action, on the wrapper; the image itself is de-duplicated
          // into it and neither of them earns a long-press twin.
          assertEquals(tree.photoWrapper, action.node);
        }
      }
    }
    assertEquals(
        labelsAroundTray(
            List.of(
                "Profile picture of Nguyen Van A",
                "Nguyen Van A",
                "Hoang Tuan looks great in this photo",
                "Hoang Tuan",
                "May be an image of text"),
            List.of("5 reactions", "Demote comment")),
        clickLabels);
    assertEquals(0, longClickTwins);
    // The frame cannot be clicked, so a double-tap lands on the body shell, while text
    // selection still targets the node carrying the text.
    assertEquals(tree.bodyActionTarget, item.activationTarget());
    assertEquals(tree.body, item.selectionTarget());
  }

  @Test
  public void commentWithPhoto_heaviestRecordedCombination_stillMatches() {
    // Photo + follow button + reactions together: eleven scanned actions after
    // de-duplication, of which Like and Reply are absorbed into the tray set of twelve.
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    list.setScrollable(true);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentWithPhoto(list);
    AccessibilityNodeInfoCompat follow =
        CollapseTestTrees.node(tree.headerGroup, "android.widget.Button");
    follow.setClickable(true);
    follow.setFocusable(true);
    follow.setText(" · Follow");
    follow.setContentDescription(" · Follow");
    CollapseTestTrees.bounds(follow, 720, 0, 900, 60);
    AccessibilityNodeInfoCompat secondPhotoWrapper =
        CollapseTestTrees.node(tree.root, "android.view.ViewGroup");
    secondPhotoWrapper.setClickable(true);
    secondPhotoWrapper.setLongClickable(true);
    secondPhotoWrapper.setFocusable(true);
    CollapseTestTrees.bounds(secondPhotoWrapper, 520, 300, 1020, 500);
    AccessibilityNodeInfoCompat secondPhoto =
        CollapseTestTrees.node(secondPhotoWrapper, "android.widget.ImageView");
    secondPhoto.setClickable(true);
    secondPhoto.setFocusable(true);
    secondPhoto.setContentDescription("May be an image of a person");
    CollapseTestTrees.bounds(secondPhoto, 520, 300, 1020, 500);
    CollapseTestTrees.applyPackage(tree.root, CollapseTestTrees.FACEBOOK_PACKAGE);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    int clickActions = 0;
    for (ChildAction action : item.actions()) {
      if (!action.longClick) {
        clickActions++;
      }
    }
    assertEquals(21, clickActions);
  }

  @Test
  public void commentWithPhoto_notADirectChildOfTheScrollableList_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    list.setScrollable(true);
    AccessibilityNodeInfoCompat wrapper =
        CollapseTestTrees.node(list, "android.view.ViewGroup");
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentWithPhoto(wrapper);

    assertNull(CollapseEngine.matchItemRoot(tree.root, SCRIPTS));
  }

  @Test
  public void commentWithPhoto_withoutTimestamp_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    list.setScrollable(true);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentWithPhotoWithoutTime(list);

    assertNull(CollapseEngine.matchItemRoot(tree.root, SCRIPTS));
  }

  @Test
  public void commentWithPhoto_withCheckableChild_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    list.setScrollable(true);
    FacebookCommentTree tree = CollapseTestTrees.facebookCommentWithPhoto(list);
    AccessibilityNodeInfoCompat checkbox =
        CollapseTestTrees.node(tree.root, "android.widget.CheckBox");
    checkbox.setCheckable(true);
    checkbox.setPackageName(CollapseTestTrees.FACEBOOK_PACKAGE);

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
  public void actionTargets_stayOnTheButtons_whenTheWrapperCannotTakeThePress() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    for (ChildAction action : item.actions()) {
      if (action.longClick) {
        continue;
      }
      if ("Profile picture of Nguyen Van A".contentEquals(action.label)) {
        // The avatar's parent is a plain container carrying no flags at all.
        assertEquals(tree.avatar, action.node);
      } else if ("5 reactions".contentEquals(action.label)) {
        // The reactions frame carries no flags either, so it cannot take the press.
        assertEquals(tree.reactions, action.node);
      } else if ("Demote comment".contentEquals(action.label)) {
        // The demote button hangs directly off the item root.
        assertEquals(tree.demote, action.node);
      }
    }
  }

  @Test
  public void wrapperWrappingTwoClickables_keepsBothButtonTargets() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    AccessibilityNodeInfoCompat extra =
        CollapseTestTrees.node(tree.likeWrap, "android.widget.Button");
    extra.setClickable(true);
    extra.setFocusable(true);
    extra.setText("Extra");
    CollapseTestTrees.bounds(extra, 400, 300, 500, 400);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.root, SCRIPTS);

    assertNotNull(item);
    for (ChildAction action : item.actions()) {
      if (action.longClick) {
        continue;
      }
      if ("Like".contentEquals(action.label)) {
        // The scanned Like is absorbed into the tray set whatever its own target was:
        // the tray entry never presses the button or the frame it sits in.
        assertEquals(ChildAction.Kind.TRAY_ITEM, action.kind);
      } else if ("Extra".contentEquals(action.label)) {
        assertEquals(extra, action.node);
      }
    }
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
