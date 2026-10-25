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
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.FacebookCommentTree;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.FacebookTrayTree;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeTree;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * The tray route of a composite item's Like and Reply actions: instead of pressing the
 * button buried inside the comment — which exposes no ACTION_CLICK, and whose frame
 * produces no click inside the app at all — the activation fires a real long press at
 * the comment's live body shell, opening Facebook's own action tray, and the sequence
 * then clicks the tray's real button. A unit test has no accessibility service, so the
 * focus node is stood in for through the listener's seam and the tray's window events
 * are delivered to the automation by hand; what is pinned here is the opener's target
 * and action, the anchor matching (including the wrong-comment abort), which tray
 * button each action clicks, the open-only long press, the honest failures, and the
 * kill-axis log stages.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemTrayActivationTest {

  /** One performed node action, in emission order. */
  private static final class NodeAction {
    final AccessibilityNodeInfoCompat target;
    final int actionId;

    NodeAction(AccessibilityNodeInfoCompat target, int actionId) {
      this.target = target;
      this.actionId = actionId;
    }
  }

  /** Performs feedback parts under the device contract, recording every part emitted. */
  private static final class PerformingReturner implements Pipeline.FeedbackReturner {
    final List<NodeAction> nodeActions = new ArrayList<>();
    final List<AccessibilityNodeInfoCompat> focusTargets = new ArrayList<>();
    final List<String> speeches = new ArrayList<>();
    AccessibilityNodeInfoCompat simulatedTarget;

    @Override
    public boolean returnFeedback(Feedback feedback) {
      return true;
    }

    @Override
    public boolean returnFeedback(EventId eventId, Feedback.Part.Builder partBuilder) {
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
        nodeActions.add(new NodeAction(target, id));
        return true;
      }
      if (part.focus() != null && part.focus().target() != null) {
        AccessibilityNodeInfoCompat target = part.focus().target();
        focusTargets.add(target);
        if (part.focus().action() == Feedback.Focus.Action.CLICK_NODE) {
          if (PerformActionUtils.isNodeSupportAction(
              target, AccessibilityNodeInfoCompat.ACTION_CLICK)) {
            nodeActions.add(
                new NodeAction(target, AccessibilityNodeInfoCompat.ACTION_CLICK));
            return true;
          }
          simulatedTarget = target;
          return true;
        }
        if (part.focus().action() == Feedback.Focus.Action.LONG_CLICK_NODE) {
          return PerformActionUtils.isNodeSupportAction(
              target, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
        }
      }
      return true;
    }
  }

  private static final class FakeRootProvider implements MenuAutomation.RootProvider {
    List<AccessibilityNodeInfoCompat> roots = new ArrayList<>();
    AccessibilityNodeInfoCompat activeRoot;

    @Override
    public List<AccessibilityNodeInfoCompat> windowRoots() {
      return roots;
    }

    @Override
    public AccessibilityNodeInfoCompat activeWindowRoot() {
      return activeRoot;
    }
  }

  /** Stands in for the live accessibility focus; the test sets it by hand. */
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

  private Context context;
  private PerformingReturner returner;
  private FakeRootProvider provider;
  private CollapsedItemActions actions;
  private AccessibilityNodeInfoCompat list;
  private FacebookCommentTree tree;

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
    provider = new FakeRootProvider();
    LogUtils.setLogLevel(Log.VERBOSE);
    actions = new CollapsedItemActions(returner, /* analytics= */ null);
    list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    tree = CollapseTestTrees.facebookComment(list);
    provider.roots = List.of(list);
    provider.activeRoot = list;
  }

  @After
  public void tearDown() {
    CollapsedItemActions.resetFocusNodeProviderForTest();
    CollapsedItemActions.resetSimulatedTapperForTest();
    MenuAutomation.resetForTest();
    LogUtils.setLogLevel(Log.ERROR);
  }

  /** Stands on the comment and lets its live body shell expose the long press. */
  private void standOnCommentWithTrayOpener() {
    CollapsedItemActions.setFocusNodeProviderForTest(new MutableFocusProvider(tree.root));
    tree.bodyActionTarget.addAction(
        new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, null));
  }

  private static AccessibilityEvent event(int type) {
    AccessibilityEvent event = AccessibilityEvent.obtain(type);
    event.setPackageName(CollapseTestTrees.FACEBOOK_PACKAGE);
    return event;
  }

  private void deliverTrayWindow(FacebookTrayTree tray) {
    provider.roots = List.of(tray.root, list);
    provider.activeRoot = tray.root;
    MenuAutomation.handleEvent(provider, event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
  }

  private void deliverFeedWindow() {
    provider.roots = List.of(list);
    provider.activeRoot = list;
    MenuAutomation.handleEvent(provider, event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
  }

  @Test
  public void likeClick_openerFiresLongClickOnTheLiveBodyShell() {
    standOnCommentWithTrayOpener();

    assertTrue(findItem(tree.root, "Like").onClickPerformed());

    assertEquals(1, returner.nodeActions.size());
    assertEquals(tree.bodyActionTarget, returner.nodeActions.get(0).target);
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, returner.nodeActions.get(0).actionId);
    assertTrue("the tray sequence stays armed", MenuAutomation.isPending());
    assertNull("no simulated press is part of the tray route", returner.simulatedTarget);
    String log = collapsedActionsLog();
    assertTrue(log.contains("tray bodyShell"));
    assertTrue(log.contains("supportsLongClick=true"));
    assertTrue(log.contains("tray opener nodeAction result=true"));
    assertTrue(log.contains("perform traySequence armed=true kind=LIKE"));
  }

  @Test
  public void likeClick_trayAppears_clicksTheTrayLikeButton_restoresFocusAfterClose() {
    standOnCommentWithTrayOpener();
    assertTrue(findItem(tree.root, "Like").onClickPerformed());
    FacebookTrayTree tray = CollapseTestTrees.facebookTray(true, null);

    deliverTrayWindow(tray);

    assertEquals(2, returner.nodeActions.size());
    assertEquals(tray.likeButton, returner.nodeActions.get(1).target);
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_CLICK, returner.nodeActions.get(1).actionId);
    String log = collapsedActionsLog();
    assertTrue(log.contains("tray sheet appeared afterMs="));
    assertTrue(log.contains("tray button label=Like supportsClick=true"));
    assertTrue(log.contains("tray target click label=Like result=true"));

    deliverFeedWindow();

    assertFalse(MenuAutomation.isPending());
    assertTrue(
        "focus returns to the originating comment",
        returner.focusTargets.contains(tree.root));
  }

  @Test
  public void replyClick_trayAppears_clicksTheTrayReplyButton_focusStaysWithTheComposer() {
    standOnCommentWithTrayOpener();
    assertTrue(findItem(tree.root, "Reply").onClickPerformed());
    FacebookTrayTree tray = CollapseTestTrees.facebookTray(true, null);

    deliverTrayWindow(tray);

    assertEquals(2, returner.nodeActions.size());
    assertEquals(tray.replyButton, returner.nodeActions.get(1).target);
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_CLICK, returner.nodeActions.get(1).actionId);

    // The tray closes onto the composer: the sequence must end without pulling focus
    // back to the comment, or the user would lose the composer they just opened.
    deliverFeedWindow();

    assertFalse(MenuAutomation.isPending());
    assertTrue(
        "no focus part may target the comment after a Reply",
        returner.focusTargets.isEmpty());
  }

  @Test
  public void likeLongPress_opensTheTrayOnly_noReactionIsClicked() {
    standOnCommentWithTrayOpener();

    assertTrue(findItem(tree.root, "Like, long press").onClickPerformed());
    assertEquals(1, returner.nodeActions.size());
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, returner.nodeActions.get(0).actionId);
    FacebookTrayTree tray = CollapseTestTrees.facebookTray(true, null);

    deliverTrayWindow(tray);

    assertEquals(
        "the tray is left open for the user: no button inside it is clicked",
        1,
        returner.nodeActions.size());
    assertFalse("the sequence ends once the tray is up", MenuAutomation.isPending());
    assertTrue(collapsedActionsLog().contains("tray openOnly: sheet left open for the user"));
    assertTrue(returner.focusTargets.isEmpty());
  }

  @Test
  public void trayDetectedByTheAuthorAnchor_withoutAHeader() {
    standOnCommentWithTrayOpener();
    assertTrue(findItem(tree.root, "Like").onClickPerformed());
    FacebookTrayTree tray = CollapseTestTrees.facebookTray(false, "Nguyen Van A");

    deliverTrayWindow(tray);

    assertEquals(2, returner.nodeActions.size());
    assertEquals(tray.likeButton, returner.nodeActions.get(1).target);
  }

  @Test
  public void trayAnchoredToAnotherComment_abortsWithoutClicking_andAnnounces() {
    standOnCommentWithTrayOpener();
    assertTrue(findItem(tree.root, "Like").onClickPerformed());
    FacebookTrayTree foreignTray = CollapseTestTrees.facebookTray(false, "Someone Else");

    deliverTrayWindow(foreignTray);

    assertEquals(
        "only the opener fired; nothing inside another comment's tray is clicked",
        1,
        returner.nodeActions.size());
    assertFalse(MenuAutomation.isPending());
    assertTrue(collapsedActionsLog().contains("tray anchor mismatch"));
    assertEquals(
        List.of(context.getString(R.string.action_not_supported)), returner.speeches);
  }

  @Test
  public void trayNeverAppears_masterTimeout_honestFailureAndFocusRestore() {
    standOnCommentWithTrayOpener();
    assertTrue(findItem(tree.root, "Like").onClickPerformed());

    // One feed event so the sequence knows where the windows are, then the deadline.
    MenuAutomation.handleEvent(provider, event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    shadowOf(Looper.getMainLooper()).idleFor(3100, TimeUnit.MILLISECONDS);

    assertFalse(MenuAutomation.isPending());
    assertTrue(returner.focusTargets.contains(tree.root));
    assertEquals(
        List.of(context.getString(R.string.action_not_supported)), returner.speeches);
    String log = collapsedActionsLog();
    assertTrue(log.contains("tray sheet never appeared within the master timeout"));
  }

  @Test
  public void openerRefused_shellExposesNoLongClick_honestSynchronousFailure() {
    // No ACTION_LONG_CLICK on the shell: the recorded refusal, in the perform's own log.
    CollapsedItemActions.setFocusNodeProviderForTest(new MutableFocusProvider(tree.root));

    assertFalse(findItem(tree.root, "Like").onClickPerformed());

    assertEquals(0, returner.nodeActions.size());
    assertFalse(MenuAutomation.isPending());
    String log = collapsedActionsLog();
    assertTrue(log.contains("supportsLongClick=false"));
    assertTrue(log.contains("tray opener nodeAction result=false"));
  }

  @Test
  public void focusOnAnotherComment_noTraySequence_noStoredTierEither() {
    AccessibilityNodeInfoCompat otherList =
        CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    // A different comment: another body text, so its composed label differs.
    FacebookCommentTree other =
        CollapseTestTrees.facebookCommentNestedShortBodyWithFollow(otherList);
    CollapsedItemActions.setFocusNodeProviderForTest(new MutableFocusProvider(other.root));
    tree.bodyActionTarget.addAction(
        new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, null));

    assertFalse(findItem(tree.root, "Like").onClickPerformed());

    assertEquals(0, returner.nodeActions.size());
    assertNull(returner.simulatedTarget);
    assertFalse(MenuAutomation.isPending());
    assertTrue(
        collapsedActionsLog()
            .contains("tray sequence failed: live item does not match the menu item"));
  }

  @Test
  public void otherCompositeAction_keepsTheStoredTiers() {
    CollapsedItemActions.setFocusNodeProviderForTest(new MutableFocusProvider(tree.root));

    assertTrue(findItem(tree.root, "Demote comment").onClickPerformed());

    assertEquals(tree.demote, returner.simulatedTarget);
    assertEquals(0, returner.nodeActions.size());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void youTubeItem_neverEntersTheTrayRoute() {
    AccessibilityNodeInfoCompat youtubeList =
        CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree youtube = CollapseTestTrees.youTubeItem(youtubeList, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new MutableFocusProvider(youtube.primary));

    assertTrue(findItem(youtube.primary, "Open menu").onClickPerformed());

    assertEquals(youtube.menu, returner.simulatedTarget);
    assertFalse(MenuAutomation.isPending());
  }

  private ContextMenuItem findItem(AccessibilityNodeInfoCompat representative, String title) {
    for (ContextMenuItem item : actions.getMenuItemsForNode(context, representative, false)) {
      if (title.contentEquals(item.getTitle())) {
        return item;
      }
    }
    throw new AssertionError("No action titled " + title);
  }

  private String collapsedActionsLog() {
    StringBuilder all = new StringBuilder();
    for (LogItem item : ShadowLog.getLogsForTag("CollapsedItemActions")) {
      all.append(item.msg).append('\n');
    }
    return all.toString();
  }
}
