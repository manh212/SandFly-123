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

import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.ArrayList;
import java.util.List;

/** Builds node trees shaped like the real YouTube and Facebook trees recorded for this feature. */
final class CollapseTestTrees {
  static final String YOUTUBE_PACKAGE = "com.google.android.youtube";
  static final String FACEBOOK_PACKAGE = "com.facebook.katana";

  private CollapseTestTrees() {}

  static AccessibilityNodeInfoCompat node(AccessibilityNodeInfoCompat parent, String className) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setClassName(className);
    info.setVisibleToUser(true);
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(info);
      // Real nodes always carry their app package; set it at creation because setting it
      // later through getChild() would only reach the obtained copies, not this node.
      CharSequence parentPackage = parent.getPackageName();
      if (parentPackage != null) {
        info.setPackageName(parentPackage.toString());
      }
    }
    return AccessibilityNodeInfoCompat.wrap(info);
  }

  static AccessibilityNodeInfoCompat list(String packageName) {
    AccessibilityNodeInfoCompat list = node(null, "androidx.recyclerview.widget.RecyclerView");
    list.unwrap()
        .setCollectionInfo(
            AccessibilityNodeInfo.CollectionInfo.obtain(
                /* rowCount= */ 10, /* columnCount= */ 1, /* hierarchical= */ false));
    applyPackage(list, packageName);
    return list;
  }

  static AccessibilityNodeInfoCompat item(AccessibilityNodeInfoCompat parent) {
    AccessibilityNodeInfoCompat item = node(parent, "android.view.ViewGroup");
    item.unwrap()
        .setCollectionItemInfo(
            AccessibilityNodeInfo.CollectionItemInfo.obtain(
                /* rowIndex= */ 0,
                /* rowSpan= */ 1,
                /* columnIndex= */ 0,
                /* columnSpan= */ 1,
                /* heading= */ false));
    return item;
  }

  static void applyPackage(AccessibilityNodeInfoCompat node, String packageName) {
    node.setPackageName(packageName);
    for (int i = 0; i < node.getChildCount(); i++) {
      applyPackage(node.getChild(i), packageName);
    }
  }

  static void bounds(AccessibilityNodeInfoCompat node, int left, int top, int right, int bottom) {
    node.setBoundsInScreen(new Rect(left, top, right, bottom));
  }

  static final class YouTubeTree {
    AccessibilityNodeInfoCompat item;
    AccessibilityNodeInfoCompat primary;
    AccessibilityNodeInfoCompat channel;
    AccessibilityNodeInfoCompat menu;
  }

  /**
   * One video item in the shape of the recorded live home feed: the item frame only takes
   * focus; the main button carries the composed description and holds, deep inside, a
   * duration badge chain and a container with the channel and menu buttons as siblings —
   * channel first, menu last. Both small buttons sit in the metadata strip at the item's
   * bottom, under where the thumbnail renders; nothing about the menu button's identity is
   * positional.
   */
  static YouTubeTree youTubeItem(AccessibilityNodeInfoCompat list, int top) {
    YouTubeTree tree = new YouTubeTree();
    tree.item = item(list);
    tree.item.setFocusable(true);
    bounds(tree.item, 0, top, 1080, top + 600);

    tree.primary = node(tree.item, "android.widget.Button");
    tree.primary.setClickable(true);
    tree.primary.setFocusable(true);
    tree.primary.setContentDescription(
        "Video title - 10 minutes, 24 seconds - Go to channel Channel X - Channel X,"
            + " 1M views - 15 minutes ago - play video");
    bounds(tree.primary, 0, top, 1080, top + 600);

    AccessibilityNodeInfoCompat durationChain = node(tree.primary, "android.view.ViewGroup");
    bounds(durationChain, 880, top + 380, 1080, top + 440);
    AccessibilityNodeInfoCompat duration = node(durationChain, "android.view.ViewGroup");
    duration.setText("10:24");
    duration.setContentDescription("10 minutes, 24 seconds");
    bounds(duration, 880, top + 380, 1080, top + 440);

    AccessibilityNodeInfoCompat stripChain = node(tree.primary, "android.view.ViewGroup");
    bounds(stripChain, 0, top + 450, 1080, top + 600);
    AccessibilityNodeInfoCompat strip = node(stripChain, "android.view.ViewGroup");
    bounds(strip, 0, top + 450, 1080, top + 600);
    AccessibilityNodeInfoCompat buttons = node(strip, "android.view.ViewGroup");
    bounds(buttons, 0, top + 450, 1080, top + 600);

    tree.channel = node(buttons, "android.widget.Button");
    tree.channel.setClickable(true);
    tree.channel.setFocusable(true);
    tree.channel.setContentDescription("Go to channel Channel X");
    bounds(tree.channel, 0, top + 450, 150, top + 600);

    tree.menu = node(buttons, "android.widget.Button");
    tree.menu.setClickable(true);
    tree.menu.setFocusable(true);
    tree.menu.setContentDescription("Action menu for Video title");
    bounds(tree.menu, 930, top + 450, 1080, top + 600);
    applyPackage(tree.item, list.getPackageName().toString());
    return tree;
  }

  /**
   * A recorded variance: a video item whose main button has no child buttons rendered yet.
   * There is nothing to fold in, so the item must simply be left untouched.
   */
  static YouTubeTree youTubeItemWithoutChildren(AccessibilityNodeInfoCompat list, int top) {
    YouTubeTree tree = new YouTubeTree();
    tree.item = item(list);
    tree.item.setFocusable(true);
    bounds(tree.item, 0, top, 1080, top + 600);

    tree.primary = node(tree.item, "android.widget.Button");
    tree.primary.setClickable(true);
    tree.primary.setFocusable(true);
    tree.primary.setContentDescription("Video title - 10 minutes, 24 seconds - play video");
    bounds(tree.primary, 0, top, 1080, top + 600);
    applyPackage(tree.item, list.getPackageName().toString());
    return tree;
  }

  static final String YOUTUBE_MENU_LABEL_VIEW_ID =
      "com.google.android.youtube:id/list_item_text";

  static final class YouTubeSheetTree {
    AccessibilityNodeInfoCompat root;
    final List<AccessibilityNodeInfoCompat> rows = new ArrayList<>();
  }

  /** A video's open overflow-menu sheet: a list of buttons labelled by a text child. */
  static YouTubeSheetTree youTubeMenuSheet(String... labels) {
    YouTubeSheetTree tree = new YouTubeSheetTree();
    tree.root = node(null, "android.widget.FrameLayout");
    AccessibilityNodeInfoCompat sheet = node(tree.root, "android.widget.FrameLayout");
    sheet.setClickable(true);
    sheet.setFocusable(true);
    AccessibilityNodeInfoCompat layout = node(sheet, "android.widget.LinearLayout");
    layout.setClickable(true);
    layout.setFocusable(true);
    AccessibilityNodeInfoCompat sheetList =
        node(layout, "androidx.recyclerview.widget.RecyclerView");
    sheetList.setFocusable(true);
    for (String label : labels) {
      AccessibilityNodeInfoCompat row = node(sheetList, "android.widget.Button");
      row.setClickable(true);
      row.setFocusable(true);
      AccessibilityNodeInfoCompat text = node(row, "android.widget.TextView");
      text.unwrap().setViewIdResourceName(YOUTUBE_MENU_LABEL_VIEW_ID);
      text.setText(label);
      tree.rows.add(row);
    }
    applyPackage(tree.root, YOUTUBE_PACKAGE);
    return tree;
  }

  static final class FacebookCommentTree {
    AccessibilityNodeInfoCompat root;
    AccessibilityNodeInfoCompat headerGroup;
    AccessibilityNodeInfoCompat avatar;
    AccessibilityNodeInfoCompat author;
    AccessibilityNodeInfoCompat time;
    /** The node carrying the body text (the text-selection target). */
    AccessibilityNodeInfoCompat body;
    /** The clickable node the body action lands on: the shell, or the body itself. */
    AccessibilityNodeInfoCompat bodyActionTarget;
    AccessibilityNodeInfoCompat follow;
    AccessibilityNodeInfoCompat badge;
    AccessibilityNodeInfoCompat seeMore;
    AccessibilityNodeInfoCompat unlabeledContainer;
    AccessibilityNodeInfoCompat mention;
    AccessibilityNodeInfoCompat photoWrapper;
    AccessibilityNodeInfoCompat photo;
    AccessibilityNodeInfoCompat reply;
    AccessibilityNodeInfoCompat reactions;
    AccessibilityNodeInfoCompat like;
    AccessibilityNodeInfoCompat demote;
  }

  /**
   * A Facebook comment as recorded from the live app: the body text sits on a plain button
   * with no flags at all, inside a clickable shell that carries no text. Seven clickable
   * descendants, counting the reactions button.
   */
  static FacebookCommentTree facebookComment(AccessibilityNodeInfoCompat list) {
    return facebookComment(list, /* splitBody= */ true, /* withFollow= */ false);
  }

  /** A live-shaped comment that also carries a follow button in its header (8 clickables). */
  static FacebookCommentTree facebookCommentWithFollow(AccessibilityNodeInfoCompat list) {
    return facebookComment(list, /* splitBody= */ true, /* withFollow= */ true);
  }

  /**
   * The earlier recorded shape: the body is itself a clickable button carrying its text, with
   * a nested "see more" button inside it.
   */
  static FacebookCommentTree facebookCommentClickableBody(AccessibilityNodeInfoCompat list) {
    return facebookComment(list, /* splitBody= */ false, /* withFollow= */ false);
  }

  private static FacebookCommentTree facebookComment(
      AccessibilityNodeInfoCompat list, boolean splitBody, boolean withFollow) {
    FacebookCommentTree tree = new FacebookCommentTree();
    tree.root = item(list);
    tree.root.setClickable(true);
    tree.root.setFocusable(true);
    bounds(tree.root, 0, 0, 1080, 400);

    AccessibilityNodeInfoCompat header = node(tree.root, "android.view.ViewGroup");
    header.setLongClickable(true);
    bounds(header, 0, 0, 1080, 300);
    AccessibilityNodeInfoCompat content = node(header, "android.view.ViewGroup");
    bounds(content, 0, 0, 1080, 300);

    tree.avatar = node(content, "android.widget.ImageView");
    tree.avatar.setClickable(true);
    tree.avatar.setFocusable(true);
    tree.avatar.setContentDescription("Profile picture of Nguyen Van A");
    bounds(tree.avatar, 0, 0, 120, 120);

    tree.author = node(content, "android.view.ViewGroup");
    tree.author.setClickable(true);
    tree.author.setFocusable(true);
    tree.author.setText("Nguyen Van A");
    tree.author.setContentDescription("Nguyen Van A");
    bounds(tree.author, 130, 0, 500, 60);

    tree.time = node(content, "android.view.ViewGroup");
    tree.time.setFocusable(true);
    tree.time.setText("1 day");
    tree.time.setContentDescription("1 day");
    bounds(tree.time, 130, 60, 300, 110);

    if (withFollow) {
      tree.follow = node(content, "android.widget.Button");
      tree.follow.setClickable(true);
      tree.follow.setFocusable(true);
      tree.follow.setText(" · Follow");
      tree.follow.setContentDescription(" · Follow");
      bounds(tree.follow, 510, 0, 700, 60);
    }

    if (splitBody) {
      AccessibilityNodeInfoCompat shell = node(content, "android.view.ViewGroup");
      shell.setClickable(true);
      shell.setLongClickable(true);
      shell.setFocusable(true);
      bounds(shell, 0, 120, 1080, 300);
      tree.body = node(shell, "android.widget.Button");
      tree.body.setText("This is a great comment");
      tree.body.setContentDescription("This is a great comment");
      bounds(tree.body, 0, 120, 1080, 300);
      tree.bodyActionTarget = shell;
    } else {
      tree.body = node(content, "android.widget.Button");
      tree.body.setClickable(true);
      tree.body.setFocusable(true);
      tree.body.setText("This is a great comment");
      tree.body.setContentDescription("This is a great comment");
      bounds(tree.body, 0, 120, 1080, 300);
      tree.bodyActionTarget = tree.body;

      tree.seeMore = node(tree.body, "android.widget.Button");
      tree.seeMore.setClickable(true);
      tree.seeMore.setFocusable(true);
      tree.seeMore.setText("See more");
      bounds(tree.seeMore, 900, 250, 1080, 300);
    }

    String replyDescription =
        "Reply to the comment by Nguyen Van A, button. Double-tap to reply to the comment.";
    AccessibilityNodeInfoCompat replyOuter = node(tree.root, "android.view.ViewGroup");
    bounds(replyOuter, 0, 300, 200, 400);
    AccessibilityNodeInfoCompat replyWrap = node(replyOuter, "android.view.ViewGroup");
    replyWrap.setFocusable(true);
    replyWrap.setText(replyDescription);
    replyWrap.setContentDescription(replyDescription);
    bounds(replyWrap, 0, 300, 200, 400);
    tree.reply = node(replyWrap, "android.widget.Button");
    tree.reply.setClickable(true);
    tree.reply.setFocusable(true);
    tree.reply.setText("Reply");
    tree.reply.setContentDescription(replyDescription);
    bounds(tree.reply, 0, 300, 200, 400);

    AccessibilityNodeInfoCompat reactionsWrap = node(tree.root, "android.widget.Button");
    bounds(reactionsWrap, 200, 300, 400, 400);
    tree.reactions = node(reactionsWrap, "android.widget.Button");
    tree.reactions.setClickable(true);
    tree.reactions.setFocusable(true);
    tree.reactions.setText("5 reactions");
    tree.reactions.setContentDescription("5 reactions");
    bounds(tree.reactions, 200, 300, 400, 400);

    String likeDescription =
        "Like button for the comment by Nguyen Van A."
            + " Double-tap and hold to show the reaction tray.";
    AccessibilityNodeInfoCompat likeWrap = node(tree.root, "android.view.ViewGroup");
    likeWrap.setFocusable(true);
    likeWrap.setText(likeDescription);
    likeWrap.setContentDescription(likeDescription);
    bounds(likeWrap, 400, 300, 600, 400);
    tree.like = node(likeWrap, "android.widget.Button");
    tree.like.setClickable(true);
    tree.like.setLongClickable(true);
    tree.like.setFocusable(true);
    tree.like.setText("Like");
    tree.like.setContentDescription(likeDescription);
    bounds(tree.like, 400, 300, 600, 400);

    tree.demote = node(tree.root, "android.widget.Button");
    tree.demote.setClickable(true);
    tree.demote.setFocusable(true);
    tree.demote.setContentDescription("Demote comment");
    bounds(tree.demote, 600, 300, 900, 400);
    applyPackage(tree.root, list.getPackageName().toString());
    return tree;
  }

  /**
   * The third recorded comment shape: the item root has a single long-clickable container
   * child, and the reply, reactions, like and demote blocks are children of that container —
   * siblings of the header group, one nesting level higher than in {@link #facebookComment}.
   * The Like block's wrapper carries a long instruction label that a whole-item body search
   * would mistake for the comment text.
   */
  static FacebookCommentTree facebookCommentNestedActions(AccessibilityNodeInfoCompat list) {
    return facebookCommentNested(list, "This is a great comment", false, false);
  }

  /** A nested-shape comment whose body is shorter than its header's follow button label. */
  static FacebookCommentTree facebookCommentNestedShortBodyWithFollow(
      AccessibilityNodeInfoCompat list) {
    return facebookCommentNested(list, "Cool", true, false);
  }

  /** A nested-shape comment carrying a "top fan" badge inside its header group. */
  static FacebookCommentTree facebookCommentNestedWithBadge(AccessibilityNodeInfoCompat list) {
    return facebookCommentNested(list, "This is a great comment", false, true);
  }

  private static FacebookCommentTree facebookCommentNested(
      AccessibilityNodeInfoCompat list, String bodyText, boolean withFollow, boolean withBadge) {
    FacebookCommentTree tree = new FacebookCommentTree();
    tree.root = item(list);
    tree.root.setClickable(true);
    tree.root.setFocusable(true);
    bounds(tree.root, 0, 0, 1080, 500);

    AccessibilityNodeInfoCompat container = node(tree.root, "android.view.ViewGroup");
    container.setLongClickable(true);
    bounds(container, 0, 0, 1080, 500);
    AccessibilityNodeInfoCompat header = node(container, "android.view.ViewGroup");
    bounds(header, 0, 0, 1080, 300);

    tree.avatar = node(header, "android.widget.ImageView");
    tree.avatar.setClickable(true);
    tree.avatar.setFocusable(true);
    tree.avatar.setContentDescription("Profile picture of Nguyen Van A");
    bounds(tree.avatar, 0, 0, 120, 120);

    tree.author = node(header, "android.view.ViewGroup");
    tree.author.setClickable(true);
    tree.author.setFocusable(true);
    tree.author.setText("Nguyen Van A");
    tree.author.setContentDescription("Nguyen Van A");
    bounds(tree.author, 130, 0, 500, 60);

    tree.time = node(header, "android.view.ViewGroup");
    tree.time.setFocusable(true);
    tree.time.setText("1 day");
    tree.time.setContentDescription("1 day");
    bounds(tree.time, 130, 60, 300, 110);

    if (withFollow) {
      tree.follow = node(header, "android.widget.Button");
      tree.follow.setClickable(true);
      tree.follow.setFocusable(true);
      tree.follow.setText(" · Follow");
      tree.follow.setContentDescription(" · Follow");
      bounds(tree.follow, 510, 0, 700, 60);
    }

    if (withBadge) {
      tree.badge = node(header, "android.view.ViewGroup");
      tree.badge.setClickable(true);
      tree.badge.setFocusable(true);
      bounds(tree.badge, 130, 110, 420, 170);
      AccessibilityNodeInfoCompat badgeSeparator = node(tree.badge, "android.view.ViewGroup");
      badgeSeparator.setText(" · ");
      badgeSeparator.setContentDescription(" · ");
      bounds(badgeSeparator, 130, 110, 180, 170);
      node(tree.badge, "android.widget.ImageView");
      AccessibilityNodeInfoCompat badgeText = node(tree.badge, "android.view.ViewGroup");
      badgeText.setText("Fan cứng");
      badgeText.setContentDescription("Fan cứng");
      bounds(badgeText, 240, 110, 420, 170);
    }

    AccessibilityNodeInfoCompat shell = node(header, "android.view.ViewGroup");
    shell.setClickable(true);
    shell.setLongClickable(true);
    shell.setFocusable(true);
    bounds(shell, 0, 120, 1080, 300);
    tree.body = node(shell, "android.widget.Button");
    tree.body.setText(bodyText);
    tree.body.setContentDescription(bodyText);
    bounds(tree.body, 0, 120, 1080, 300);
    tree.bodyActionTarget = shell;

    String replyDescription =
        "Reply to the comment by Nguyen Van A, button. Double-tap to reply to the comment.";
    AccessibilityNodeInfoCompat replyOuter = node(container, "android.view.ViewGroup");
    bounds(replyOuter, 0, 300, 200, 400);
    AccessibilityNodeInfoCompat replyWrap = node(replyOuter, "android.view.ViewGroup");
    replyWrap.setFocusable(true);
    replyWrap.setText(replyDescription);
    replyWrap.setContentDescription(replyDescription);
    bounds(replyWrap, 0, 300, 200, 400);
    tree.reply = node(replyWrap, "android.widget.Button");
    tree.reply.setClickable(true);
    tree.reply.setFocusable(true);
    tree.reply.setText("Reply");
    tree.reply.setContentDescription(replyDescription);
    bounds(tree.reply, 0, 300, 200, 400);

    AccessibilityNodeInfoCompat reactionsWrap = node(container, "android.widget.Button");
    bounds(reactionsWrap, 200, 300, 400, 400);
    tree.reactions = node(reactionsWrap, "android.widget.Button");
    tree.reactions.setClickable(true);
    tree.reactions.setFocusable(true);
    tree.reactions.setText("5 reactions");
    tree.reactions.setContentDescription("5 reactions");
    bounds(tree.reactions, 200, 300, 400, 400);

    String likeDescription =
        "Like button for the comment by Nguyen Van A."
            + " Double-tap and hold to show the reaction tray.";
    AccessibilityNodeInfoCompat likeWrap = node(container, "android.view.ViewGroup");
    likeWrap.setFocusable(true);
    likeWrap.setText(likeDescription);
    likeWrap.setContentDescription(likeDescription);
    bounds(likeWrap, 400, 300, 600, 400);
    tree.like = node(likeWrap, "android.widget.Button");
    tree.like.setClickable(true);
    tree.like.setLongClickable(true);
    tree.like.setFocusable(true);
    tree.like.setText("Like");
    tree.like.setContentDescription(likeDescription);
    bounds(tree.like, 400, 300, 600, 400);

    tree.demote = node(container, "android.widget.Button");
    tree.demote.setClickable(true);
    tree.demote.setFocusable(true);
    tree.demote.setContentDescription("Demote comment");
    bounds(tree.demote, 600, 300, 900, 400);
    applyPackage(tree.root, list.getPackageName().toString());
    return tree;
  }

  /**
   * The fourth recorded comment shape: a reply with an attached photo. The item frame
   * carries no clickable or focusable flags at all; the header also holds an unlabelled
   * clickable container with three empty children, the body text button is focusable only
   * and nests a tappable mention, and the photo block's wrapper and image are both
   * clickable and long-clickable. Eleven clickable descendants before de-duplication.
   */
  static FacebookCommentTree facebookCommentWithPhoto(AccessibilityNodeInfoCompat list) {
    return facebookCommentWithPhoto(list, /* withTime= */ true);
  }

  /** The photo comment shape with no timestamp node: a required field is missing. */
  static FacebookCommentTree facebookCommentWithPhotoWithoutTime(
      AccessibilityNodeInfoCompat list) {
    return facebookCommentWithPhoto(list, /* withTime= */ false);
  }

  private static FacebookCommentTree facebookCommentWithPhoto(
      AccessibilityNodeInfoCompat parent, boolean withTime) {
    FacebookCommentTree tree = new FacebookCommentTree();
    tree.root = item(parent);
    bounds(tree.root, 0, 0, 1080, 700);

    AccessibilityNodeInfoCompat container = node(tree.root, "android.view.ViewGroup");
    container.setLongClickable(true);
    bounds(container, 0, 0, 1080, 700);
    AccessibilityNodeInfoCompat header = node(container, "android.view.ViewGroup");
    bounds(header, 0, 0, 1080, 300);
    tree.headerGroup = header;

    tree.avatar = node(header, "android.widget.ImageView");
    tree.avatar.setClickable(true);
    tree.avatar.setFocusable(true);
    tree.avatar.setContentDescription("Profile picture of Nguyen Van A");
    bounds(tree.avatar, 0, 0, 120, 120);

    tree.author = node(header, "android.view.ViewGroup");
    tree.author.setClickable(true);
    tree.author.setFocusable(true);
    tree.author.setText("Nguyen Van A");
    tree.author.setContentDescription("Nguyen Van A");
    bounds(tree.author, 130, 0, 500, 60);

    tree.unlabeledContainer = node(header, "android.view.ViewGroup");
    tree.unlabeledContainer.setClickable(true);
    tree.unlabeledContainer.setFocusable(true);
    bounds(tree.unlabeledContainer, 510, 0, 700, 110);
    for (int i = 0; i < 3; i++) {
      node(tree.unlabeledContainer, "android.view.ViewGroup");
    }

    if (withTime) {
      tree.time = node(header, "android.view.ViewGroup");
      tree.time.setFocusable(true);
      tree.time.setText("1 day");
      tree.time.setContentDescription("1 day");
      bounds(tree.time, 130, 60, 300, 110);
    }

    AccessibilityNodeInfoCompat shell = node(header, "android.view.ViewGroup");
    shell.setClickable(true);
    shell.setLongClickable(true);
    shell.setFocusable(true);
    bounds(shell, 0, 120, 1080, 300);
    tree.body = node(shell, "android.widget.Button");
    tree.body.setFocusable(true);
    tree.body.setText("Hoang Tuan looks great in this photo");
    tree.body.setContentDescription("Hoang Tuan looks great in this photo");
    bounds(tree.body, 0, 120, 1080, 300);
    tree.mention = node(tree.body, "android.widget.Button");
    tree.mention.setClickable(true);
    tree.mention.setFocusable(true);
    tree.mention.setText("Hoang Tuan");
    bounds(tree.mention, 0, 120, 300, 200);
    tree.bodyActionTarget = shell;

    tree.photoWrapper = node(container, "android.view.ViewGroup");
    tree.photoWrapper.setClickable(true);
    tree.photoWrapper.setLongClickable(true);
    tree.photoWrapper.setFocusable(true);
    bounds(tree.photoWrapper, 0, 300, 500, 500);
    tree.photo = node(tree.photoWrapper, "android.widget.ImageView");
    tree.photo.setClickable(true);
    tree.photo.setLongClickable(true);
    tree.photo.setFocusable(true);
    tree.photo.setContentDescription("May be an image of text");
    bounds(tree.photo, 0, 300, 500, 500);

    String replyDescription =
        "Reply to the comment by Nguyen Van A, button. Double-tap to reply to the comment.";
    AccessibilityNodeInfoCompat replyOuter = node(container, "android.view.ViewGroup");
    bounds(replyOuter, 0, 500, 200, 600);
    AccessibilityNodeInfoCompat replyWrap = node(replyOuter, "android.view.ViewGroup");
    replyWrap.setFocusable(true);
    replyWrap.setText(replyDescription);
    replyWrap.setContentDescription(replyDescription);
    bounds(replyWrap, 0, 500, 200, 600);
    tree.reply = node(replyWrap, "android.widget.Button");
    tree.reply.setClickable(true);
    tree.reply.setFocusable(true);
    tree.reply.setText("Reply");
    tree.reply.setContentDescription(replyDescription);
    bounds(tree.reply, 0, 500, 200, 600);

    AccessibilityNodeInfoCompat reactionsWrap = node(container, "android.widget.Button");
    bounds(reactionsWrap, 200, 500, 400, 600);
    tree.reactions = node(reactionsWrap, "android.widget.Button");
    tree.reactions.setClickable(true);
    tree.reactions.setFocusable(true);
    tree.reactions.setText("5 reactions");
    tree.reactions.setContentDescription("5 reactions");
    bounds(tree.reactions, 200, 500, 400, 600);

    String likeDescription =
        "Like button for the comment by Nguyen Van A."
            + " Double-tap and hold to show the reaction tray.";
    AccessibilityNodeInfoCompat likeWrap = node(container, "android.view.ViewGroup");
    likeWrap.setFocusable(true);
    likeWrap.setText(likeDescription);
    likeWrap.setContentDescription(likeDescription);
    bounds(likeWrap, 400, 500, 600, 600);
    tree.like = node(likeWrap, "android.widget.Button");
    tree.like.setClickable(true);
    tree.like.setLongClickable(true);
    tree.like.setFocusable(true);
    tree.like.setText("Like");
    tree.like.setContentDescription(likeDescription);
    bounds(tree.like, 400, 500, 600, 600);

    tree.demote = node(container, "android.widget.Button");
    tree.demote.setClickable(true);
    tree.demote.setFocusable(true);
    tree.demote.setContentDescription("Demote comment");
    bounds(tree.demote, 600, 500, 900, 600);
    applyPackage(tree.root, parent.getPackageName().toString());
    return tree;
  }

  /** A Facebook feed post: already a single stop with Facebook's own custom actions. */
  static AccessibilityNodeInfoCompat facebookPost(AccessibilityNodeInfoCompat list) {
    AccessibilityNodeInfoCompat post = item(list);
    post.setFocusable(true);
    bounds(post, 0, 0, 1080, 500);
    AccessibilityNodeInfoCompat content = node(post, "android.view.ViewGroup");
    content.setClickable(true);
    content.setLongClickable(true);
    content.setFocusable(true);
    content.setContentDescription("Group name, Author, Yesterday, the full post text");
    content.addAction(
        new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            0x10001, "Open the full post"));
    content.addAction(
        new AccessibilityNodeInfoCompat.AccessibilityActionCompat(0x10002, "Open action menu"));
    bounds(content, 0, 0, 1080, 500);
    applyPackage(post, list.getPackageName().toString());
    return post;
  }
}
