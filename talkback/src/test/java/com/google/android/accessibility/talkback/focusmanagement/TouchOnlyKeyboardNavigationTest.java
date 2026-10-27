/*
 * Copyright 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.monitor.InputMethodMonitor;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FocusFinder;
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TouchOnlyKeyboardNavigationTest {
  public static class TestService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  private AccessibilityNodeInfoCompat focused;
  private AccessibilityNodeInfoCompat inputFocused;
  private FocusProcessorForLogicalNavigation processor;
  private TestService service;

  @Before
  public void setUp() {
    service = Robolectric.setupService(TestService.class);
    FocusFinder finder = new FocusFinder(service);
    AccessibilityFocusMonitor monitor =
        new AccessibilityFocusMonitor(service, finder, null) {
          @Override
          public AccessibilityNodeInfoCompat getAccessibilityFocus(
              boolean useInputFocusIfEmpty,
              boolean requireEditable,
              Filter<AccessibilityNodeInfoCompat> isCurrent) {
            return focused;
          }

          @Override
          public AccessibilityNodeInfoCompat getInputFocus() {
            return inputFocused;
          }
        };
    processor =
        new FocusProcessorForLogicalNavigation(
            service,
            finder,
            monitor,
            new ScreenStateMonitor(service, new InputMethodMonitor(service)).state,
            null,
            null,
            null);
  }

  private static AccessibilityWindowInfo window(int type, int id) {
    AccessibilityWindowInfo window = new AccessibilityWindowInfo();
    shadowOf(window).setType(type);
    shadowOf(window).setId(id);
    return window;
  }

  private static AccessibilityNodeInfoCompat item(AccessibilityWindowInfo window, String text) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setText(text);
    node.setClassName("android.widget.Button");
    node.setVisibleToUser(true);
    node.setFocusable(true);
    node.setClickable(true);
    node.setBoundsInScreen(new Rect(0, 0, 100, 100));
    shadowOf(node).setAccessibilityWindowInfo(window);
    shadowOf(node).setRefreshReturnValue(true);
    return AccessibilityNodeInfoCompat.wrap(node);
  }

  private static NavigationAction swipe(int direction) {
    return new NavigationAction.Builder()
        .setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
        .setTarget(NavigationTarget.TARGET_DEFAULT)
        .setDirection(direction)
        .build();
  }

  @Test
  public void swipesAfterTouchingAKey_continueBeforeAndAfterTheAppsInputField() {
    AccessibilityWindowInfo app = window(AccessibilityWindowInfo.TYPE_APPLICATION, 1);
    AccessibilityNodeInfoCompat root = item(app, "");
    AccessibilityNodeInfoCompat before = item(app, "Previous item");
    inputFocused = item(app, "Message");
    inputFocused.setClassName("android.widget.EditText");
    inputFocused.setEditable(true);
    AccessibilityNodeInfoCompat after = item(app, "Send");
    shadowOf(root.unwrap()).addChild(before.unwrap());
    shadowOf(root.unwrap()).addChild(inputFocused.unwrap());
    shadowOf(root.unwrap()).addChild(after.unwrap());
    focused = item(window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, 2), "a");
    OrderedTraversalStrategy traversal = new OrderedTraversalStrategy(root);
    Filter<AccessibilityNodeInfoCompat> filter =
        processor.composeNodeFilter(
            NavigationTarget.TARGET_DEFAULT, traversal.getSpeakingNodesCache());

    for (int direction :
        new int[] {TraversalStrategy.SEARCH_FOCUS_FORWARD, TraversalStrategy.SEARCH_FOCUS_BACKWARD}) {
      AccessibilityNodeInfoCompat pivot = processor.getPivotNodeForNavigationAction(swipe(direction));
      assertEquals(inputFocused, pivot);
      assertEquals(
          direction == TraversalStrategy.SEARCH_FOCUS_FORWARD ? after : before,
          TraversalStrategyUtils.searchFocus(traversal, pivot, direction, filter));
    }
  }

  @Test
  public void keyboardKeysAndSuggestions_areSkippedBySwipes_butRemainTouchFocusable() {
    AccessibilityWindowInfo keyboard = window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, 2);
    Filter<AccessibilityNodeInfoCompat> filter =
        processor.composeNodeFilter(NavigationTarget.TARGET_DEFAULT, null);

    for (String text : new String[] {"a", "Suggested word", "Enter"}) {
      AccessibilityNodeInfoCompat key = item(keyboard, text);
      assertTrue(AccessibilityNodeInfoUtils.shouldFocusNode(key));
      assertFalse(filter.accept(key));
    }
    assertTrue(filter.accept(item(window(AccessibilityWindowInfo.TYPE_APPLICATION, 1), "Send")));
  }

  @Test
  public void swipesFromAnAppItem_keepTheirOriginalPivot() {
    focused = item(window(AccessibilityWindowInfo.TYPE_APPLICATION, 1), "Send");
    inputFocused = item(window(AccessibilityWindowInfo.TYPE_APPLICATION, 1), "Message");

    assertEquals(
        focused,
        processor.getPivotNodeForNavigationAction(swipe(TraversalStrategy.SEARCH_FOCUS_FORWARD)));
  }

  @Test
  public void explicitWindowNavigation_keepsTheKeyboardPivot() {
    focused = item(window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, 2), "a");
    inputFocused = item(window(AccessibilityWindowInfo.TYPE_APPLICATION, 1), "Message");
    NavigationAction action =
        new NavigationAction.Builder()
            .setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
            .setTarget(NavigationTarget.TARGET_WINDOW)
            .setDirection(TraversalStrategy.SEARCH_FOCUS_FORWARD)
            .build();

    assertEquals(focused, processor.getPivotNodeForNavigationAction(action));
  }

  @Test
  public void crossingWindows_skipsTheWholeKeyboardWindow() {
    Filter<AccessibilityWindowInfo> filter =
        new FocusProcessorForLogicalNavigation.DirectionalNavigationWindowFilter(service, null);

    assertFalse(filter.accept(window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, 2)));
  }
}
