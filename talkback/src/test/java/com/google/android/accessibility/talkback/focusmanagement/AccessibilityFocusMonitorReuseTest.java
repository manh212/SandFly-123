/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory;
import com.google.android.accessibility.utils.Filter;
import com.google.android.accessibility.utils.FocusFinder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the focus-fetch reuse in {@link AccessibilityFocusMonitor}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AccessibilityFocusMonitorReuseTest {

  /** A service that does nothing; the monitor under test never reaches the real one. */
  public static class TestService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  private static class CountingFocusFinder extends FocusFinder {
    int findFocusCalls = 0;
    AccessibilityNodeInfoCompat focus;

    CountingFocusFinder(AccessibilityService service) {
      super(service);
    }

    @Override
    public AccessibilityNodeInfoCompat findFocusCompat(int focusType) {
      findFocusCalls++;
      return focus;
    }
  }

  private CountingFocusFinder focusFinder;
  private AccessibilityFocusMonitor monitor;

  @Before
  public void setUp() {
    TestService service = Robolectric.setupService(TestService.class);
    AccessibilityFocusActionHistory history = new AccessibilityFocusActionHistory(service);
    focusFinder = new CountingFocusFinder(service);
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setVisibleToUser(true);
    focusFinder.focus = AccessibilityNodeInfoCompat.wrap(info);
    monitor = new AccessibilityFocusMonitor(service, focusFinder, history.reader);
  }

  @Test
  public void withoutReuse_everyFetchAsksAgain() {
    monitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ true);
    monitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ true);

    assertEquals(2, focusFinder.findFocusCalls);
  }

  @Test
  public void withinReuse_theSameNodeIsAnsweredOnce() {
    monitor.beginFocusFetchReuse();
    try {
      AccessibilityNodeInfoCompat first = monitor.getAccessibilityFocus(true);
      AccessibilityNodeInfoCompat second = monitor.getAccessibilityFocus(true);

      assertSame(first, second);
      assertEquals(1, focusFinder.findFocusCalls);
    } finally {
      monitor.endFocusFetchReuse();
    }

    monitor.getAccessibilityFocus(true);
    assertEquals(2, focusFinder.findFocusCalls);
  }

  @Test
  public void withinReuse_differentArgumentsAskAgain() {
    monitor.beginFocusFetchReuse();
    try {
      monitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ true, /* requireEditable= */ true);
      monitor.getAccessibilityFocus(/* useInputFocusIfEmpty= */ true, /* requireEditable= */ false);
    } finally {
      monitor.endFocusFetchReuse();
    }

    assertEquals(2, focusFinder.findFocusCalls);
  }

  @Test
  public void withinReuse_aCurrencyFilterBypassesTheReuse() {
    monitor.beginFocusFetchReuse();
    try {
      monitor.getAccessibilityFocus(true);
      // The filtered fetch is not the plain one the reuse answers: it goes its own way (and here
      // finds nothing), so the plain counter must not move.
      monitor.getAccessibilityFocus(
          /* useInputFocusIfEmpty= */ false,
          /* requireEditable= */ false,
          /* isCurrent= */ Filter.node(node -> true));
    } finally {
      monitor.endFocusFetchReuse();
    }

    assertEquals(1, focusFinder.findFocusCalls);
  }
}
