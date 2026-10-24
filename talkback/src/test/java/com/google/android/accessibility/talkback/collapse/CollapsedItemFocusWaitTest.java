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
 * The focus-first route of a composite item's synthesized action, in its event-driven
 * form: at activation time the button inside the frame is re-located from the item
 * holding accessibility focus and the focus action is fired at it, but the defended
 * activation is issued only once the button's own accessibility-focus event has arrived
 * and a fresh fetch confirms the button holds focus -- on device the focus move
 * completes asynchronously, tens of milliseconds after the action. Focus is restored to
 * the item afterwards. A wait that times out or sees focus land elsewhere restores focus
 * and runs the delayed-dispatch control and the stored-target tiers from the
 * continuation; when every rung fails, the failure is announced through the pipeline. A
 * unit test has no accessibility service, so the focus node, the focus actions, the
 * focus-event stream, the delays and the gesture dispatch are stood in for through the
 * listener's seams; what is pinned here is the order of the ladder, the node each rung
 * lands on, and that nothing is activated before the focus event arrives.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemFocusWaitTest {

  /** Performs feedback parts under the device contract, recording every part emitted. */
  private static final class PerformingReturner implements Pipeline.FeedbackReturner {
    int partCount;
    AccessibilityNodeInfoCompat actionTarget;
    int actionId;
    AccessibilityNodeInfoCompat simulatedTarget;
    final List<AccessibilityNodeInfoCompat> focusTargets = new ArrayList<>();
    final List<String> speeches = new ArrayList<>();

    @Override
    public boolean returnFeedback(Feedback feedback) {
      partCount++;
      return true;
    }

    @Override
    public boolean returnFeedback(EventId eventId, Feedback.Part.Builder partBuilder) {
      partCount++;
      Feedback.Part part = partBuilder.build();
      if (part.speech() != null && part.speech().text() != null) {
        speeches.add(part.speech().text().toString());
        return true;
      }
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

  /** Captures every delayed post instead of posting it; tests run them out by hand. */
  private static final class CapturingDelayer implements CollapsedItemActions.DelayedDispatcher {
    private final List<Runnable> runnables = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();

    @Override
    public void postDelayed(Runnable r, long delay) {
      runnables.add(r);
      delays.add(delay);
    }

    int count() {
      return runnables.size();
    }

    int countWithDelay(long delayMs) {
      int count = 0;
      for (long delay : delays) {
        if (delay == delayMs) {
          count++;
        }
      }
      return count;
    }

    /** Runs the oldest captured post with the given delay, as its deadline arriving. */
    void runWithDelay(long delayMs) {
      for (int i = 0; i < runnables.size(); i++) {
        if (delays.get(i) == delayMs) {
          Runnable runnable = runnables.remove(i);
          delays.remove(i);
          runnable.run();
          return;
        }
      }
      throw new AssertionError("no captured post with delay " + delayMs);
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

  /** Stands in for the live accessibility focus; the test moves it by hand. */
  private static final class MutableFocusProvider
      implements CollapsedItemActions.FocusNodeProvider {
    AccessibilityNodeInfoCompat focus;

    MutableFocusProvider(AccessibilityNodeInfoCompat focus) {
      this.focus = focus;
    }

    @Override
    public AccessibilityNodeInfoCompat getFocusNode(Context context) {
      return focus;
    }
  }

  /** Stands in for the service's focus-event stream; the test delivers events by hand. */
  private static final class FakeFocusEventSource implements CollapsedItemActions.FocusEventSource {
    CollapsedItemActions.FocusEventConsumer consumer;
    CollapsedItemActions.FocusEventConsumer lastConsumer;
    int armCount;

    @Override
    public void arm(CollapsedItemActions.FocusEventConsumer newConsumer) {
      consumer = newConsumer;
      lastConsumer = newConsumer;
      armCount++;
    }

    @Override
    public void disarm() {
      consumer = null;
    }

    @Override
    public void deliver(AccessibilityNodeInfoCompat source) {
      if (consumer != null) {
        // The real stream hands over a fresh wrap of the event's source node, never
        // the tree's own instance; mirror that so the session's reads and recycle of
        // the source cannot touch the node under test.
        consumer.onFocusEvent(
            source == null ? null : AccessibilityNodeInfoCompat.obtain(source));
      }
    }

    /** Delivers an event to the armed consumer, as the service stream would. */
    void fireEvent(AccessibilityNodeInfoCompat source) {
      deliver(source);
    }

    /** Delivers an event to the last armed consumer even after it was disarmed. */
    void fireLateEvent(AccessibilityNodeInfoCompat source) {
      if (lastConsumer != null) {
        lastConsumer.onFocusEvent(
            source == null ? null : AccessibilityNodeInfoCompat.obtain(source));
      }
    }
  }

  private Context context;
  private PerformingReturner returner;
  private RecordingPerformer performer;
  private CapturingDelayer delayer;
  private RecordingGestureDispatcher gestureDispatcher;
  private FakeFocusEventSource eventSource;
  private MutableFocusProvider focusProvider;
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
    eventSource = new FakeFocusEventSource();
    CollapsedItemActions.setNodeActionPerformerForTest(performer);
    CollapsedItemActions.setDelayedDispatcherForTest(delayer);
    CollapsedItemActions.setGestureDispatcherForTest(gestureDispatcher);
    CollapsedItemActions.setFocusEventSourceForTest(eventSource);
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
    CollapsedItemActions.resetFocusEventSourceForTest();
    LogUtils.setLogLevel(Log.ERROR);
  }

  private void standOn(FacebookCommentTree tree) {
    focusProvider = new MutableFocusProvider(tree.root);
    CollapsedItemActions.setFocusNodeProviderForTest(focusProvider);
  }

  @Test
  public void likeClick_armsTheWait_noActivationBeforeTheFocusEvent() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue("the armed wait reports the click as handled", like.onClickPerformed());
    assertEquals(
        "only the focus action is fired at arm time",
        Arrays.asList(tree.like),
        performer.nodes);
    assertEquals(
        Arrays.asList(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS),
        performer.actionIds);
    assertEquals("no activation part is emitted before the focus event", 0, returner.partCount);
    assertNull(returner.simulatedTarget);
    assertEquals("the wait is subscribed exactly once", 1, eventSource.armCount);
    assertNotNull("the wait stays armed", eventSource.consumer);
    assertEquals("the timeout is posted with the wait's delay", 1, delayer.countWithDelay(800));
    assertEquals("no control dispatch is scheduled yet", 0, delayer.countWithDelay(400));
    String log = collapsedActionsLog();
    assertTrue(log.contains("focusFirst focusAction result=true"));
    assertTrue(log.contains("perform async: focus wait armed"));
  }

  @Test
  public void likeClick_focusEventArrives_activationOnTheButton_focusRestored() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    focusProvider.focus = tree.like;
    eventSource.fireEvent(tree.like);

    assertEquals(
        "focus moves onto the live button at arm time and back to the item afterwards",
        Arrays.asList(tree.like, tree.root),
        performer.nodes);
    assertEquals(
        "no direct click action is fired at the button in this route",
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
    assertEquals("the wait is disarmed once it resolves", null, eventSource.consumer);
    assertEquals("no control dispatch is scheduled once the route lands", 0,
        delayer.countWithDelay(400));

    delayer.runWithDelay(800);

    assertEquals("the stale timeout must not re-run the ladder", 1, returner.partCount);
    assertEquals(2, performer.calls());
    assertTrue(
        collapsedActionsLog().contains("focusFirst focusEvent timeout ignored"));
  }

  @Test
  public void likeClick_logRecordsStagesInOrder_withTheEventLatency() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    focusProvider.focus = tree.like;
    eventSource.fireEvent(tree.like);

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
    int action = log.indexOf("focusFirst focusAction result=true");
    int arrived = log.indexOf("focusFirst focusEvent arrived=true latencyMs=");
    int verified = log.indexOf("focusFirst focusedNodeVerified=true");
    int part = log.indexOf("focusFirst clickNode result=true");
    int restored = log.indexOf("focusFirst focusRestored=true");
    int done = log.indexOf("perform result=true");
    assertTrue("the focus action is logged after the descriptor", action > descriptor);
    assertTrue("the focus event's arrival and latency are logged", arrived > action);
    assertTrue("the event source is matched against the button",
        log.contains("sourceMatched=true"));
    assertTrue("the fresh-fetch verification follows the event", verified > arrived);
    assertTrue("the activation part is issued once verified", part > verified);
    assertTrue("the focus restore is logged after the part", restored > part);
    assertTrue("the perform result closes the ladder", done > restored);
  }

  @Test
  public void replyClick_focusEventAimsAtTheLiveReplyButton() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);

    ContextMenuItem reply = findItem(tree.root, "Reply");

    assertTrue(reply.onClickPerformed());
    focusProvider.focus = tree.reply;
    eventSource.fireEvent(tree.reply);

    assertEquals(Arrays.asList(tree.reply, tree.root), performer.nodes);
    assertEquals(Arrays.asList(tree.reply), returner.focusTargets);
    assertEquals(tree.reply, returner.simulatedTarget);
    assertEquals(0, delayer.countWithDelay(400));
  }

  @Test
  public void likeClick_staleStoredFrame_focusWaitStillPerforms() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    focusProvider.focus = tree.like;
    eventSource.fireEvent(tree.like);

    assertEquals(
        "the live button performs even while the stored frame snapshot is stale",
        Arrays.asList(tree.like, tree.root),
        performer.nodes);
    assertEquals(1, returner.partCount);
  }

  @Test
  public void focusEventWhileFocusStillOnTheItem_waitStaysArmed_realEventActivates() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    // A focus event arrives, but the fresh fetch still shows the item: not the
    // button's arrival, so nothing is activated and the wait stays armed.
    eventSource.fireEvent(tree.like);

    assertEquals(0, returner.partCount);
    assertEquals(1, performer.calls());
    assertNotNull("the wait stays armed for the real event", eventSource.consumer);
    assertTrue(
        collapsedActionsLog().contains("focusFirst focusEvent ignored: focus still on the item"));

    focusProvider.focus = tree.like;
    eventSource.fireEvent(tree.like);

    assertEquals(Arrays.asList(tree.like), returner.focusTargets);
    assertEquals(Arrays.asList(tree.like, tree.root), performer.nodes);
  }

  @Test
  public void focusEventForAnotherNode_noActivationOnTheButton_fallbacksRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    focusProvider.focus = tree.author;
    eventSource.fireEvent(tree.author);

    assertFalse(
        "the button is never activated when focus landed elsewhere",
        returner.focusTargets.contains(tree.like));
    assertEquals(
        "focus is still restored to the item the user was standing on",
        Arrays.asList(tree.like, tree.root),
        performer.nodes);
    assertEquals("the stored tiers run from the continuation", tree.likeWrap,
        returner.simulatedTarget);
    assertEquals("the control dispatch is scheduled as before", 1, delayer.countWithDelay(400));
    String log = collapsedActionsLog();
    assertTrue(log.contains("focusFirst focusedNodeVerified=false"));
    assertFalse(log.contains("focusFirst clickNode result"));
    assertTrue(log.contains("perform result=true"));
  }

  @Test
  public void likeLongPress_waitsForTheEvent_thenLongClickNode_refused_fallsBack() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);
    RecordingTapper tapper = new RecordingTapper();
    CollapsedItemActions.setSimulatedTapperForTest(tapper);

    ContextMenuItem longPress = findItem(tree.root, "Like, long press");

    assertTrue(longPress.onClickPerformed());
    assertEquals("no part is emitted before the focus event", 0, returner.partCount);
    assertEquals(1, performer.calls());

    focusProvider.focus = tree.like;
    eventSource.fireEvent(tree.like);

    assertEquals(
        "focus is restored to the item even though the activation was refused",
        Arrays.asList(tree.like, tree.root),
        performer.nodes);
    assertEquals(
        "the long-click part tries the focused live button first, then the stored frame",
        Arrays.asList(tree.like, tree.likeWrap),
        returner.focusTargets);
    assertEquals("the control dispatch is scheduled after the refused part", 1,
        delayer.countWithDelay(400));
    assertEquals(tree.likeWrap, tapper.node);
    assertTrue(tapper.longClick);
    String log = collapsedActionsLog();
    assertTrue(log.contains("focusFirst longClickNode result=false"));
    assertTrue(log.contains("focusFirst focusRestored=true"));
    assertTrue(log.contains("perform result=true"));
  }

  @Test
  public void timeout_noActivation_controlAndTiersRun_totalFailureAnnounced() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    standOn(tree);
    RecordingTapper tapper = new RecordingTapper();
    tapper.result = false;
    CollapsedItemActions.setSimulatedTapperForTest(tapper);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue("the armed wait reports the click as handled", like.onClickPerformed());
    assertEquals(0, returner.partCount);

    delayer.runWithDelay(800);

    String log = collapsedActionsLog();
    assertTrue(log.contains("focusFirst focusEvent timeout=true afterMs=800"));
    assertTrue(log.contains("focusFirst focusRestored=true"));
    assertTrue(log.contains("perform result=false"));
    assertFalse(
        "no activation part ever aimed at the button", returner.focusTargets.contains(tree.like));
    assertEquals("the final tier still attempted the stored target", tree.likeWrap, tapper.node);
    assertEquals(
        "total failure is announced through the pipeline",
        Arrays.asList(context.getString(R.string.action_not_supported)),
        returner.speeches);
    assertEquals("the control dispatch is scheduled as before", 1, delayer.countWithDelay(400));

    delayer.runWithDelay(400);

    assertEquals(1, gestureDispatcher.calls);
    assertNotNull(gestureDispatcher.gesture);
    assertEquals(
        "the control press is the canonical two-stroke tap",
        2,
        gestureDispatcher.gesture.getStrokeCount());
    gestureDispatcher.completeWith(/* completed= */ false);
    log = collapsedActionsLog();
    int accepted = log.indexOf("delayedDispatch accepted=true");
    int cancelled = log.indexOf("delayedDispatch onCancelled");
    assertTrue("dispatch acceptance is logged apart from completion", accepted >= 0);
    assertTrue("cancellation is logged distinctly", cancelled > accepted);
  }

  @Test
  public void controlDispatch_onCompleted_loggedAfterAcceptance() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    standOn(tree);
    RecordingTapper tapper = new RecordingTapper();
    tapper.result = false;
    CollapsedItemActions.setSimulatedTapperForTest(tapper);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    delayer.runWithDelay(800);
    delayer.runWithDelay(400);
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
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    delayer.runWithDelay(800);
    assertEquals("the stored tiers performed after the timeout", tree.likeWrap,
        returner.simulatedTarget);
    assertEquals(1, delayer.countWithDelay(400));
    delayer.runWithDelay(400);
    assertEquals(
        "the control never double-fires after a stored tier landed", 0, gestureDispatcher.calls);
    assertTrue(
        collapsedActionsLog()
            .contains("delayedDispatch skipped: a stored tier already performed"));
  }

  @Test
  public void lateEventAfterTimeout_ignored_noSecondActivation() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    delayer.runWithDelay(800);
    int partsAfterTimeout = returner.partCount;
    int callsAfterTimeout = performer.calls();
    assertTrue("the stored tiers ran after the timeout", partsAfterTimeout > 0);

    focusProvider.focus = tree.like;
    eventSource.fireLateEvent(tree.like);

    assertEquals("a late event must not activate anything", partsAfterTimeout,
        returner.partCount);
    assertEquals(callsAfterTimeout, performer.calls());
    assertFalse(returner.focusTargets.contains(tree.like));
    assertTrue(
        collapsedActionsLog().contains("focusFirst focusEvent ignored: wait already resolved"));
  }

  @Test
  public void focusActionRefused_waitStillArmed_timeoutRestoresAndFallsBack() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    standOn(tree);
    performer.result = false;

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals("only the focus action was attempted", 1, performer.calls());
    assertEquals(0, returner.partCount);
    assertTrue(collapsedActionsLog().contains("focusFirst focusAction result=false"));

    delayer.runWithDelay(800);

    assertEquals(
        "the restore is attempted even when every focus action is refused",
        2,
        performer.calls());
    assertEquals(tree.likeWrap, returner.simulatedTarget);
    String log = collapsedActionsLog();
    assertTrue(log.contains("focusFirst focusRestored=false"));
    assertTrue(log.contains("perform result=true"));
  }

  @Test
  public void focusOnAnotherComment_relocationRefused_noWaitArmed_storedTiersRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    FacebookCommentTree other = CollapseTestTrees.facebookCommentNestedShortBodyWithFollow(list);
    focusProvider = new MutableFocusProvider(other.root);
    CollapsedItemActions.setFocusNodeProviderForTest(focusProvider);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals("a different item's button must never be acted on", 0, performer.calls());
    assertEquals("no wait is armed when relocation is refused", 0, eventSource.armCount);
    assertEquals(0, delayer.count());
    assertEquals(tree.likeWrap, returner.simulatedTarget);
    assertNull(returner.actionTarget);
  }

  @Test
  public void noFocus_noWaitArmed_storedTiersRun() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(0, performer.calls());
    assertEquals(0, eventSource.armCount);
    assertEquals(0, delayer.count());
    assertEquals(tree.likeWrap, returner.simulatedTarget);
  }

  @Test
  public void youTubeItem_neverEntersTheFocusWait() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    focusProvider = new MutableFocusProvider(tree.primary);
    CollapsedItemActions.setFocusNodeProviderForTest(focusProvider);

    ContextMenuItem openMenu = findItem(tree.primary, "Open menu");

    assertTrue(openMenu.onClickPerformed());
    assertEquals("primary-child items keep the stored-target routes", 0, performer.calls());
    assertEquals(0, eventSource.armCount);
    assertEquals(0, delayer.count());
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
