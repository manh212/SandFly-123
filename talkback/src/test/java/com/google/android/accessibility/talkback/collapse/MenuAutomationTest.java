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
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeSheetTree;
import com.google.android.accessibility.talkback.collapse.CollapseTestTrees.YouTubeTree;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MenuAutomationTest {
  private static final String SHEET_ID = CollapseTestTrees.YOUTUBE_MENU_LABEL_VIEW_ID;
  private static final String ORIGIN_LABEL =
      "Video title - 10 minutes, 24 seconds - Go to channel Channel X - Channel X,"
          + " 1M views - 15 minutes ago - play video";

  // The toggle specs mirror the wording recorded in YouTubeCollapseScript.
  private static final String[] WATCH_LATER_VARIANTS = {
    "Lưu vào Xem sau",
    "Lưu vào danh sách Xem sau",
    "Xóa khỏi Xem sau",
    "Xóa khỏi danh sách Xem sau",
    "Save to Watch later",
    "Remove from Watch later",
  };
  private static final String[] WATCH_LATER_CORES = {"Xem sau", "Watch later"};
  private static final String[] DOWNLOAD_VARIANTS = {
    "Tải xuống",
    "Xóa khỏi danh sách video tải xuống",
    "Download",
    "Remove from downloaded videos",
    "Remove from downloads",
  };
  private static final String[] DOWNLOAD_CORES = {"tải xuống", "download"};

  /** Records every feedback part routed through the returner, in order. */
  private static final class RecordingReturner implements Pipeline.FeedbackReturner {
    final List<Feedback.Part> parts = new ArrayList<>();

    @Override
    public boolean returnFeedback(Feedback feedback) {
      parts.addAll(feedback.failovers());
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

  private Context context;
  private RecordingReturner returner;
  private FakeRootProvider provider;
  private AccessibilityNodeInfoCompat feedList;
  private YouTubeTree feedTree;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    returner = new RecordingReturner();
    provider = new FakeRootProvider();
    feedList = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    feedTree = CollapseTestTrees.youTubeItem(feedList, 0);
    provider.roots = List.of(feedList);
    provider.activeRoot = feedList;
  }

  @After
  public void tearDown() {
    MenuAutomation.resetForTest();
  }

  @Test
  public void sheetHelpers_findRootAndRowByLiveLabel() {
    YouTubeSheetTree sheet =
        CollapseTestTrees.youTubeMenuSheet("Play next in queue", "Share", "Not interested");

    assertEquals(
        sheet.root, MenuAutomation.findSheetRoot(List.of(sheet.root), SHEET_ID));
    assertNull(MenuAutomation.findSheetRoot(List.of(feedList), SHEET_ID));
    assertEquals(
        sheet.rows.get(1), MenuAutomation.sheetTargetRow(sheet.root, SHEET_ID, "Share"));
    assertEquals(
        sheet.rows.get(1), MenuAutomation.sheetTargetRow(sheet.root, SHEET_ID, "  Share "));
    assertNull(MenuAutomation.sheetTargetRow(sheet.root, SHEET_ID, "Report"));
  }

  @Test
  public void sheetHelpers_plainRowStillNeedsTheExactLabel() {
    YouTubeSheetTree sheet = CollapseTestTrees.youTubeMenuSheet("Shares", "share");

    assertNull(MenuAutomation.sheetTargetRow(sheet.root, SHEET_ID, "Share"));
  }

  @Test
  public void sheetHelpers_toggleRowMatchesEitherState_vietnameseAndEnglish() {
    YouTubeSheetTree savedLater = CollapseTestTrees.youTubeMenuSheet("Chia sẻ", "Xóa khỏi Xem sau");
    assertEquals(
        savedLater.rows.get(1),
        MenuAutomation.sheetToggleTargetRow(
            savedLater.root, SHEET_ID, WATCH_LATER_VARIANTS, WATCH_LATER_CORES));

    YouTubeSheetTree notSavedYet =
        CollapseTestTrees.youTubeMenuSheet("Lưu vào danh sách Xem sau", "Chia sẻ");
    assertEquals(
        notSavedYet.rows.get(0),
        MenuAutomation.sheetToggleTargetRow(
            notSavedYet.root, SHEET_ID, WATCH_LATER_VARIANTS, WATCH_LATER_CORES));

    YouTubeSheetTree downloaded =
        CollapseTestTrees.youTubeMenuSheet("Xóa khỏi danh sách video tải xuống");
    assertEquals(
        downloaded.rows.get(0),
        MenuAutomation.sheetToggleTargetRow(
            downloaded.root, SHEET_ID, DOWNLOAD_VARIANTS, DOWNLOAD_CORES));

    YouTubeSheetTree notDownloaded = CollapseTestTrees.youTubeMenuSheet("Tải xuống", "Chia sẻ");
    assertEquals(
        notDownloaded.rows.get(0),
        MenuAutomation.sheetToggleTargetRow(
            notDownloaded.root, SHEET_ID, DOWNLOAD_VARIANTS, DOWNLOAD_CORES));

    // English states, one per pair, sharing one sheet.
    YouTubeSheetTree english =
        CollapseTestTrees.youTubeMenuSheet(
            "Remove from Watch later", "Remove from downloaded videos");
    assertEquals(
        english.rows.get(0),
        MenuAutomation.sheetToggleTargetRow(
            english.root, SHEET_ID, WATCH_LATER_VARIANTS, WATCH_LATER_CORES));
    assertEquals(
        english.rows.get(1),
        MenuAutomation.sheetToggleTargetRow(
            english.root, SHEET_ID, DOWNLOAD_VARIANTS, DOWNLOAD_CORES));
  }

  @Test
  public void sheetHelpers_toggleRowMatchesAnUnrecordedStateWording_byCore() {
    // Neither label below is a recorded variant; each still carries its pair's stable core.
    YouTubeSheetTree sheet =
        CollapseTestTrees.youTubeMenuSheet("Thêm vào Xem sau", "Xóa video đã tải xuống");
    assertEquals(
        sheet.rows.get(0),
        MenuAutomation.sheetToggleTargetRow(
            sheet.root, SHEET_ID, WATCH_LATER_VARIANTS, WATCH_LATER_CORES));
    assertEquals(
        sheet.rows.get(1),
        MenuAutomation.sheetToggleTargetRow(
            sheet.root, SHEET_ID, DOWNLOAD_VARIANTS, DOWNLOAD_CORES));
  }

  @Test
  public void sheetHelpers_toggleRowAmbiguousOrAbsent_returnsNull() {
    YouTubeSheetTree bothStates =
        CollapseTestTrees.youTubeMenuSheet("Tải xuống", "Xóa khỏi danh sách video tải xuống");
    assertNull(
        MenuAutomation.sheetToggleTargetRow(
            bothStates.root, SHEET_ID, DOWNLOAD_VARIANTS, DOWNLOAD_CORES));

    YouTubeSheetTree unrelated = CollapseTestTrees.youTubeMenuSheet("Share", "Report");
    assertNull(
        MenuAutomation.sheetToggleTargetRow(
            unrelated.root, SHEET_ID, WATCH_LATER_VARIANTS, WATCH_LATER_CORES));
    assertNull(
        MenuAutomation.sheetToggleTargetRow(
            unrelated.root, SHEET_ID, DOWNLOAD_VARIANTS, DOWNLOAD_CORES));
  }

  @Test
  public void toggleTarget_clicksTheRowOfTheStateTheVideoIsIn() {
    beginToggle("Add or remove from Watch later", WATCH_LATER_VARIANTS, WATCH_LATER_CORES);
    // This video is already saved: the sheet offers the remove wording.
    YouTubeSheetTree sheet =
        CollapseTestTrees.youTubeMenuSheet("Play next in queue", "Xóa khỏi Xem sau", "Share");
    showOnly(sheet.root);

    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    Feedback.Part rowClick = lastNodeActionPart();
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, rowClick.nodeAction().actionId());
    assertEquals(sheet.rows.get(1), rowClick.nodeAction().target().getCompat());

    showOnly(feedList);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertEquals(feedTree.primary, lastFocusPart().focus().target());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void toggleTarget_ambiguousSheet_clicksNothing_backsOut_andSpeaksNotAvailable() {
    beginToggle("Add or remove from offline videos", DOWNLOAD_VARIANTS, DOWNLOAD_CORES);
    // Both states of the downloads item in one sheet: no row may be guessed.
    YouTubeSheetTree sheet =
        CollapseTestTrees.youTubeMenuSheet("Tải xuống", "Xóa khỏi danh sách video tải xuống");
    showOnly(sheet.root);

    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertEquals("no row may be clicked", 1, nodeActionParts().size());

    shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS);
    assertNotNull(lastSystemActionPart());
    assertEquals(
        AccessibilityService.GLOBAL_ACTION_BACK,
        lastSystemActionPart().systemAction().systemActionId());

    showOnly(feedList);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertEquals(feedTree.primary, lastFocusPart().focus().target());
    assertEquals("Not available", lastSpeechPart().speech().text().toString());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void matchingSheetRow_isClicked_sheetEventsStaySilent_focusReturnsToTheRebuiltRow() {
    beginShare();
    YouTubeSheetTree sheet =
        CollapseTestTrees.youTubeMenuSheet("Play next in queue", "Share", "Not interested");
    showOnly(sheet.root);

    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    Feedback.Part rowClick = lastNodeActionPart();
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, rowClick.nodeAction().actionId());
    assertEquals(sheet.rows.get(1), rowClick.nodeAction().target().getCompat());

    int partsAfterClick = returner.parts.size();
    assertTrue(deliver(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
    assertEquals("the sheet must not produce any feedback", partsAfterClick, returner.parts.size());

    // The list recycled every node while the sheet was up; the feed is in front again.
    AccessibilityNodeInfoCompat feedList2 = CollapseTestTrees.list(CollapseTestTrees.YOUTUBE_PACKAGE);
    YouTubeTree feedTree2 = CollapseTestTrees.youTubeItem(feedList2, 0);
    showOnly(feedList2);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));

    assertEquals(feedTree2.primary, lastFocusPart().focus().target());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void sheetWithoutTheLabel_backsOut_restoresFocus_andSpeaksNotAvailable() {
    beginShare();
    YouTubeSheetTree sheet = CollapseTestTrees.youTubeMenuSheet("Play next in queue", "Report");
    showOnly(sheet.root);

    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertEquals("no row may be clicked", 1, nodeActionParts().size());

    shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS);
    assertNotNull(lastSystemActionPart());
    assertEquals(
        AccessibilityService.GLOBAL_ACTION_BACK,
        lastSystemActionPart().systemAction().systemActionId());

    showOnly(feedList);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertEquals(feedTree.primary, lastFocusPart().focus().target());
    assertEquals("Not available", lastSpeechPart().speech().text().toString());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void deadlineWithoutAnySheet_restoresFocus_andSpeaksNotAvailable_withoutBack() {
    beginShare();
    // One harmless event so the automation learns the current windows.
    assertTrue(deliver(AccessibilityEvent.TYPE_VIEW_SCROLLED));

    shadowOf(Looper.getMainLooper()).idleFor(3100, TimeUnit.MILLISECONDS);

    assertNull(lastSystemActionPart());
    assertEquals(feedTree.primary, lastFocusPart().focus().target());
    assertEquals("Not available", lastSpeechPart().speech().text().toString());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void userBackingOutOfTheSheet_cancelsSilently_andRestoresFocus() {
    beginShare();
    YouTubeSheetTree sheet = CollapseTestTrees.youTubeMenuSheet("Play next in queue");
    showOnly(sheet.root);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));

    showOnly(feedList);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));

    assertEquals(feedTree.primary, lastFocusPart().focus().target());
    assertNull("a back-out is not a failure", lastSpeechPart());
    assertNull("the automation must not press Back itself", lastSystemActionPart());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void noSequenceRunning_eventsPassThrough() {
    assertFalse(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
  }

  @Test
  public void anotherAppTakingTheForeground_endsTheSequenceSilently() {
    beginShare();
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    event.setPackageName("com.android.systemui");
    assertFalse(MenuAutomation.handleEvent(provider, event));
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void beginWhileASequenceRuns_isRefused() {
    beginShare();
    Rect bounds = new Rect();
    feedTree.item.getBoundsInScreen(bounds);
    assertFalse(
        MenuAutomation.begin(
            returner,
            context,
            feedTree.menu,
            "Share",
            SHEET_ID,
            ORIGIN_LABEL,
            bounds));
  }

  private void beginToggle(String neutralLabel, String[] variants, String[] cores) {
    Rect bounds = new Rect();
    feedTree.item.getBoundsInScreen(bounds);
    returner.parts.clear();
    assertTrue(
        MenuAutomation.begin(
            returner,
            context,
            feedTree.menu,
            neutralLabel,
            variants,
            cores,
            SHEET_ID,
            ORIGIN_LABEL,
            bounds));
    // The first thing a sequence does is click the row's menu button.
    assertEquals(1, returner.parts.size());
    Feedback.Part click = returner.parts.get(0);
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, click.nodeAction().actionId());
    assertEquals(feedTree.menu, click.nodeAction().target().getCompat());
  }

  private void beginShare() {
    Rect bounds = new Rect();
    feedTree.item.getBoundsInScreen(bounds);
    returner.parts.clear();
    assertTrue(
        MenuAutomation.begin(
            returner, context, feedTree.menu, "Share", SHEET_ID, ORIGIN_LABEL, bounds));
    // The first thing a sequence does is click the row's menu button.
    assertEquals(1, returner.parts.size());
    Feedback.Part click = returner.parts.get(0);
    assertEquals(AccessibilityNodeInfoCompat.ACTION_CLICK, click.nodeAction().actionId());
    assertEquals(feedTree.menu, click.nodeAction().target().getCompat());
  }

  private void showOnly(AccessibilityNodeInfoCompat root) {
    provider.roots = List.of(root);
    provider.activeRoot = root;
  }

  private boolean deliver(int eventType) {
    AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
    event.setPackageName(CollapseTestTrees.YOUTUBE_PACKAGE);
    return MenuAutomation.handleEvent(provider, event);
  }

  private List<Feedback.Part> nodeActionParts() {
    List<Feedback.Part> result = new ArrayList<>();
    for (Feedback.Part part : returner.parts) {
      if (part.nodeAction() != null) {
        result.add(part);
      }
    }
    return result;
  }

  private Feedback.Part lastNodeActionPart() {
    List<Feedback.Part> parts = nodeActionParts();
    return parts.isEmpty() ? null : parts.get(parts.size() - 1);
  }

  private Feedback.Part lastSystemActionPart() {
    Feedback.Part found = null;
    for (Feedback.Part part : returner.parts) {
      if (part.systemAction() != null) {
        found = part;
      }
    }
    return found;
  }

  private Feedback.Part lastFocusPart() {
    Feedback.Part found = null;
    for (Feedback.Part part : returner.parts) {
      if (part.focus() != null) {
        found = part;
      }
    }
    return found;
  }

  private Feedback.Part lastSpeechPart() {
    Feedback.Part found = null;
    for (Feedback.Part part : returner.parts) {
      if (part.speech() != null) {
        found = part;
      }
    }
    return found;
  }
}
