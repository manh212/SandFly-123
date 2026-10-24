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
import android.util.Log;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.FacebookCommentTree;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeTree;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLog.LogItem;

/**
 * The live-button route of a composite item's synthesized action: at activation time the
 * button inside the frame is re-located from the item holding accessibility focus and the
 * press is performed on that node -- first the action directly on it, then the defended
 * focus-actor route aimed at it -- instead of routing every press through the stored
 * frame. A unit test has no accessibility service, so the focus node and the direct action
 * are stood in for through the listener's seams; what is pinned here is the order of the
 * ladder, the node each rung lands on, and that any relocation doubt falls back to the
 * stored-target tiers untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemLiveButtonTest {

  /** Performs feedback parts under the device contract, recording every part emitted. */
  private static final class PerformingReturner implements Pipeline.FeedbackReturner {
    int partCount;
    AccessibilityNodeInfoCompat actionTarget;
    int actionId;
    AccessibilityNodeInfoCompat simulatedTarget;
    final List<AccessibilityNodeInfoCompat> focusTargets = new ArrayList<>();

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
        focusTargets.add(target);
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

  /** Records the direct action on the live button; can be told to refuse it. */
  private static final class RecordingPerformer
      implements CollapsedItemActions.NodeActionPerformer {
    AccessibilityNodeInfoCompat node;
    int actionId;
    int calls;
    boolean result;

    @Override
    public boolean performAction(AccessibilityNodeInfoCompat target, int id) {
      node = target;
      actionId = id;
      calls++;
      return result;
    }
  }

  /** Records the simulated press instead of dispatching it as a gesture. */
  private static final class RecordingTapper implements CollapsedItemActions.SimulatedTapper {
    AccessibilityNodeInfoCompat node;
    boolean longClick;

    @Override
    public boolean tap(Context context, AccessibilityNodeInfoCompat target, boolean isLongClick) {
      node = target;
      longClick = isLongClick;
      return true;
    }
  }

  /** Stands in for the live accessibility focus with one fixed node. */
  private static final class FixedFocusProvider implements CollapsedItemActions.FocusNodeProvider {
    private final AccessibilityNodeInfoCompat focus;

    FixedFocusProvider(AccessibilityNodeInfoCompat focus) {
      this.focus = focus;
    }

    @Override
    public AccessibilityNodeInfoCompat getFocusNode(Context context) {
      return focus;
    }
  }

  private Context context;
  private PerformingReturner returner;
  private RecordingPerformer performer;
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
    performer = new RecordingPerformer();
    CollapsedItemActions.setNodeActionPerformerForTest(performer);
    LogUtils.setLogLevel(Log.VERBOSE);
    actions = new CollapsedItemActions(returner, /* analytics= */ null);
  }

  @After
  public void tearDown() {
    CollapsedItemActions.resetFocusNodeProviderForTest();
    CollapsedItemActions.resetNodeActionPerformerForTest();
    CollapsedItemActions.resetSimulatedTapperForTest();
    LogUtils.setLogLevel(Log.ERROR);
  }

  @Test
  public void likeClick_liveButtonFound_directActionPerformsOnTheButton_andStops() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    performer.result = true;

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(1, performer.calls);
    assertEquals(
        "the direct action lands on the live button inside the frame", tree.like, performer.node);
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, performer.actionId);
    assertEquals("no further rung may run once the direct action lands", 0, returner.partCount);
  }

  @Test
  public void likeClick_liveButtonLog_recordsTheButtonsOwnSupportFlagsAndRungResults() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    String liveButtonLine = null;
    StringBuilder all = new StringBuilder();
    for (LogItem item : ShadowLog.getLogsForTag("CollapsedItemActions")) {
      all.append(item.msg).append('\n');
      if (item.msg.startsWith("liveButton class=")) {
        liveButtonLine = item.msg;
      }
    }
    assertTrue("the live button's own support flags are logged", liveButtonLine != null);
    assertTrue(
        "the recorded Facebook button exposes neither action",
        liveButtonLine.contains("supportsClick=false")
            && liveButtonLine.contains("supportsLongClick=false"));
    int directIndex = all.indexOf("liveButton nodeAction result=false");
    int routedIndex = all.indexOf("liveButton clickNode result=true");
    assertTrue("the refused direct action is logged", directIndex >= 0);
    assertTrue(
        "the defended route runs after the direct action and is logged",
        routedIndex > directIndex);
  }

  @Test
  public void likeClick_directActionRefused_clickNodeRouteAimsAtTheLiveButton() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(1, performer.calls);
    assertEquals(tree.like, performer.node);
    assertEquals(
        "the defended route is aimed at the live button, not the stored frame",
        Arrays.asList(tree.like),
        returner.focusTargets);
    assertEquals(tree.like, returner.simulatedTarget);
    assertNull(returner.actionTarget);
    assertEquals("only the defended route's part is emitted", 1, returner.partCount);
  }

  @Test
  public void likeLongPress_directAndDefendedRoutesRefused_fallsThroughToStoredTiers() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    RecordingTapper tapper = new RecordingTapper();
    CollapsedItemActions.setSimulatedTapperForTest(tapper);

    ContextMenuItem longPress = findItem(tree.root, "Like, long press");

    assertTrue(longPress.onClickPerformed());
    assertEquals(1, performer.calls);
    assertEquals(tree.like, performer.node);
    assertEquals(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, performer.actionId);
    assertEquals(
        "the defended route tries the live button first, then the stored frame",
        Arrays.asList(tree.like, tree.likeWrap),
        returner.focusTargets);
    assertEquals(tree.likeWrap, tapper.node);
    assertTrue(tapper.longClick);
  }

  @Test
  public void replyClick_directActionPerformsOnTheLiveReplyButton() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    performer.result = true;

    ContextMenuItem reply = findItem(tree.root, "Reply");

    assertTrue(reply.onClickPerformed());
    assertEquals(1, performer.calls);
    assertEquals(tree.reply, performer.node);
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, performer.actionId);
    assertEquals(0, returner.partCount);
  }

  @Test
  public void likeClick_staleStoredFrame_liveButtonStillPerforms() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    performer.result = true;

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(
        "the live button performs even while the stored frame snapshot is stale",
        like.onClickPerformed());
    assertEquals(1, performer.calls);
    assertEquals(tree.like, performer.node);
    assertEquals(0, returner.partCount);
  }

  @Test
  public void focusOnAnotherComment_relocationRefused_noDirectAction_storedTiersRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    FacebookCommentTree other = CollapseTestTrees.facebookCommentNestedShortBodyWithFollow(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(other.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals("a different item's button must never be acted on", 0, performer.calls);
    assertEquals(tree.likeWrap, returner.simulatedTarget);
    assertNull(returner.actionTarget);
  }

  @Test
  public void noFocus_noDirectAction_storedTiersRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(0, performer.calls);
    assertEquals(tree.likeWrap, returner.simulatedTarget);
  }

  @Test
  public void youTubeItem_neverEntersTheLiveButtonRoute() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.primary));

    ContextMenuItem openMenu = findItem(tree.primary, "Open menu");

    assertTrue(openMenu.onClickPerformed());
    assertEquals("primary-child items keep the stored-target routes", 0, performer.calls);
    assertEquals(tree.menu, returner.simulatedTarget);
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
