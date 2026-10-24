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
import static org.junit.Assert.assertNull;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
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
    List<String> labels = new ArrayList<>();
    for (ChildAction action : item.actions()) {
      labels.add(action.label.toString());
    }
    assertEquals(List.of("Go to channel Channel X", "Action menu for Video title"), labels);
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
