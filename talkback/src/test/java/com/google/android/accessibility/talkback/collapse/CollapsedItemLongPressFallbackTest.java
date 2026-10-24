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
 * The long-press twin of a Facebook action (Like, which opens the reaction tray) when its
 * target — the Like block's wrapper — exposes no ACTION_LONG_CLICK: the direct action is
 * refused and the focus actor's long-click route -- which, unlike its click route,
 * simulates nothing -- is refused too, so the press must finally be simulated at the
 * wrapper's centre. The simulated press is recorded through the listener's test seam
 * because a unit test has no accessibility service to dispatch gestures with.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemLongPressFallbackTest {

  /** Performs feedback parts under the same device contract as the activation tests. */
  private static final class PerformingReturner implements Pipeline.FeedbackReturner {
    AccessibilityNodeInfoCompat actionTarget;

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
        return true;
      }
      if (part.focus() != null && part.focus().target() != null) {
        AccessibilityNodeInfoCompat target = part.focus().target();
        int actionId;
        if (part.focus().action() == Feedback.Focus.Action.CLICK_NODE) {
          actionId = AccessibilityNodeInfoCompat.ACTION_CLICK;
        } else if (part.focus().action() == Feedback.Focus.Action.LONG_CLICK_NODE) {
          actionId = AccessibilityNodeInfoCompat.ACTION_LONG_CLICK;
        } else {
          return true;
        }
        if (PerformActionUtils.isNodeSupportAction(target, actionId)) {
          actionTarget = target;
          return true;
        }
        // The click route simulates a tap from here on a device; the long-click route
        // performs nothing more, so an unexposed long-click is simply refused.
        return part.focus().action() == Feedback.Focus.Action.CLICK_NODE;
      }
      return true;
    }
  }

  /** Records the simulated press instead of dispatching it as a gesture. */
  private static final class RecordingTapper implements CollapsedItemActions.SimulatedTapper {
    AccessibilityNodeInfoCompat node;
    boolean longClick;

    @Override
    public boolean tap(Context context, AccessibilityNodeInfoCompat node, boolean longClick) {
      this.node = node;
      this.longClick = longClick;
      return true;
    }
  }

  private Context context;
  private PerformingReturner returner;
  private RecordingTapper tapper;
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
    tapper = new RecordingTapper();
    CollapsedItemActions.setSimulatedTapperForTest(tapper);
    actions = new CollapsedItemActions(returner, /* analytics= */ null);
  }

  @After
  public void tearDown() {
    CollapsedItemActions.resetSimulatedTapperForTest();
  }

  @Test
  public void facebookComment_likeLongPress_fallsBackToASimulatedPressOnTheLikeWrapper() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    ContextMenuItem longPress = findItem(tree.root, "Like, long press");

    assertTrue(longPress.onClickPerformed());
    assertNull("no route performs the long-click directly", returner.actionTarget);
    assertEquals(tree.likeWrap, tapper.node);
    assertTrue(tapper.longClick);
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
