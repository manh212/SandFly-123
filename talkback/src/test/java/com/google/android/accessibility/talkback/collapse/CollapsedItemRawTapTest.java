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
import android.view.ViewConfiguration;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The raw-tap route of a composite item's synthesized action: at activation time the live
 * button is re-located from the item holding accessibility focus and tapped directly, with
 * a finger-like duration, instead of pressing the stored frame snapshot. A unit test has
 * no accessibility service, so the focus node and the tap itself are stood in for through
 * the listener's seams; what is pinned here is which node gets tapped, with what duration,
 * and that any relocation doubt falls back to the stored-target tiers untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CollapsedItemRawTapTest {

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

  /** Records the raw tap instead of dispatching it; can be told to refuse it. */
  private static final class RecordingRawTapper implements CollapsedItemActions.RawTapper {
    AccessibilityNodeInfoCompat node;
    long durationMs = -1;
    int calls;
    boolean result = true;

    @Override
    public boolean tap(Context context, AccessibilityNodeInfoCompat target, long duration) {
      node = target;
      durationMs = duration;
      calls++;
      return result;
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
  private RecordingRawTapper rawTapper;
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
    rawTapper = new RecordingRawTapper();
    CollapsedItemActions.setRawTapperForTest(rawTapper);
    actions = new CollapsedItemActions(returner, /* analytics= */ null);
  }

  @After
  public void tearDown() {
    CollapsedItemActions.resetFocusNodeProviderForTest();
    CollapsedItemActions.resetRawTapperForTest();
  }

  @Test
  public void likeClick_liveButtonRelocated_rawTapsTheButtonNotTheFrame() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(1, rawTapper.calls);
    assertEquals("the tap lands on the live button inside the frame", tree.like, rawTapper.node);
    assertEquals("a click is one finger-like stroke", 80, rawTapper.durationMs);
    assertEquals("no stored-target tier may run once the raw tap lands", 0, returner.partCount);
  }

  @Test
  public void likeClick_staleFrame_liveButtonStillRawTapped() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapseTestTrees.bounds(tree.likeWrap, 0, 0, 0, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue("the live button performs even while the stored frame snapshot is stale",
        like.onClickPerformed());
    assertEquals(1, rawTapper.calls);
    assertEquals(tree.like, rawTapper.node);
    assertEquals(0, returner.partCount);
  }

  @Test
  public void likeLongPress_liveButtonRelocated_rawTapsWithLongPressDuration() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem longPress = findItem(tree.root, "Like, long press");

    assertTrue(longPress.onClickPerformed());
    assertEquals(1, rawTapper.calls);
    assertEquals(tree.like, rawTapper.node);
    assertEquals(
        "a long press holds past the system long-press timeout",
        ViewConfiguration.getLongPressTimeout() + 100,
        rawTapper.durationMs);
    assertEquals(0, returner.partCount);
  }

  @Test
  public void replyClick_liveButtonRelocated_rawTapsTheReplyButton() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));

    ContextMenuItem reply = findItem(tree.root, "Reply");

    assertTrue(reply.onClickPerformed());
    assertEquals(1, rawTapper.calls);
    assertEquals(tree.reply, rawTapper.node);
    assertEquals(80, rawTapper.durationMs);
    assertEquals(0, returner.partCount);
  }

  @Test
  public void focusOnAnotherComment_relocationRefused_fallsBackToStoredTarget() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    FacebookCommentTree other = CollapseTestTrees.facebookCommentNestedShortBodyWithFollow(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(other.root));

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals("focus on a different item must never be raw-tapped", 0, rawTapper.calls);
    assertEquals(tree.likeWrap, returner.simulatedTarget);
    assertNull(returner.actionTarget);
  }

  @Test
  public void noFocus_relocationSkipped_fallsBackToStoredTarget() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(0, rawTapper.calls);
    assertEquals(tree.likeWrap, returner.simulatedTarget);
  }

  @Test
  public void rawTapRefused_fallsBackToStoredTarget() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.FACEBOOK_PACKAGE);
    FacebookCommentTree tree = CollapseTestTrees.facebookComment(list);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.root));
    rawTapper.result = false;

    ContextMenuItem like = findItem(tree.root, "Like");

    assertTrue(like.onClickPerformed());
    assertEquals(1, rawTapper.calls);
    assertEquals(tree.likeWrap, returner.simulatedTarget);
  }

  @Test
  public void youTubeItem_neverRawTaps() {
    AccessibilityNodeInfoCompat list = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree tree = CollapseTestTrees.youTubeItem(list, 0);
    CollapsedItemActions.setFocusNodeProviderForTest(new FixedFocusProvider(tree.primary));

    ContextMenuItem openMenu = findItem(tree.primary, "Open menu");

    assertTrue(openMenu.onClickPerformed());
    assertEquals("primary-child items keep the stored-target routes", 0, rawTapper.calls);
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
