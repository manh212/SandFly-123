package com.google.android.accessibility.talkback.collapse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.collapse.CollapsedItem.ChildAction;
import com.google.android.accessibility.talkback.compositor.CompositorUtils;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
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
public class FacebookFeedCollapseScriptTest {
  private static final String AUTHOR = "Trang thử nghiệm";
  private static final String TIME = "Có dùng AI•4 ngày•Chia sẻ với: Công khai";
  private static final String BODY = "Nội dung bài viết mẫu… xem thêm";
  private static final List<String> TRAY_LABELS =
      List.of(
          "Thích",
          "Bày tỏ cảm xúc",
          "Bình luận",
          "Chia sẻ",
          "Xem thêm lựa chọn cho bài viết này",
          "Xem phần mô tả chi tiết về hình ảnh");

  private Context context;
  private CollapsedItemActions actions;
  private RecordingReturner returner;
  private Provider provider;
  private AccessibilityNodeInfoCompat list;
  private AccessibilityNodeInfoCompat post;
  private AccessibilityNodeInfoCompat author;
  private AccessibilityNodeInfoCompat body;
  private AccessibilityNodeInfoCompat image;
  private AccessibilityNodeInfoCompat options;

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
    createFeed();
    CollapsedItemActions.setFocusNodeProviderForTest(ignored -> post);
  }

  @After
  public void tearDown() {
    CollapsedItemActions.resetFocusNodeProviderForTest();
    MenuAutomation.resetForTest();
  }

  @Test
  public void recordedPostIsOneStopWithAuthorMetadataAndBodyAndSixTrayActions() {
    CollapsedItem match = CollapseEngine.matchRepresentative(context, post);
    assertNotNull(match);
    String separator = CompositorUtils.getSeparator();
    assertEquals(AUTHOR + separator + TIME + separator + BODY, match.composedLabel());
    assertEquals(body, match.activationTarget());
    assertEquals(body, match.selectionTarget());
    List<String> labels = new ArrayList<>();
    for (ChildAction action : match.actions()) {
      if (action.kind == ChildAction.Kind.TRAY_ITEM) {
        labels.add(action.label.toString());
        assertFalse(action.longClick);
        assertEquals(FacebookFeedCollapseScript.TRAY_SIGNATURE, action.traySignatureText);
      }
    }
    assertEquals(TRAY_LABELS, labels);
    assertTrue(titles().contains("Ảnh"));
    assertTrue(titles().contains("Ẩn bài viết"));
    assertTrue(titles().contains(AUTHOR));
    assertEquals(post, CollapseEngine.touchTarget(context, image));
    assertTrue(actions.getMenuItemsForNode(context, body, false).isEmpty());
  }

  @Test
  public void disabledSettingLeavesFeedUntouched() {
    SharedPreferencesUtils.getSharedPreferences(context)
        .edit()
        .putBoolean(context.getString(R.string.pref_collapse_list_item_actions_key), false)
        .commit();
    assertFalse(actions.accept(context, post));
    assertEquals(image, CollapseEngine.touchTarget(context, image));
  }

  @Test
  public void nativeSingleStopPostsAndUnrecognisedListsRemainUntouched() {
    options.setContentDescription("Lựa chọn khác");
    assertFalse(actions.accept(context, post));
    options.setContentDescription("Lựa chọn khác cho bài viết của " + AUTHOR);
    list.setViewIdResourceName("com.facebook.katana:id/stories");
    assertFalse(actions.accept(context, post));
    list.setViewIdResourceName("android:id/list");
    post.setContentDescription("Bài viết đã có nhãn riêng");
    assertFalse(actions.accept(context, post));
  }

  @Test
  public void incompleteFeedPostDoesNotFallBackToCommentRules() {
    author.setContentDescription("Không khớp tác giả");
    assertFalse(actions.accept(context, post));
    author.setContentDescription(AUTHOR);
    body.setLongClickable(false);
    assertFalse(actions.accept(context, post));
  }

  @Test
  public void eachTrayActionLongPressesLiveBodyAndClicksItsRealButton() {
    for (String title : TRAY_LABELS) {
      returner.parts.clear();
      createFeed();
      assertTrue(find(title).onClickPerformed());
      assertTrue(MenuAutomation.isPending());
      assertEquals(
          AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
          nodeActions().get(0).nodeAction().actionId());
      assertEquals(body, nodeActions().get(0).nodeAction().target().getCompat());

      List<AccessibilityNodeInfoCompat> buttons = showTray(TRAY_LABELS);
      assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
      assertEquals(2, nodeActions().size());
      assertEquals(
          buttons.get(TRAY_LABELS.indexOf(title)),
          nodeActions().get(1).nodeAction().target().getCompat());
      assertTrue(deliver(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
      assertEquals(2, nodeActions().size());

      provider.roots = List.of(list);
      provider.active = list;
      boolean restores = title.equals("Thích");
      assertEquals(restores, deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
      assertFalse(MenuAutomation.isPending());
      if (restores) {
        assertEquals(post, lastFocus().focus().target());
      } else {
        assertNull(lastFocus());
      }
    }
  }

  @Test
  public void likeActionAlsoFindsUnlikeState() {
    assertTrue(find("Thích").onClickPerformed());
    List<String> labels = new ArrayList<>(TRAY_LABELS);
    labels.set(0, "Bỏ thích");
    List<AccessibilityNodeInfoCompat> buttons = showTray(labels);
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertEquals(buttons.get(0), nodeActions().get(1).nodeAction().target().getCompat());
  }

  @Test
  public void wrongTrayDoesNotReceiveAnyClickAndTimesOutWithFailure() {
    assertTrue(find("Thích").onClickPerformed());
    showTray(List.of("Thích", FacebookFeedCollapseScript.TRAY_SIGNATURE));
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertEquals(1, nodeActions().size());
    shadowOf(Looper.getMainLooper()).idleFor(3100, TimeUnit.MILLISECONDS);
    assertFalse(MenuAutomation.isPending());
    assertNotNull(lastSpeech());
    assertEquals(1, nodeActions().size());
  }

  @Test
  public void unavailableImageDescriptionFailsWithoutChoosingAnotherOption() {
    assertTrue(find(TRAY_LABELS.get(5)).onClickPerformed());
    showTray(TRAY_LABELS.subList(0, 5));
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    assertEquals(1, nodeActions().size());
    shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS);
    assertTrue(returner.parts.stream().anyMatch(part -> part.systemAction() != null));
    provider.roots = List.of(list);
    provider.active = list;
    assertTrue(deliver(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertFalse(MenuAutomation.isPending());
    assertNotNull(lastSpeech());
    assertEquals(post, lastFocus().focus().target());
  }

  @Test
  public void changedFocusedPostNeverReceivesTheStoredAction() {
    ContextMenuItem stored = find("Thích");
    createFeed();
    body.setText("Một bài viết khác");
    body.setContentDescription("Một bài viết khác");
    assertFalse(stored.onClickPerformed());
    assertFalse(MenuAutomation.isPending());
    assertTrue(nodeActions().isEmpty());
  }

  private void createFeed() {
    list = CollapseTestTrees.list(FacebookCollapseScript.PACKAGE_NAME);
    list.setViewIdResourceName("android:id/list");
    list.setScrollable(true);
    post = CollapseTestTrees.item(list);
    post.setFocusable(true);
    CollapseTestTrees.bounds(post, 0, 0, 1080, 1200);
    AccessibilityNodeInfoCompat content = post;
    for (int i = 0; i < 3; i++) {
      content = CollapseTestTrees.node(content, "android.view.ViewGroup");
    }
    AccessibilityNodeInfoCompat header = CollapseTestTrees.node(content, "android.view.ViewGroup");
    header.setClickable(true);
    header.setFocusable(true);
    AccessibilityNodeInfoCompat avatar = CollapseTestTrees.node(header, "android.widget.ImageView");
    avatar.setClickable(true);
    avatar.setFocusable(true);
    avatar.setLongClickable(true);
    avatar.setContentDescription("Mở tin của " + AUTHOR + ", tin chưa xem");
    author = CollapseTestTrees.node(header, "android.view.ViewGroup");
    author.setFocusable(true);
    author.setText(AUTHOR);
    author.setContentDescription(AUTHOR);
    button(author, AUTHOR);
    AccessibilityNodeInfoCompat time = CollapseTestTrees.node(header, "android.view.ViewGroup");
    time.setFocusable(true);
    time.setText(TIME);
    time.setContentDescription(TIME);
    button(time, "Có dùng AI");
    options = button(header, "Lựa chọn khác cho bài viết của " + AUTHOR);
    button(header, "Ẩn bài viết");
    AccessibilityNodeInfoCompat bodyWrap = CollapseTestTrees.node(content, "android.view.ViewGroup");
    body = CollapseTestTrees.node(bodyWrap, "android.view.ViewGroup");
    body.setText(BODY);
    body.setContentDescription(BODY);
    body.setClickable(true);
    body.setLongClickable(true);
    body.setFocusable(true);
    body.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK);
    CollapseTestTrees.bounds(body, 0, 250, 1080, 600);
    button(body, "xem thêm");
    image = CollapseTestTrees.node(content, "android.view.ViewGroup");
    image.setContentDescription("Ảnh");
    image.setClickable(true);
    image.setFocusable(true);
    image.setLongClickable(true);
    AccessibilityNodeInfoCompat toolbar = CollapseTestTrees.node(content, "android.view.ViewGroup");
    toolbar.setClickable(true);
    button(toolbar, "Nút Thích. Hãy nhấn đúp và giữ để bày tỏ cảm xúc về bình luận.");
    button(toolbar, "Bình luận");
    button(toolbar, "Nút Chia sẻ. Nhấn đúp để chia sẻ bài viết.");
    provider.roots = List.of(list);
    provider.active = list;
  }

  private AccessibilityNodeInfoCompat button(AccessibilityNodeInfoCompat parent, String label) {
    AccessibilityNodeInfoCompat button = CollapseTestTrees.node(parent, "android.widget.Button");
    button.setClickable(true);
    button.setFocusable(true);
    button.setContentDescription(label);
    button.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK);
    return button;
  }

  private List<AccessibilityNodeInfoCompat> showTray(List<String> labels) {
    AccessibilityNodeInfoCompat tray = CollapseTestTrees.node(null, "android.widget.FrameLayout");
    tray.setPackageName(FacebookCollapseScript.PACKAGE_NAME);
    button(tray, "Đóng");
    AccessibilityNodeInfoCompat scroll = CollapseTestTrees.node(tray, "android.widget.ScrollView");
    List<AccessibilityNodeInfoCompat> buttons = new ArrayList<>();
    for (String label : labels) {
      AccessibilityNodeInfoCompat row = button(scroll, label);
      AccessibilityNodeInfoCompat duplicate = CollapseTestTrees.node(row, "android.view.ViewGroup");
      duplicate.setText(label);
      duplicate.setContentDescription(label);
      buttons.add(row);
    }
    provider.roots = List.of(tray, list);
    provider.active = tray;
    return buttons;
  }

  private ContextMenuItem find(String title) {
    for (ContextMenuItem item : actions.getMenuItemsForNode(context, post, false)) {
      if (title.contentEquals(item.getTitle())) {
        return item;
      }
    }
    throw new AssertionError("Missing action: " + title);
  }

  private List<String> titles() {
    List<String> titles = new ArrayList<>();
    for (ContextMenuItem item : actions.getMenuItemsForNode(context, post, false)) {
      titles.add(item.getTitle().toString());
    }
    return titles;
  }

  private boolean deliver(int type) {
    AccessibilityEvent event = AccessibilityEvent.obtain(type);
    event.setPackageName(FacebookCollapseScript.PACKAGE_NAME);
    return MenuAutomation.handleEvent(provider, event);
  }

  private List<Feedback.Part> nodeActions() {
    return returner.parts.stream().filter(part -> part.nodeAction() != null).toList();
  }

  private Feedback.Part lastFocus() {
    return returner.parts.stream()
        .filter(part -> part.focus() != null)
        .reduce((a, b) -> b)
        .orElse(null);
  }

  private Feedback.Part lastSpeech() {
    return returner.parts.stream()
        .filter(part -> part.speech() != null)
        .reduce((a, b) -> b)
        .orElse(null);
  }

  private static final class RecordingReturner implements Pipeline.FeedbackReturner {
    final List<Feedback.Part> parts = new ArrayList<>();

    @Override
    public boolean returnFeedback(Feedback feedback) {
      parts.addAll(feedback.failovers());
      return true;
    }
  }

  private static final class Provider implements MenuAutomation.RootProvider {
    List<AccessibilityNodeInfoCompat> roots;
    AccessibilityNodeInfoCompat active;

    @Override
    public List<AccessibilityNodeInfoCompat> windowRoots() {
      return roots;
    }

    @Override
    public AccessibilityNodeInfoCompat activeWindowRoot() {
      return active;
    }
  }
}
