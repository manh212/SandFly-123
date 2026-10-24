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

  /** One video item: a full-size primary button with channel and menu buttons nested inside. */
  static YouTubeTree youTubeItem(AccessibilityNodeInfoCompat list, int top) {
    YouTubeTree tree = new YouTubeTree();
    tree.item = item(list);
    tree.item.setFocusable(true);
    bounds(tree.item, 0, top, 1080, top + 600);

    tree.primary = node(tree.item, "android.widget.Button");
    tree.primary.setClickable(true);
    tree.primary.setFocusable(true);
    tree.primary.setContentDescription(
        "Video title, 10:24, Channel X, 1M views, play video");
    bounds(tree.primary, 0, top, 1080, top + 600);

    tree.channel = node(tree.primary, "android.widget.Button");
    tree.channel.setClickable(true);
    tree.channel.setFocusable(true);
    tree.channel.setContentDescription("Go to channel Channel X");
    bounds(tree.channel, 0, top + 400, 300, top + 600);

    tree.menu = node(tree.primary, "android.widget.Button");
    tree.menu.setClickable(true);
    tree.menu.setFocusable(true);
    tree.menu.setContentDescription("Action menu for Video title");
    bounds(tree.menu, 900, top, 1080, top + 150);
    applyPackage(tree.item, list.getPackageName().toString());
    return tree;
  }

  static final class FacebookCommentTree {
    AccessibilityNodeInfoCompat root;
    AccessibilityNodeInfoCompat avatar;
    AccessibilityNodeInfoCompat author;
    AccessibilityNodeInfoCompat time;
    AccessibilityNodeInfoCompat body;
    AccessibilityNodeInfoCompat seeMore;
    AccessibilityNodeInfoCompat reply;
    AccessibilityNodeInfoCompat reactions;
    AccessibilityNodeInfoCompat like;
    AccessibilityNodeInfoCompat demote;
  }

  /** A Facebook comment, structured as recorded from the real app. */
  static FacebookCommentTree facebookComment(AccessibilityNodeInfoCompat list) {
    FacebookCommentTree tree = new FacebookCommentTree();
    tree.root = item(list);
    tree.root.setClickable(true);
    tree.root.setFocusable(true);
    bounds(tree.root, 0, 0, 1080, 400);

    AccessibilityNodeInfoCompat header = node(tree.root, "android.view.ViewGroup");
    header.setLongClickable(true);
    header.setFocusable(true);
    bounds(header, 0, 0, 1080, 300);

    tree.avatar = node(header, "android.widget.ImageButton");
    tree.avatar.setClickable(true);
    tree.avatar.setFocusable(true);
    tree.avatar.setContentDescription("Profile picture of Nguyen Van A");
    bounds(tree.avatar, 0, 0, 120, 120);

    tree.author = node(header, "android.view.ViewGroup");
    tree.author.setClickable(true);
    tree.author.setFocusable(true);
    tree.author.setText("Nguyen Van A");
    bounds(tree.author, 130, 0, 500, 60);

    tree.time = node(header, "android.widget.TextView");
    tree.time.setFocusable(true);
    tree.time.setText("1 day");
    bounds(tree.time, 130, 60, 300, 110);

    tree.body = node(header, "android.widget.Button");
    tree.body.setClickable(true);
    tree.body.setFocusable(true);
    tree.body.setText("This is a great comment");
    bounds(tree.body, 0, 120, 1080, 300);

    tree.seeMore = node(tree.body, "android.widget.Button");
    tree.seeMore.setClickable(true);
    tree.seeMore.setFocusable(true);
    tree.seeMore.setText("See more");
    bounds(tree.seeMore, 900, 250, 1080, 300);

    AccessibilityNodeInfoCompat replyWrap = node(tree.root, "android.view.ViewGroup");
    replyWrap.setFocusable(true);
    bounds(replyWrap, 0, 300, 200, 400);
    tree.reply = node(replyWrap, "android.widget.Button");
    tree.reply.setClickable(true);
    tree.reply.setFocusable(true);
    tree.reply.setText("Reply");
    bounds(tree.reply, 0, 300, 200, 400);

    AccessibilityNodeInfoCompat reactionsWrap = node(tree.root, "android.widget.Button");
    bounds(reactionsWrap, 200, 300, 400, 400);
    tree.reactions = node(reactionsWrap, "android.widget.Button");
    tree.reactions.setClickable(true);
    tree.reactions.setFocusable(true);
    tree.reactions.setText("5 reactions");
    bounds(tree.reactions, 200, 300, 400, 400);

    AccessibilityNodeInfoCompat likeWrap = node(tree.root, "android.view.ViewGroup");
    likeWrap.setFocusable(true);
    bounds(likeWrap, 400, 300, 600, 400);
    tree.like = node(likeWrap, "android.widget.Button");
    tree.like.setClickable(true);
    tree.like.setLongClickable(true);
    tree.like.setFocusable(true);
    tree.like.setText("Like");
    tree.like.setContentDescription(
        "Like button for the comment by Nguyen Van A. Double-tap and hold for more options.");
    bounds(tree.like, 400, 300, 600, 400);

    tree.demote = node(tree.root, "android.widget.Button");
    tree.demote.setClickable(true);
    tree.demote.setFocusable(true);
    tree.demote.setContentDescription("Demote comment");
    bounds(tree.demote, 600, 300, 900, 400);
    applyPackage(tree.root, list.getPackageName().toString());
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
