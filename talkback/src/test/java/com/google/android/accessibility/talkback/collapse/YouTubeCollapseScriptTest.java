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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeTree;
import com.google.android.accessibility.talkback.collapse.CollapsedItem.ChildAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class YouTubeCollapseScriptTest {
  private static final Map<String, CollapseScript> SCRIPTS =
      Map.of(CollapseTestTrees.YOUTUBE_PACKAGE, new YouTubeCollapseScript());

  @Test
  public void videoItem_matches_primaryIsTheStop_childButtonsBecomeActions() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);

    assertEquals(tree.primary, item.representative());
    assertNull(item.composedLabel());
    List<String> clickLabels = new ArrayList<>();
    for (ChildAction action : item.actions()) {
      if (action.kind == ChildAction.Kind.CLICK) {
        clickLabels.add(action.label.toString());
      }
    }
    assertEquals(List.of("Go to channel Channel X"), clickLabels);
  }

  @Test
  public void videoItem_menuButtonExpandsIntoTheCatalog_withAnOpenMenuFallback() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);

    List<ChildAction> actions = item.actions();
    assertEquals(13, actions.size());
    // The channel button stays a plain click action on its own node.
    assertEquals(ChildAction.Kind.CLICK, actions.get(0).kind);
    assertEquals(tree.channel, actions.get(0).node);
    // The menu button is replaced by the catalog, in menu order, all aimed at the menu node.
    List<Integer> catalogResIds = new ArrayList<>();
    for (int i = 1; i <= 11; i++) {
      assertEquals(ChildAction.Kind.MENU_ITEM, actions.get(i).kind);
      assertEquals(tree.menu, actions.get(i).node);
      assertEquals(
          CollapseTestTrees.YOUTUBE_MENU_LABEL_VIEW_ID, actions.get(i).sheetItemLabelViewId);
      catalogResIds.add(actions.get(i).labelResId);
    }
    assertEquals(
        List.of(
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
            R.string.title_collapse_menu_youtube_music),
        catalogResIds);
    // The fallback that opens the menu as usual comes last.
    assertEquals(ChildAction.Kind.MENU_OPEN, actions.get(12).kind);
    assertEquals(tree.menu, actions.get(12).node);
    assertEquals(R.string.title_collapse_menu_open, actions.get(12).labelResId);
  }

  @Test
  public void catalog_watchLaterAndDownloadsAreToggles_theRestArePlain() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);
    List<ChildAction> actions = item.actions();

    // Index 4 is Watch later: a toggle whose recorded variants all share one stable core.
    ChildAction watchLater = actions.get(4);
    assertEquals(R.string.title_collapse_menu_watch_later, watchLater.labelResId);
    assertNotNull(watchLater.menuStateVariants);
    assertArrayEquals(new String[] {"Xem sau", "Watch later"}, watchLater.menuStateCores);
    // Index 6 is downloads: the second toggle.
    ChildAction downloads = actions.get(6);
    assertEquals(R.string.title_collapse_menu_remove_download, downloads.labelResId);
    assertNotNull(downloads.menuStateVariants);
    assertArrayEquals(new String[] {"tải xuống", "download"}, downloads.menuStateCores);
    // Their neighbours stay plain: the offered label is itself the exact match key.
    assertNull(actions.get(5).menuStateCores);
    assertNull(actions.get(5).menuStateVariants);
    assertNull(actions.get(7).menuStateCores);
    assertNull(actions.get(7).menuStateVariants);
  }

  @Test
  public void menuButtonInTheBottomStrip_isRecognized_expands() {
    // This test replaces menuButtonInTheBottomCorner_isNotRecognized_noExpansion, which
    // asserted the opposite. That test encoded a top-corner geometry assumption that no
    // live tree ever supported: on the real feed the menu button sits in the metadata
    // strip at the item's bottom, and the shipped geometry rule therefore never fired on
    // device (dev 624). Recognition is by description and structure now, so the bottom
    // strip is exactly where the button is expected — and it expands.
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    CollapseTestTrees.bounds(tree.menu, 900, 450, 1080, 600);

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);

    assertEquals(13, item.actions().size());
    assertEquals(ChildAction.Kind.MENU_ITEM, item.actions().get(1).kind);
    assertEquals(tree.menu, item.actions().get(1).node);
    assertEquals(ChildAction.Kind.MENU_OPEN, item.actions().get(12).kind);
  }

  @Test
  public void menuButtonWithVietnameseWording_expandsByDescription() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    tree.channel.setContentDescription("Chuyển đến kênh Channel X");
    tree.menu.setContentDescription("Trình đơn thao tác cho Video title");

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);

    assertEquals(13, item.actions().size());
    assertEquals(ChildAction.Kind.CLICK, item.actions().get(0).kind);
    assertEquals("Chuyển đến kênh Channel X", item.actions().get(0).label.toString());
    assertEquals(ChildAction.Kind.MENU_ITEM, item.actions().get(1).kind);
    assertEquals(tree.menu, item.actions().get(1).node);
  }

  @Test
  public void menuButtonWithUnrecordedWording_expandsByStructure() {
    // A wording the script has not recorded (YouTube words the button differently in some
    // locales): the channel button is still identified by its own wording, and the menu
    // button is the last secondary after it, so structure alone settles the identification.
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    tree.menu.setContentDescription("More options for Video title");

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);

    assertEquals(13, item.actions().size());
    assertEquals(ChildAction.Kind.MENU_ITEM, item.actions().get(1).kind);
    assertEquals(tree.menu, item.actions().get(1).node);
  }

  @Test
  public void twoMenuWordingButtons_noExpansion() {
    // Both secondaries carry the menu wording: the exactly-one gate cannot settle which
    // one is the overflow button, so both stay plain actions and no catalog appears.
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    tree.channel.setContentDescription("Action menu for Another title");

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);

    assertEquals(2, item.actions().size());
    for (ChildAction action : item.actions()) {
      assertEquals(ChildAction.Kind.CLICK, action.kind);
    }
  }

  @Test
  public void unrecordedWordingsWithoutAChannelAnchor_noExpansion() {
    // Neither wording is recorded and the first secondary is not recognisable as the
    // channel button, so structure cannot settle the last one either: untouched.
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    tree.channel.setContentDescription("Chaîne Channel X");
    tree.menu.setContentDescription("Menu d'actions pour Video title");

    CollapsedItem item = CollapseEngine.matchItemRoot(tree.item, SCRIPTS);

    assertEquals(2, item.actions().size());
    for (ChildAction action : item.actions()) {
      assertEquals(ChildAction.Kind.CLICK, action.kind);
    }
  }

  @Test
  public void videoItemWithoutChildButtons_isLeftUntouched() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItemWithoutChildren(list, 0);

    assertNull(CollapseEngine.matchItemRoot(tree.item, SCRIPTS));
  }

  @Test
  public void matchContaining_fromAChildButton_findsTheItem() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    CollapsedItem item = CollapseEngine.matchContaining(tree.channel, SCRIPTS);

    assertEquals(tree.primary, item.representative());
  }

  @Test
  public void shelfWithScrollableDescendant_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    AccessibilityNodeInfoCompat shelf = CollapseTestTrees.node(tree.item, "android.view.ViewGroup");
    shelf.setScrollable(true);

    assertNull(CollapseEngine.matchItemRoot(tree.item, SCRIPTS));
  }

  @Test
  public void fiveChildButtons_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    for (int i = 0; i < 3; i++) {
      AccessibilityNodeInfoCompat extra =
          CollapseTestTrees.node(tree.primary, "android.widget.Button");
      extra.setClickable(true);
      extra.setFocusable(true);
      extra.setContentDescription("Extra button " + i);
      CollapseTestTrees.bounds(extra, 400 + i * 100, 500, 480 + i * 100, 580);
      extra.setPackageName(CollapseTestTrees.YOUTUBE_PACKAGE);
    }

    assertNull(CollapseEngine.matchItemRoot(tree.item, SCRIPTS));
  }

  @Test
  public void primaryCoveringOnlyHalfTheItem_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    CollapseTestTrees.bounds(tree.primary, 0, 0, 1080, 300);

    assertNull(CollapseEngine.matchItemRoot(tree.item, SCRIPTS));
  }

  @Test
  public void childButtonWithoutLabel_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    tree.channel.setContentDescription(null);

    assertNull(CollapseEngine.matchItemRoot(tree.item, SCRIPTS));
  }

  @Test
  public void seekBarDescendant_doesNotMatch() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    CollapseTestTrees.node(tree.item, "android.widget.SeekBar");

    assertNull(CollapseEngine.matchItemRoot(tree.item, SCRIPTS));
  }

  @Test
  public void itemOutsideACollection_doesNotMatch() {
    AccessibilityNodeInfoCompat screen =
        CollapseTestTrees.node(null, "android.view.ViewGroup");
    screen.setPackageName(CollapseTestTrees.YOUTUBE_PACKAGE);
    AccessibilityNodeInfoCompat fakeItem =
        CollapseTestTrees.node(screen, "android.view.ViewGroup");
    fakeItem.setFocusable(true);
    CollapseTestTrees.bounds(fakeItem, 0, 0, 1080, 600);
    AccessibilityNodeInfoCompat primary =
        CollapseTestTrees.node(fakeItem, "android.widget.Button");
    primary.setClickable(true);
    primary.setFocusable(true);
    primary.setContentDescription("Video title, play video");
    CollapseTestTrees.bounds(primary, 0, 0, 1080, 600);
    AccessibilityNodeInfoCompat menu = CollapseTestTrees.node(primary, "android.widget.Button");
    menu.setClickable(true);
    menu.setFocusable(true);
    menu.setContentDescription("Action menu");
    CollapseTestTrees.bounds(menu, 900, 0, 1080, 150);
    CollapseTestTrees.applyPackage(screen, CollapseTestTrees.YOUTUBE_PACKAGE);

    assertNull(CollapseEngine.matchContaining(primary, SCRIPTS));
  }
}
