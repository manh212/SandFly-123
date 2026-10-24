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
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;

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
   * The overflow button is the small button in the item's top corner opposite the channel
   * button: its centre sits in the end half of the item horizontally and in the top half
   * vertically. The channel button fails both halves (bottom strip, start side).
   */
  @Override
  public boolean isMenuButton(
      AccessibilityNodeInfoCompat node, AccessibilityNodeInfoCompat itemRoot) {
    Rect itemBounds = new Rect();
    itemRoot.getBoundsInScreen(itemBounds);
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (itemBounds.isEmpty() || bounds.isEmpty()) {
      return false;
    }
    int centreX = (bounds.left + bounds.right) / 2;
    int centreY = (bounds.top + bounds.bottom) / 2;
    return centreX > (itemBounds.left + itemBounds.right) / 2
        && centreY < (itemBounds.top + itemBounds.bottom) / 2;
  }
}
