package com.google.android.accessibility.talkback.collapse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.HashSet;
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
public class ZaloCollapseScriptTest {
  private static final String ORIGIN = "Người thử nghiệm\n2 giờ\nTin nhắn mẫu\n";
  private static final List<String> TITLES =
      List.of(
          "Đánh dấu đã đọc hoặc chưa đọc",
          "Ghim hoặc bỏ ghim",
          "Bật hoặc tắt thông báo",
          "Chuyển giữa Ưu tiên và Khác",
          "Ẩn",
          "Xóa",
          "Chọn nhiều");

  private Context context;
  private RecordingReturner returner;
  private CollapsedItemActions actions;
  private Provider provider;
  private AccessibilityNodeInfoCompat root;
  private AccessibilityNodeInfoCompat list;
  private AccessibilityNodeInfoCompat conversation;
  private AccessibilityNodeInfoCompat popover;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    SharedPreferencesUtils.getSharedPreferences(context)
        .edit()
        .clear()
        .putBoolean(context.getString(R.string.pref_collapse_list_item_actions_key), true)
        .commit();
    returner = new RecordingReturner();
    actions = new CollapsedItemActions(returner, null);
    provider = new Provider();
    createScreen();
  }

  @After
  public void tearDown() {
    MenuAutomation.resetForTest();
  }

  @Test
  public void conversationOffersSevenNeutralActionsAndKeepsItsOwnLabelAndActivation() {
    CollapsedItem match = CollapseEngine.matchRepresentative(context, conversation);
    assertNotNull(match);
    assertEquals(conversation, match.representative());
    assertNull(match.composedLabel());
    assertNull(match.activationTarget());
    assertNull(match.selectionTarget());
    assertEquals(ORIGIN, ItemScan.spokenLabel(conversation).toString());
    List<ContextMenuItem> items = actions.getMenuItemsForNode(context, conversation, false);
    List<String> titles = new ArrayList<>();
    HashSet<Integer> ids = new HashSet<>();
    for (ContextMenuItem item : items) {
      titles.add(item.getTitle().toString());
      ids.add(item.getItemId());
      assertTrue(CollapsedItemActions.isCollapsedActionItem(item));
    }
    assertEquals(TITLES, titles);
    assertEquals(7, ids.size());
  }

  @Test
  public void settingOffLeavesConversationsUntouched() {
    SharedPreferencesUtils.getSharedPreferences(context)
        .edit()
        .putBoolean(context.getString(R.string.pref_collapse_list_item_actions_key), false)
        .commit();
    assertFalse(actions.accept(context, conversation));
    assertTrue(actions.getMenuItemsForNode(context, conversation, false).isEmpty());
  }

  @Test
  public void chatMessagesTabsAndOtherPackagesDoNotMatch() {
    list.setViewIdResourceName("com.zing.zalo:id/chatlinelist");
    assertFalse(actions.accept(context, conversation));
    list.setViewIdResourceName("com.zing.zalo:id/tab_recycler_view");
    assertFalse(actions.accept(context, conversation));
    list.setViewIdResourceName(ZaloCollapseScript.CONVERSATION_LIST_ID);
    CollapseTestTrees.applyPackage(root, "com.example.other");
    assertFalse(actions.accept(context, conversation));
  }

  @Test
  public void incompleteOrChangedConversationShapesDoNotMatch() {
    conversation.setLongClickable(false);
    assertFalse(actions.accept(context, conversation));
    conversation.setLongClickable(true);
    conversation.setText("");
    assertFalse(actions.accept(context, conversation));
    conversation.setText(ORIGIN);
    conversation.setVisibleToUser(false);
    assertFalse(actions.accept(context, conversation));
    conversation.setVisibleToUser(true);
    CollapseTestTrees.node(conversation, "android.widget.Button").setText("Nút mới");
    assertFalse(actions.accept(context, conversation));
  }

  @Test
  public void activationLongPressesTheConversationThenClicksOnlyThePopoverRow() {
    begin(5);
    List<AccessibilityNodeInfoCompat> rows = showMenu(ORIGIN, "Ẩn", "Xóa", "Chọn nhiều");
    AccessibilityNodeInfoCompat background =
        CollapseTestTrees.node(root, "android.widget.LinearLayout");
    background.setClickable(true);
    AccessibilityNodeInfoCompat label = CollapseTestTrees.node(background, "android.widget.TextView");
    label.setViewIdResourceName(ZaloCollapseScript.MENU_LABEL_ID);
    label.setText("Xóa");

    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertEquals(2, nodeActions().size());
    assertEquals(rows.get(1), nodeActions().get(1).nodeAction().target().getCompat());
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_CLICK, nodeActions().get(1).nodeAction().actionId());
    assertTrue(deliver(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
    assertEquals(2, nodeActions().size());

    popover.setVisibleToUser(false);
    assertFalse(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertFalse(MenuAutomation.isPending());
    assertNull(lastFocus());
    assertNull(lastSpeech());
  }

  @Test
  public void togglesClickTheLiveStateAndRestoreAfterTheConversationMoves() {
    String[][] states = {
      {"Đánh dấu chưa đọc", "Đánh dấu đã đọc"},
      {"Ghim", "Bỏ ghim"},
      {"Tắt thông báo", "Bật thông báo"},
      {"Chuyển sang mục Khác", "Chuyển sang mục Ưu tiên"}
    };
    for (int action = 0; action < states.length; action++) {
      for (String state : states[action]) {
        returner.parts.clear();
        createScreen();
        begin(action);
        List<AccessibilityNodeInfoCompat> rows = showMenu(ORIGIN, state);
        assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertEquals(rows.get(0), nodeActions().get(1).nodeAction().target().getCompat());
        popover.setVisibleToUser(false);
        CollapseTestTrees.bounds(conversation, 0, 0, 1080, 180);
        assertEquals(action != 2, deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertFalse(MenuAutomation.isPending());
        if (action == 2) {
          assertNull(lastFocus());
        } else {
          assertEquals(conversation, lastFocus().focus().target());
        }
      }
    }
  }

  @Test
  public void openingEventsWithTheBackgroundStillVisibleDoNotCancelTheSequence() {
    begin(1);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertTrue(deliver(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
    assertTrue(MenuAutomation.isPending());
    List<AccessibilityNodeInfoCompat> rows = showMenu(ORIGIN, "Ghim");
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertEquals(rows.get(0), nodeActions().get(1).nodeAction().target().getCompat());
  }

  @Test
  public void menuForAnotherConversationNeverReceivesAClick() {
    begin(5);
    showMenu("Người khác\nTin nhắn khác\n", "Xóa");
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertEquals(1, nodeActions().size());
    shadowOf(Looper.getMainLooper()).idleFor(3100, TimeUnit.MILLISECONDS);
    assertFalse(MenuAutomation.isPending());
    assertNotNull(lastSpeech());
    assertEquals(1, nodeActions().size());
  }

  @Test
  public void absentOrAmbiguousToggleClosesTheMenuAndReportsFailure() {
    for (String[] labels : new String[][] {{"Ẩn"}, {"Ghim", "Bỏ ghim"}}) {
      returner.parts.clear();
      createScreen();
      begin(1);
      showMenu(ORIGIN, labels);
      assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
      assertEquals(1, nodeActions().size());
      shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS);
      assertEquals(
          AccessibilityService.GLOBAL_ACTION_BACK,
          lastSystemAction().systemAction().systemActionId());
      popover.setVisibleToUser(false);
      assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
      assertFalse(MenuAutomation.isPending());
      assertNotNull(lastSpeech());
      assertEquals(conversation, lastFocus().focus().target());
    }
  }

  @Test
  public void refusedMenuClickIsReportedInsteadOfAdvancingToSuccess() {
    begin(5);
    showMenu(ORIGIN, "Xóa");
    returner.acceptNodeActions = false;
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS);
    assertNotNull(lastSystemAction());
    popover.setVisibleToUser(false);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertFalse(MenuAutomation.isPending());
    assertNotNull(lastSpeech());
  }

  @Test
  public void rejectedLongPressDoesNotLeaveAPendingSequence() {
    returner.acceptNodeActions = false;
    assertFalse(menuItems().get(0).onClickPerformed());
    assertFalse(MenuAutomation.isPending());
  }

  @Test
  public void missingMenuTimesOutAndAnotherAppCancelsWithoutStealingFocus() {
    begin(0);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    shadowOf(Looper.getMainLooper()).idleFor(3100, TimeUnit.MILLISECONDS);
    assertFalse(MenuAutomation.isPending());
    assertNotNull(lastSpeech());
    assertNull(lastSystemAction());

    returner.parts.clear();
    begin(0);
    AccessibilityEvent event =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    event.setPackageName("com.example.other");
    assertFalse(MenuAutomation.handleEvent(provider, event));
    assertFalse(MenuAutomation.isPending());
    assertNull(lastSpeech());
    assertNull(lastFocus());
  }

  @Test
  public void duplicatePlainOptionsAreNotClicked() {
    begin(5);
    showMenu(ORIGIN, "Xóa", "Xóa");
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertEquals(1, nodeActions().size());
  }

  @Test
  public void userClosingThePopoverRestoresSilently() {
    begin(0);
    showMenu(ORIGIN, "Ẩn");
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    popover.setVisibleToUser(false);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertFalse(MenuAutomation.isPending());
    assertNull(lastSpeech());
    assertNull(lastSystemAction());
    assertEquals(conversation, lastFocus().focus().target());
  }

  private void begin(int index) {
    assertTrue(menuItems().get(index).onClickPerformed());
    assertTrue(MenuAutomation.isPending());
    assertEquals(1, nodeActions().size());
    assertEquals(
        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, nodeActions().get(0).nodeAction().actionId());
    assertEquals(conversation, nodeActions().get(0).nodeAction().target().getCompat());
  }

  private List<ContextMenuItem> menuItems() {
    return actions.getMenuItemsForNode(context, conversation, false);
  }

  private void createScreen() {
    root = CollapseTestTrees.node(null, "android.widget.FrameLayout");
    root.setPackageName(ZaloCollapseScript.PACKAGE_NAME);
    list = CollapseTestTrees.node(root, "androidx.recyclerview.widget.RecyclerView");
    list.setViewIdResourceName(ZaloCollapseScript.CONVERSATION_LIST_ID);
    list.setScrollable(true);
    list.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(10, 1, false));
    conversation = CollapseTestTrees.node(list, "android.widget.FrameLayout");
    conversation.setText(ORIGIN);
    conversation.setClickable(true);
    conversation.setLongClickable(true);
    conversation.setFocusable(true);
    CollapseTestTrees.bounds(conversation, 0, 400, 1080, 580);
    provider.root = root;
  }

  private List<AccessibilityNodeInfoCompat> showMenu(String preview, String... labels) {
    AccessibilityNodeInfoCompat content = CollapseTestTrees.node(root, "android.widget.FrameLayout");
    content.setViewIdResourceName("com.zing.zalo:id/content_layout");
    CollapseTestTrees.node(content, "android.widget.FrameLayout").setText(preview);
    popover = CollapseTestTrees.node(content, "androidx.recyclerview.widget.RecyclerView");
    popover.setViewIdResourceName(ZaloCollapseScript.MENU_LIST_ID);
    List<AccessibilityNodeInfoCompat> rows = new ArrayList<>();
    for (String label : labels) {
      AccessibilityNodeInfoCompat row = CollapseTestTrees.node(popover, "android.widget.LinearLayout");
      row.setClickable(true);
      row.setFocusable(true);
      AccessibilityNodeInfoCompat title = CollapseTestTrees.node(row, "android.widget.TextView");
      title.setViewIdResourceName(ZaloCollapseScript.MENU_LABEL_ID);
      title.setText(label);
      rows.add(row);
    }
    return rows;
  }

  private boolean deliver(int type) {
    AccessibilityEvent event = AccessibilityEvent.obtain(type);
    event.setPackageName(ZaloCollapseScript.PACKAGE_NAME);
    return MenuAutomation.handleEvent(provider, event);
  }

  private List<Feedback.Part> nodeActions() {
    List<Feedback.Part> parts = new ArrayList<>();
    for (Feedback.Part part : returner.parts) {
      if (part.nodeAction() != null) {
        parts.add(part);
      }
    }
    return parts;
  }

  private Feedback.Part lastFocus() {
    Feedback.Part found = null;
    for (Feedback.Part part : returner.parts) {
      if (part.focus() != null) {
        found = part;
      }
    }
    return found;
  }

  private Feedback.Part lastSpeech() {
    Feedback.Part found = null;
    for (Feedback.Part part : returner.parts) {
      if (part.speech() != null) {
        found = part;
      }
    }
    return found;
  }

  private Feedback.Part lastSystemAction() {
    Feedback.Part found = null;
    for (Feedback.Part part : returner.parts) {
      if (part.systemAction() != null) {
        found = part;
      }
    }
    return found;
  }

  private static final class Provider implements MenuAutomation.RootProvider {
    AccessibilityNodeInfoCompat root;

    @Override
    public List<AccessibilityNodeInfoCompat> windowRoots() {
      return List.of(root);
    }

    @Override
    public AccessibilityNodeInfoCompat activeWindowRoot() {
      return root;
    }
  }

  private static final class RecordingReturner implements Pipeline.FeedbackReturner {
    final List<Feedback.Part> parts = new ArrayList<>();
    boolean acceptNodeActions = true;

    @Override
    public boolean returnFeedback(Feedback feedback) {
      parts.addAll(feedback.failovers());
      for (Feedback.Part part : feedback.failovers()) {
        if (part.nodeAction() != null && !acceptNodeActions) {
          return false;
        }
      }
      return true;
    }
  }
}
