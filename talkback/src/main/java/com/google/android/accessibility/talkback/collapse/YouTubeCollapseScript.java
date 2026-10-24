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

import android.graphics.Rect;
import android.text.TextUtils;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube: a video item is one large button (title, duration, channel, views) with small child
 * buttons nested inside it (go to channel, menu). The large button stays the single stop; the
 * child buttons become actions. The menu button is expanded into the items of the video's own
 * overflow menu, declared by {@link #menuCatalog()}.
 */
final class YouTubeCollapseScript extends CollapseScript {
  static final String PACKAGE_NAME = "com.google.android.youtube";

  /** View id of the TextView carrying one row's label inside the open overflow-menu sheet. */
  private static final String MENU_ITEM_LABEL_VIEW_ID =
      "com.google.android.youtube:id/list_item_text";

  /**
   * Wording of the overflow button's description as recorded from YouTube's own live
   * trees: "Action menu for &lt;title&gt;", in Vietnamese "Trình đơn thao tác cho
   * &lt;tiêu đề&gt;". These are YouTube's words, not SandFly strings; the match is by
   * prefix because the video title follows.
   */
  private static final String[] MENU_DESCRIPTION_PREFIXES = {
    "Action menu for", "Trình đơn thao tác cho",
  };

  /** Wording of the neighbouring go-to-channel button's description, from the same trees. */
  private static final String[] CHANNEL_DESCRIPTION_PREFIXES = {
    "Go to channel", "Chuyển đến kênh",
  };

  private static final MenuCatalog MENU_CATALOG =
      new MenuCatalog(
          new int[] {
            R.string.title_collapse_menu_play_on_device,
            R.string.title_collapse_menu_play_next,
            R.string.title_collapse_menu_play_last,
            R.string.title_collapse_menu_watch_later,
            R.string.title_collapse_menu_save_to_playlist,
            R.string.title_collapse_menu_remove_download,
            R.string.title_collapse_menu_share,
            R.string.title_collapse_menu_not_interested,
            R.string.title_collapse_menu_dont_recommend,
            R.string.title_collapse_menu_report,
            R.string.title_collapse_menu_youtube_music,
          },
          MENU_ITEM_LABEL_VIEW_ID,
          R.string.title_collapse_menu_open);

  YouTubeCollapseScript() {
    super(PACKAGE_NAME, Pattern.PRIMARY_CHILD, /* maxActions= */ 4);
  }

  @Override
  public MenuCatalog menuCatalog() {
    return MENU_CATALOG;
  }

  /**
   * The overflow button is identified by what YouTube calls it and by the company it keeps,
   * never by where it sits: on a feed row the button lives in the metadata strip under the
   * thumbnail, in the item's bottom half, so a position rule misses it there — and recorded
   * trees carry no bounds to check a position rule against anyway. Two signals, in order:
   *
   * <ol>
   *   <li>The button's spoken label carries YouTube's menu wording ({@link
   *       #MENU_DESCRIPTION_PREFIXES}).
   *   <li>Structure, for a menu wording that is not recorded: the button is the last of the
   *       item's secondary buttons in reading order and every secondary before it is a
   *       channel button. Anything less settled than that stays unidentified, and the
   *       engine's exactly-one gate then leaves the item's actions untouched.
   * </ol>
   */
  @Override
  public boolean isMenuButton(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat itemRoot) {
    CharSequence label = ItemScan.spokenLabel(node);
    if (startsWithAny(label, MENU_DESCRIPTION_PREFIXES)) {
      return true;
    }
    if (startsWithAny(label, CHANNEL_DESCRIPTION_PREFIXES)) {
      return false;
    }
    return isLastSecondaryAfterChannelButtons(node, itemRoot);
  }

  private boolean isLastSecondaryAfterChannelButtons(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat itemRoot) {
    ItemScan scan = ItemScan.of(itemRoot);
    if (scan == null) {
      return false;
    }
    long itemArea = area(itemRoot);
    if (itemArea <= 0) {
      return false;
    }
    // Rebuild the engine's action set for this item — its primary first, then the labelled
    // small clickables with nested duplicates removed — so "last" is judged on the same
    // list the engine is about to expand, not on the raw scan.
    AccessibilityNodeInfoCompat primary = null;
    for (AccessibilityNodeInfoCompat candidate : scan.clickables()) {
      if ((double) area(candidate) >= primaryMinAreaFraction() * itemArea
          && !TextUtils.isEmpty(ItemScan.spokenLabel(candidate))) {
        if (primary != null) {
          return false;
        }
        primary = candidate;
      }
    }
    if (primary == null) {
      return false;
    }
    List<AccessibilityNodeInfoCompat> secondaries = new ArrayList<>();
    for (AccessibilityNodeInfoCompat candidate : scan.clickables()) {
      if (candidate.equals(primary)) {
        continue;
      }
      if (AccessibilityNodeInfoUtils.hasAncestor(primary, candidate)
          || TextUtils.isEmpty(ItemScan.spokenLabel(candidate))
          || (double) area(candidate) > auxMaxAreaFraction() * itemArea) {
        // The engine rejects an item carrying such a candidate outright, so no expansion
        // is in play for it either way.
        return false;
      }
      boolean nestedDuplicate = false;
      for (AccessibilityNodeInfoCompat earlier : secondaries) {
        if (TextUtils.equals(ItemScan.spokenLabel(earlier), ItemScan.spokenLabel(candidate))
            && AccessibilityNodeInfoUtils.hasAncestor(candidate, earlier)) {
          nestedDuplicate = true;
          break;
        }
      }
      if (!nestedDuplicate) {
        secondaries.add(candidate);
      }
    }
    if (secondaries.size() < 2 || !secondaries.get(secondaries.size() - 1).equals(node)) {
      return false;
    }
    for (int i = 0; i < secondaries.size() - 1; i++) {
      if (!startsWithAny(
          ItemScan.spokenLabel(secondaries.get(i)), CHANNEL_DESCRIPTION_PREFIXES)) {
        return false;
      }
    }
    return true;
  }

  private static boolean startsWithAny(CharSequence label, String[] prefixes) {
    if (TextUtils.isEmpty(label)) {
      return false;
    }
    String text = label.toString();
    for (String prefix : prefixes) {
      if (text.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static long area(AccessibilityNodeInfoCompat node) {
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    return (long) bounds.width() * bounds.height();
  }
}
