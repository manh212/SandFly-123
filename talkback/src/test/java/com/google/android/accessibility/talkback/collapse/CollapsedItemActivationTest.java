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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeTree;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Activation of a synthesized action, driven the way the Actions reading control drives it on
 * a device: the item is heard from one menu build, then the menu is rebuilt and the item is
 * looked up by its {@link ContextMenuItem.ContextMenuItemId} and clicked -- except that here
 * the pipeline actually performs, under the contract the framework and the focus actor
 * implement on a real device:
 *
 * <ul>
 *   <li>A node action is refused when the target node does not expose that action. Facebook's
 *       Litho buttons are shaped like that: clickable, but with no ACTION_CLICK in their
 *       action list (the test trees model exactly the recorded flags).
 *   <li>A node action on a node that does expose the action is performed on it.
 *   <li>The focus actor's defended click (CLICK_NODE) performs the action when the node
 *       exposes it and otherwise simulates a tap at the node's centre, which the app receives
 *       as a real tap on the child; its long-click (LONG_CLICK_NODE) has no such simulation.
 * </ul>
 *
 * <p>Facebook's Like and Reply used to be pinned here against delivery on the action
 * block's wrapper; they now run the tray sequence instead of these routes and are pinned
 * in {@code CollapsedItemTrayActivationTest}, so this file keeps the routes' contract
 * for the actions that still use them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemActivationTest {

  /** A pipeline seam that performs feedback parts under the device contract above. */
  private static final class PerformingReturner implements Pipeline.FeedbackReturner {
    /** The node a real action was performed on, or null when none was. */
    AccessibilityNodeInfoCompat actionTarget;
    int actionId;
    /** The node whose centre received a simulated tap, or null when none was. */
    AccessibilityNodeInfoCompat simulatedTarget;

    @Override
    public boolean returnFeedback(Feedback feedback) {
      return true;
    }

    @Override
    public boolean returnFeedback(EventId eventId, Feedback.Part.Builder partBuilder) {
      Feedback.Part part = partBuilder.build();
      if (part.nodeAction() != null) {
        AccessibilityNodeInfoCompat target = part.nodeAction().target().getCompat();
        int id = part.nodeAction().actionId();
        if (!PerformActionUtils.isNodeSupportAction(target, id)) {
          return false;
        }
        actionTarget = target;
        actionId = id;
        return true;
      }
      if (part.focus() != null && part.focus().target() != null) {
        AccessibilityNodeInfoCompat target = part.focus().target();
        if (part.focus().action() == Feedback.Focus.Action.CLICK_NODE) {
          if (PerformActionUtils.isNodeSupportAction(
              target, AccessibilityNodeInfoCompat.ACTION_CLICK)) {
            actionTarget = target;
            actionId = AccessibilityNodeInfoCompat.ACTION_CLICK;
            return true;
          }
          simulatedTarget = target;
          return true;
        }
        if (part.focus().action() == Feedback.Focus.Action.LONG_CLICK_NODE) {
          if (PerformActionUtils.isNodeSupportAction(
              target, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)) {
            actionTarget = target;
            actionId = AccessibilityNodeInfoCompat.ACTION_LONG_CLICK;
            return true;
          }
          return false;
        }
      }
      return true;
    }
  }

  private Context context;
  private PerformingReturner returner;
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
    returner = new PerformingReturner();
    actions = new CollapsedItemActions(returner, /* analytics= */ null);
  }

  @Test
  public void youTubeChannel_activation_stillPerformsDirectlyOnTheChannelButton() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    // A view-backed button exposes ACTION_CLICK, as real Android views do; the Facebook
    // trees deliberately do not, which is the whole difference between the two shapes.
    tree.channel.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);

    ContextMenuItem item = findItem(tree.primary, "Go to channel Channel X");

    assertTrue(item.onClickPerformed());
    assertEquals(tree.channel, returner.actionTarget);
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, returner.actionId);
    assertNull("a node that performs its action needs no simulated tap", returner.simulatedTarget);
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
