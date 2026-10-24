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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
 * The focus-first route of a composite item's synthesized action: at activation time the
 * button inside the frame is re-located from the item holding accessibility focus, focus
 * is moved onto the button, the defended activation is issued at it while it holds focus,
 * and focus is restored to the item. When the focus move or the activation fails, a
 * delayed dispatch is scheduled as a control and the stored-target tiers run as before. A
 * unit test has no accessibility service, so the focus node, the focus actions, the delay
 * and the gesture dispatch are stood in for through the listener's seams; what is pinned
 * here is the order of the ladder, the node each rung lands on, and that any relocation
 * doubt falls back to the stored-target tiers untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemFocusFirstTest {

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

  /** Records every direct node action in order; one result stands in for all of them. */
  private static final class RecordingPerformer
      implements CollapsedItemActions.NodeActionPerformer {
    final List<AccessibilityNodeInfoCompat> nodes = new ArrayList<>();
    final List<Integer> actionIds = new ArrayList<>();
    boolean result = true;

    @Override
    public boolean performAction(AccessibilityNodeInfoCompat target, int id) {
      nodes.add(target);
      actionIds.add(id);
      return result;
    }

    int calls() {
      return nodes.size();
    }
  }

  /** Records the simulated press instead of dispatching it as a gesture. */
  private static final class RecordingTapper implements CollapsedItemActions.SimulatedTapper {
    AccessibilityNodeInfoCompat node;
    boolean longClick;
    boolean result = true;

    @Override
    public boolean tap(Context context, AccessibilityNodeInfoCompat target, boolean isLongClick) {
      node = target;
      longClick = isLongClick;
      return result;
    }
  }

  /** Captures the delayed control dispatch instead of posting it. */
  private static final class CapturingDelayer implements CollapsedItemActions.DelayedDispatcher {
    Runnable runnable;
    long delayMs = -1;
    int count;

    @Override
    public void postDelayed(Runnable r, long delay) {
      runnable = r;
      delayMs = delay;
      count++;
    }

    void runCaptured() {
      assertNotNull("a delayed dispatch was scheduled", runnable);
      runnable.run();
    }
  }

  /** Records the control gesture and its callback; the test completes it by hand. */
  private static final class RecordingGestureDispatcher
      implements CollapsedItemActions.GestureDispatcher {
    GestureDescription gesture;
    AccessibilityService.GestureResultCallback callback;
    int calls;
    boolean accepted = true;

    @Override
    public boolean dispatch(
        Context context,
        GestureDescription g,
        AccessibilityService.GestureResultCallback cb) {
      gesture = g;
      callback = cb;
      calls++;
      return accepted;
    }

    void completeWith(boolean completed) {
      assertNotNull("a gesture was dispatched with a callback", callback);
      if (completed) {
        callback.onCompleted(gesture);
      } else {
        callback.onCancelled(gesture);
      }
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
  private CapturingDelayer delayer;
  private RecordingGestureDispatcher gestureDispatcher;
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
    delayer = new CapturingDelayer();
    gestureDispatcher = new RecordingGestureDispatcher();
    CollapsedItemActions.setNodeActionPerformerForTest(performer);
    CollapsedItemActions.setDelayedDispatcherForTest(delayer);
    CollapsedItemActions.setGestureDispatcherForTest(gestureDispatcher);
    LogUtils.setLogLevel(Log.VERBOSE);
    actions = new CollapsedItemActions(returner, /* analytics= */ null);
  }

  @After
  public void tearDown() {
    CollapsedItemActions.resetFocusNodeProviderForTest();
    CollapsedItemActions.resetNodeActionPerformerForTest();
    CollapsedItemActions.resetSimulatedTapperForTest();
    CollapsedItemActions.resetDelayedDispatcherForTest();
    CollapsedItemActions.resetGestureDispatcherForTest();
    LogUtils.setLogLevel(Log.ERROR);
  }

  @Test
  public void likeClick_focusMovesOntoLiveButton_clickNodeAimsAtIt_focusRestored() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(
        "focus moves onto the live button first and back to the item afterwards",
        Arrays.asList(tree.like, tree.root),
        performer.nodes);
    assertEquals(
        "no direct click action is fired at the button any more",
        Arrays.asList(
            AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS),
        performer.actionIds);
    assertEquals(
        "the defended activation is aimed at the live button, not the stored frame",
        Arrays.asList(tree.like),
        returner.focusTargets);
    assertEquals(tree.like, returner.simulatedTarget);
    assertNull(returner.actionTarget);
    assertEquals("only the defended activation's part is emitted", 1, returner.partCount);
    assertEquals("no control dispatch is scheduled once the route lands", 0, delayer.count);
  }

  @Test
  public void likeClick_logRecordsLadderInOrder_withTheButtonsOwnSupportFlags() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    String log = collapsedActionsLog();
    String liveButtonLine = null;
    for (String line : log.split("\n")) {
      if (line.startsWith("liveButton class=")) {
        liveButtonLine = line;
      }
    }
    assertNotNull("the live button's descriptor line is logged", liveButtonLine);
    assertTrue(
        "the recorded Facebook button exposes neither action",
        liveButtonLine.contains("supportsClick=false")
            && liveButtonLine.contains("supportsLongClick=false"));
    int descriptor = log.indexOf("liveButton class=");
    int moved = log.indexOf("focusFirst focusMoved=true");
    int observed = log.indexOf("focusFirst focusObservedOnButton=");
    int part = log.indexOf("focusFirst clickNode result=true");
    int restored = log.indexOf("focusFirst focusRestored=true");
    int done = log.indexOf("perform result=true");
    assertTrue("the focus move is logged", moved > descriptor);
    assertTrue("the observed focus state is logged", observed > moved);
    assertTrue("the activation part is logged after the move", part > observed);
    assertTrue("the focus restore is logged after the part", restored > part);
    assertTrue("the perform result closes the ladder", done > restored);
  }

  @Test
  public void replyClick_focusFirstAimsAtTheLiveReplyButton() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem reply = findItem(tree.root, "Reply");

    assertTrue(reply.onClickPerformed());
    assertEquals(Arrays.asList(tree.reply, tree.root), performer.nodes);
    assertEquals(Arrays.asList(tree.reply), returner.focusTargets);
    assertEquals(tree.reply, returner.simulatedTarget);
    assertEquals(0, delayer.count);
  }

  @Test
  public void likeClick_staleStoredFrame_focusFirstStillPerforms() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(
        "the live button performs even while the stored frame snapshot is stale",
        like.onClickPerformed());
    assertEquals(Arrays.asList(tree.like, tree.root), performer.nodes);
    assertEquals(1, returner.partCount);
  }

  @Test
  public void likeLongPress_partRefused_focusStillRestored_controlScheduled_storedTiersRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    RecordingTapper tapper = new RecordingTapper();
    CollapsedItemActions.setSimulatedTapperForTest(tapper);

    ContextMenuItem longPress = findItem(tree.root, "Like, long press");

    assertTrue(longPress.onClickPerformed());
    assertEquals(
        "focus is restored to the item even though the activation was refused",
        Arrays.asList(tree.like, tree.root),
        performer.nodes);
    assertEquals(
        "the long-click part tries the focused live button first, then the stored frame",
        Arrays.asList(tree.like, tree.likeWrap),
        returner.focusTargets);
    assertEquals("the control dispatch is scheduled after the refused part", 1, delayer.count);
    assertEquals(400, delayer.delayMs);
    assertEquals(tree.likeWrap, tapper.node);
    assertTrue(tapper.longClick);
    String log = collapsedActionsLog();
    assertTrue(log.contains("focusFirst longClickNode result=false"));
    assertTrue(log.contains("focusFirst focusRestored=true"));
  }

  @Test
  public void focusDoesNotMove_partSkipped_controlDispatches_onCancelledLogged_performFalse() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    performer.result = false;
    RecordingTapper tapper = new RecordingTapper();
    tapper.result = false;
    CollapsedItemActions.setSimulatedTapperForTest(tapper);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertFalse("nothing performed, so the perform reports failure", like.onClickPerformed());
    assertEquals(
        "only the focus move was attempted; there is nothing to restore", 1, performer.calls());
    assertEquals(tree.like, performer.nodes.get(0));
    assertEquals("no activation part is emitted without the focus move", 0, returner.partCount);
    assertEquals(1, delayer.count);

    delayer.runCaptured();

    assertEquals(1, gestureDispatcher.calls);
    assertNotNull(gestureDispatcher.gesture);
    assertEquals(
        "the control press is the canonical two-stroke tap",
        2,
        gestureDispatcher.gesture.getStrokeCount());
    gestureDispatcher.completeWith(/* completed= */ false);
    String log = collapsedActionsLog();
    int moved = log.indexOf("focusFirst focusMoved=false");
    int scheduled = log.indexOf("delayedDispatch scheduled delayMs=400");
    int result = log.indexOf("perform result=false");
    int accepted = log.indexOf("delayedDispatch accepted=true");
    int cancelled = log.indexOf("delayedDispatch onCancelled");
    assertTrue("the refused focus move is logged", moved >= 0);
    assertTrue("the control is scheduled from inside the perform", scheduled > moved);
    assertTrue("the perform result is reported before the control runs", result > scheduled);
    assertTrue("dispatch acceptance is logged apart from completion", accepted > result);
    assertTrue("cancellation is logged distinctly", cancelled > accepted);
  }

  @Test
  public void controlDispatch_onCompleted_loggedAfterAcceptance() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    performer.result = false;
    RecordingTapper tapper = new RecordingTapper();
    tapper.result = false;
    CollapsedItemActions.setSimulatedTapperForTest(tapper);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertFalse(like.onClickPerformed());
    delayer.runCaptured();
    gestureDispatcher.completeWith(/* completed= */ true);
    String log = collapsedActionsLog();
    int accepted = log.indexOf("delayedDispatch accepted=true");
    int completed = log.indexOf("delayedDispatch onCompleted");
    assertTrue("completion is logged after acceptance", completed > accepted);
    assertFalse(
        "a completed dispatch is not a cancellation", log.contains("delayedDispatch onCancelled"));
  }

  @Test
  public void controlDispatch_skippedWhenAStoredTierAlreadyPerformed() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    performer.result = false;

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(
        "the stored tiers still perform when the focus move is refused", like.onClickPerformed());
    assertEquals(1, delayer.count);
    delayer.runCaptured();
    assertEquals(
        "the control never double-fires after a stored tier landed", 0, gestureDispatcher.calls);
    assertTrue(
        collapsedActionsLog()
            .contains("delayedDispatch skipped: a stored tier already performed"));
  }

  @Test
  public void focusOnAnotherComment_relocationRefused_noFocusAction_noControl_storedTiersRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    FacebookCommentTree other = CollapseTestTrees.facebookCommentNestedShortBodyWithFollow(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(other.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals("a different item's button must never be acted on", 0, performer.calls());
    assertEquals(0, delayer.count);
    assertEquals(tree.likeWrap, returner.simulatedTarget);
    assertNull(returner.actionTarget);
  }

  @Test
  public void noFocus_noFocusAction_noControl_storedTiersRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(0, performer.calls());
    assertEquals(0, delayer.count);
    assertEquals(tree.likeWrap, returner.simulatedTarget);
  }

  @Test
  public void youTubeItem_neverEntersTheFocusFirstRoute() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.primary));

    ContextMenuItem openMenu = findItem(tree.primary, "Open menu");

    assertTrue(openMenu.onClickPerformed());
    assertEquals("primary-child items keep the stored-target routes", 0, performer.calls());
    assertEquals(0, delayer.count);
    assertEquals(0, gestureDispatcher.calls);
    assertEquals(tree.menu, returner.simulatedTarget);
  }

  private String collapsedActionsLog() {
    StringBuilder all = new StringBuilder();
    for (LogItem item : ShadowLog.getLogsForTag("CollapsedItemActions")) {
      all.append(item.msg).append('\n');
    }
    return all.toString();
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
