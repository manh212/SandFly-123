/*
 * Copyright 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.actor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionRecord;
import com.google.android.accessibility.talkback.monitor.InputMethodMonitor;
import com.google.android.accessibility.utils.FocusFinder;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The focus-history record is posted off the swipe path instead of being built inside it, so
 * these tests pin the two halves of that bargain: the listener that starts speech is still told
 * synchronously, and by the time the main queue runs — ahead of the app's focus event, which
 * still has to come back through the event queue — the record is in the history, matching that
 * event exactly as the synchronous record did.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FocusManagerInternalTest {

  /** Minimal service for Robolectric to instantiate the collaborators against. */
  public static class TestService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  private AccessibilityFocusActionHistory history;
  private FocusManagerInternal focusManager;
  private final AtomicInteger listenerCalls = new AtomicInteger();

  @Before
  public void setUp() {
    TestService service = Robolectric.setupService(TestService.class);
    history = new AccessibilityFocusActionHistory(service);
    FocusFinder focusFinder = new FocusFinder(service);
    ScreenStateMonitor screenStateMonitor =
        new ScreenStateMonitor(service, new InputMethodMonitor(service));
    focusManager =
        new FocusManagerInternal(
            service,
            focusFinder,
            screenStateMonitor.state,
            history,
            new AccessibilityFocusMonitor(service, focusFinder, history.reader));
    focusManager.setPipeline(feedback -> true);
    listenerCalls.set(0);
    focusManager.setFocusSetListener(
        (node, info, eventId, actionTime) -> listenerCalls.incrementAndGet());
  }

  private static AccessibilityNodeInfoCompat node() {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setPackageName("com.example.chat");
    info.setWindowId(7);
    return AccessibilityNodeInfoCompat.wrap(info);
  }

  private static FocusActionInfo actionInfo() {
    return FocusActionInfo.builder()
        .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
        .build();
  }

  @Test
  public void focusAction_listenerIsToldAtOnce_recordFollowsOnTheQueue() {
    AccessibilityNodeInfoCompat node = node();
    long actionTime = SystemClock.uptimeMillis();

    boolean result = focusManager.performAccessibilityFocusActionInternal(node, actionInfo(), null);

    assertTrue(result);
    assertEquals(1, listenerCalls.get());
    assertNull(history.getLastFocusActionRecord());

    shadowOf(Looper.getMainLooper()).idle();

    FocusActionRecord record = history.getLastFocusActionRecord();
    assertNotNull(record);
    assertEquals(actionTime, record.getActionTime());
    assertEquals(7, record.getFocusedNode().getWindowId());
    assertEquals("com.example.chat", record.getFocusedNode().getPackageName());
  }

  @Test
  public void focusAction_recordMatchesTheAppsFocusEventOnceTheQueueRuns() {
    AccessibilityNodeInfoCompat node = node();

    focusManager.performAccessibilityFocusActionInternal(node, actionInfo(), null);

    AccessibilityEvent focusedEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    focusedEvent.setSource(node.unwrap());
    assertNull(history.matchFocusActionRecordFromEvent(focusedEvent));

    shadowOf(Looper.getMainLooper()).idle();

    assertNotNull(history.matchFocusActionRecordFromEvent(focusedEvent));
  }
}
