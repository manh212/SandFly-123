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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.FacebookCommentTree;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeTree;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemActionsTest {

  /** Records the last feedback part instead of performing it. */
  private static final class RecordingReturner implements Pipeline.FeedbackReturner {
    Feedback.Part lastPart;

    @Override
    public boolean returnFeedback(Feedback feedback) {
      return true;
    }

    @Override
    public boolean returnFeedback(EventId eventId, Feedback.Part.Builder part) {
      lastPart = part.build();
      return true;
    }
  }

  private Context context;
  private RecordingReturner returner;
  private CollapsedItemActions actions;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    prefs
        .edit()
        .putBoolean(context.getString(R.string.pref_collapse_list_item_actions_key), true)
        .commit();
    returner = new RecordingReturner();
    actions = new CollapsedItemActions(returner, /* analytics= */ null);
  }

  @Test
  public void facebookComment_producesChildActionsInReadingOrder_withTheFullTraySet() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    List<ContextMenuItem> items = actions.getMenuItemsForNode(context, tree.root, false);

    List<String> titles = new ArrayList<>();
    Set<Integer> ids = new HashSet<>();
    for (ContextMenuItem item : items) {
      titles.add(item.getTitle().toString());
      ids.add(item.getItemId());
      assertTrue(CollapsedItemActions.isCollapsedActionItem(item));
    }
    // Where the scanned Reply/Like cluster stood, the item now offers the tray's full
    // contents in tray order; there is no separate long-press variant of any of them.
    assertEquals(
        List.of(
            "Profile picture of Nguyen Van A",
            "Nguyen Van A",
            "This is a great comment",
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
            "Hide comment",
            "5 reactions",
            "Demote comment"),
        titles);
    assertEquals("every item id must be unique", titles.size(), ids.size());
  }

  @Test
  public void activation_clicksTheFreshChildFromARebuiltTree() {
    AccessibilityNodeInfoCompat list1 = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree1 = CollapseTestTrees.facebookComment(list1);
    ContextMenuItem.ContextMenuItemId heardId =
        findItem(tree1.root, "Demote comment").getContextMenuItemId();

    // The list recycled every node: a structurally identical new tree.
    AccessibilityNodeInfoCompat list2 = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree2 = CollapseTestTrees.facebookComment(list2);
    // The Demote button exposes the click on the rebuilt tree, as a view-backed button
    // does. (Like and Reply are not used here: they run the tray sequence instead of
    // the stored tiers this test exercises.)
    tree2.demote.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
    ContextMenuItem rebuilt = findItem(tree2.root, "Demote comment");
    assertEquals(heardId, rebuilt.getContextMenuItemId());

    assertTrue(rebuilt.onClickPerformed());
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_CLICK, returner.lastPart.nodeAction().actionId());
    // The item scan hands out obtained copies of the nodes, so this compares nodes rather
    // than object identity: the click must land on the rebuilt tree's Demote button, and
    // not on the recycled tree's.
    assertEquals(tree2.demote, returner.lastPart.nodeAction().target().getCompat());
    assertNotEquals(tree1.demote, returner.lastPart.nodeAction().target().getCompat());
  }

  @Test
  public void activation_afterTheChildMoved_theHeardItemNoLongerMatches() {
    AccessibilityNodeInfoCompat list1 = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree1 = CollapseTestTrees.facebookComment(list1);
    ContextMenuItem.ContextMenuItemId heardId =
        findItem(tree1.root, "Reply").getContextMenuItemId();

    AccessibilityNodeInfoCompat list2 = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree2 = CollapseTestTrees.facebookComment(list2);
    // The item fingerprint follows the action's target node, which is the Reply wrapper.
    CollapseTestTrees.bounds(tree2.replyWrap, 0, 500, 200, 600);

    assertNotEquals(heardId, findItem(tree2.root, "Reply").getContextMenuItemId());
  }

  @After
  public void tearDown() {
    MenuAutomation.resetForTest();
  }

  @Test
  public void youTubeItem_menuExpandsIntoCatalogTitles_withOpenMenuLast() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    List<ContextMenuItem> items = actions.getMenuItemsForNode(context, tree.primary, false);

    List<String> titles = new ArrayList<>();
    Set<Integer> ids = new HashSet<>();
    for (ContextMenuItem item : items) {
      titles.add(item.getTitle().toString());
      ids.add(item.getItemId());
      assertTrue(CollapsedItemActions.isCollapsedActionItem(item));
    }
    assertEquals(
        List.of(
            "Go to channel Channel X",
            "Play on another device",
            "Play next in queue",
            "Play last in queue",
            "Add or remove from Watch later",
            "Save to playlist",
            "Add or remove from offline videos",
            "Share",
            "Not interested",
            "Don't recommend channel",
            "Report",
            "Listen in YouTube Music",
            "Open menu"),
        titles);
    assertEquals("every item id must be unique", titles.size(), ids.size());
  }

  @Test
  public void youTubeItem_activatingACatalogItem_clicksTheMenuButtonAndArmsTheAutomation() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    ContextMenuItem share = findItem(tree.primary, "Share");
    assertTrue(share.onClickPerformed());

    assertTrue(MenuAutomation.isPending());
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_CLICK, returner.lastPart.nodeAction().actionId());
    assertEquals(tree.menu, returner.lastPart.nodeAction().target().getCompat());
  }

  @Test
  public void youTubeItem_openMenuFallback_justClicksTheMenuButton() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);

    ContextMenuItem openMenu = findItem(tree.primary, "Open menu");
    assertTrue(openMenu.onClickPerformed());

    assertFalse(MenuAutomation.isPending());
    // The tree's menu button does not expose ACTION_CLICK, so the plain click goes through
    // the focus actor's defended click rather than a bare node action.
    assertEquals(Feedback.Focus.Action.CLICK_NODE, returner.lastPart.focus().action());
    assertEquals(tree.menu, returner.lastPart.focus().target());
  }

  @Test
  public void nonRepresentativeNodes_getNoItems() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    assertFalse(actions.accept(context, tree.body));
    assertTrue(actions.getMenuItemsForNode(context, tree.body, false).isEmpty());
    assertTrue(actions.getMenuItemsForNode(context, tree.avatar, false).isEmpty());
  }

  private ContextMenuItem findItem(AccessibilityNodeInfoCompat representative, String title) {
    for (ContextMenuItem item : actions.getMenuItemsForNode(context, representative, false)) {
      if (title.contentEquals(item.getTitle())) {
        return item;
      }
    }
    throw new AssertionError("No action titled " + title);
  }
}
