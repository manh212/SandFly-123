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

package com.google.android.accessibility.talkback.menurules;

import static org.junit.Assert.assertEquals;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.Menu;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Synthesized collapsed-item actions (item ids in the reserved collapse range, base 0x5d000000)
 * must never reach a context menu surface; they belong to the Actions reading control only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NodeMenuRuleProcessorCollapseTest {
  private static final int NORMAL_ITEM_ID = 12345;
  private static final int COLLAPSED_ITEM_ID = 0x5d000005;

  /** Minimal service for the processor to use as a context. */
  public static class TestService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  private static final class StubRule extends NodeMenuRule {
    private final List<ContextMenuItem> items;

    StubRule(List<ContextMenuItem> items) {
      super(
          R.string.pref_show_context_menu_custom_action_setting_key,
          R.bool.pref_show_context_menu_custom_action_default);
      this.items = items;
    }

    @Override
    public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
      return true;
    }

    @Override
    public List<ContextMenuItem> getMenuItemsForNode(
        Context context, AccessibilityNodeInfoCompat node, boolean includeAncestors) {
      return new ArrayList<>(items);
    }

    @Override
    CharSequence getUserFriendlyMenuName(Context context) {
      return "Actions";
    }
  }

  private static final class StubCreator extends NodeMenuRuleCreator {
    private final NodeMenuRule rule;

    StubCreator(NodeMenuRule rule) {
      super(
          new Pipeline.FeedbackReturner() {
            @Override
            public boolean returnFeedback(Feedback feedback) {
              return true;
            }
          },
          /* actorState= */ null,
          /* accessibilityFocusMonitor= */ null,
          /* analytics= */ null);
      this.rule = rule;
    }

    @Override
    @Nullable NodeMenuRule getMenuRuleById(int ruleId) {
      return rule;
    }
  }

  private Context context;
  private AccessibilityService service;
  private AccessibilityNodeInfoCompat node;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    service = Robolectric.buildService(TestService.class).create().get();
    SharedPreferencesUtils.getSharedPreferences(service)
        .edit()
        .putBoolean(
            service.getString(R.string.pref_show_context_menu_custom_action_setting_key), true)
        .commit();
    node =
        AccessibilityNodeInfoCompat.wrap(
            android.view.accessibility.AccessibilityNodeInfo.obtain());
  }

  private NodeMenuRuleProcessor processorWithItems(ContextMenuItem... items) {
    return new NodeMenuRuleProcessor(service, new StubCreator(new StubRule(List.of(items))));
  }

  private ContextMenuItem item(int itemId, String title) {
    return ContextMenu.createMenuItem(context, /* groupId= */ 1, itemId, Menu.NONE, title);
  }

  @Test
  public void prepareRuleMenuForNode_dropsCollapsedItems() {
    NodeMenuRuleProcessor processor =
        processorWithItems(item(COLLAPSED_ITEM_ID, "Reply"), item(NORMAL_ITEM_ID, "Normal"));
    ContextMenu menu = new ContextMenu(context);

    processor.prepareRuleMenuForNode(menu, node, R.id.custom_action_menu);

    assertEquals(1, menu.size());
    assertEquals("Normal", menu.getItem(0).getTitle().toString());
  }

  @Test
  public void prepareTalkbackMenuForNode_dropsCollapsedItems() {
    NodeMenuRuleProcessor processor =
        processorWithItems(item(COLLAPSED_ITEM_ID, "Reply"), item(NORMAL_ITEM_ID, "Normal"));
    ContextMenu menu = new ContextMenu(context);

    processor.prepareTalkbackMenuForNode(
        menu, node, R.id.custom_action_menu, R.string.title_custom_action, /* itemOrder= */ 0);

    assertEquals(1, menu.size());
    android.view.SubMenu subMenu = menu.getItem(0).getSubMenu();
    assertEquals(1, subMenu.size());
    assertEquals("Normal", subMenu.getItem(0).getTitle().toString());
  }

  @Test
  public void prepareTalkbackMenuForNode_onlyCollapsedItems_addsNothing() {
    NodeMenuRuleProcessor processor = processorWithItems(item(COLLAPSED_ITEM_ID, "Reply"));
    ContextMenu menu = new ContextMenu(context);

    processor.prepareTalkbackMenuForNode(
        menu, node, R.id.custom_action_menu, R.string.title_custom_action, /* itemOrder= */ 0);

    assertEquals(0, menu.size());
  }
}
