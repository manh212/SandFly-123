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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.FacebookCommentTree;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The perform-time guard of a synthesized action: the target is refreshed at activation, and
 * while its bounds stay empty no route may report success -- the defended click would tap at
 * (0, 0) and call it a press. Robolectric nodes cannot go stale, so staleness is modelled by
 * empty bounds on the target; the refresh itself is stood in for through the listener's seam
 * where a test needs it to restore the bounds, the way a live refresh re-reads the child.
 * The guard is exercised through Demote, an action that still runs the stored tiers; Like
 * and Reply run the tray sequence instead and are pinned in {@code
 * CollapsedItemTrayActivationTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemPerformGuardTest {

  /** Performs feedback parts under the device contract, recording every part emitted. */
  private static final class PerformingReturner implements Pipeline.FeedbackReturner {
    int partCount;
    AccessibilityNodeInfoCompat actionTarget;
    int actionId;
    AccessibilityNodeInfoCompat simulatedTarget;

    @Override
    public boolean returnFeedback(Feedback feedback) {
      partCount++;
      return true;
    }

    @Override
    public boolean returnFeedback(EventId eventId, Feedback.Part.Builder partBuilder) {
      partCount++;
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
          // The defended click simulates a tap at the target's centre from here and
          // reports the gesture's acceptance as success, whatever the bounds are.
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

  /** Records the refresh and can restore the target's bounds, as a live refresh would. */
  private static final class RecordingRefresher implements CollapsedItemActions.NodeRefresher {
    AccessibilityNodeInfoCompat node;
    int calls;
    boolean restoreBounds;

    @Override
    public boolean refresh(AccessibilityNodeInfoCompat target) {
      node = target;
      calls++;
      if (restoreBounds) {
        CollapseTestTrees.bounds(target, 400, 300, 600, 400);
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

  @After
  public void tearDown() {
    CollapsedItemActions.resetNodeRefresherForTest();
    CollapsedItemActions.resetSimulatedTapperForTest();
  }

  @Test
  public void demoteClick_emptyBounds_refreshLeavesThemEmpty_honestFailure() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.demote, 0, 0, 0, 0);

    ContextMenuItem demote = findItem(tree.root, "Demote comment");

    assertFalse("empty bounds must surface as not supported, not as success",
        demote.onClickPerformed());
    assertEquals("no perform tier may emit feedback against an empty-bounds target",
        0, returner.partCount);
    assertNull(returner.actionTarget);
    assertNull(returner.simulatedTarget);
  }

  @Test
  public void demoteClick_emptyBounds_refreshRestoresBounds_defendedClickRuns() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.demote, 0, 0, 0, 0);
    RecordingRefresher refresher = new RecordingRefresher();
    refresher.restoreBounds = true;
    CollapsedItemActions.setNodeRefresherForTest(refresher);

    ContextMenuItem demote = findItem(tree.root, "Demote comment");

    assertTrue(demote.onClickPerformed());
    assertEquals(1, refresher.calls);
    assertEquals(tree.demote, refresher.node);
    assertEquals(tree.demote, returner.simulatedTarget);
  }

  @Test
  public void demoteClick_emptyBounds_buttonExposesClick_directActionStillPerforms() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.demote, 0, 0, 0, 0);
    tree.demote.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);

    ContextMenuItem demote = findItem(tree.root, "Demote comment");

    assertTrue(demote.onClickPerformed());
    assertEquals(tree.demote, returner.actionTarget);
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, returner.actionId);
  }

  @Test
  public void demoteClick_validBounds_defendedClickUnchanged() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    ContextMenuItem demote = findItem(tree.root, "Demote comment");

    assertTrue(demote.onClickPerformed());
    assertEquals(tree.demote, returner.simulatedTarget);
    assertNull(returner.actionTarget);
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
