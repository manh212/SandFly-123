/*
 * Copyright (C) 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.interpreters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Interpretation;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor;
import com.google.android.accessibility.talkback.interpreters.ManualScrollInterpreter.ManualScrollInterpretation;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.input.WindowEventInterpreter.EventInterpretation;
import com.google.android.accessibility.utils.traversal.TraversalStrategy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The manual-scroll settle delay in {@link AccessibilityFocusInterpreter}: a scroll is copied and
 * held for 100 ms so a still-moving scroll does not fire repeatedly. A scroll still settling when
 * the service suspends must never fire, and a held scroll that is replaced is discarded (the
 * production code recycles its event copy at both points; the event pool's state is not
 * observable under Robolectric, so the tests pin the discard itself).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AccessibilityFocusInterpreterTest {

  /** Records every interpretation handed to the pipeline. */
  private static final class RecordingReceiver implements Pipeline.InterpretationReceiver {
    final List<Interpretation> interpretations = new ArrayList<>();
    final List<AccessibilityEvent> events = new ArrayList<>();

    @Override
    public boolean input(
        EventId eventId,
        AccessibilityEvent event,
        Interpretation interpretation,
        AccessibilityNodeInfoCompat eventSourceNode) {
      interpretations.add(interpretation);
      events.add(event);
      return true;
    }
  }

  private AccessibilityFocusInterpreter interpreter;
  private RecordingReceiver receiver;

  @Before
  public void setUp() {
    Context context = RuntimeEnvironment.getApplication();
    AccessibilityFocusMonitor focusMonitor =
        new AccessibilityFocusMonitor(
            /* service= */ null, /* focusFinder= */ null, /* history= */ null) {
          @Override
          public AccessibilityNodeInfoCompat getAccessibilityFocus(boolean useInputFocusIfEmpty) {
            return null;
          }
        };
    ScreenStateMonitor screenStateMonitor =
        new ScreenStateMonitor(/* service= */ null, /* inputMethodMonitor= */ null);
    // No window change in the interpretation leaves the main windows marked stable.
    screenStateMonitor.handle(new EventInterpretation(), /* eventId= */ null);
    interpreter =
        new AccessibilityFocusInterpreter(
            context, focusMonitor, screenStateMonitor.state, new TalkBackAnalytics() {});
    receiver = new RecordingReceiver();
    interpreter.setPipeline(receiver);
  }

  @Test
  public void manualScroll_firesOnceTheSettleDelayPasses() {
    interpreter.onManualScroll(scrollInterpretation("com.example.app"));

    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS);

    assertEquals(1, receiver.interpretations.size());
    assertTrue(receiver.interpretations.get(0) instanceof Interpretation.ManualScroll);
    assertEquals("com.example.app", receiver.events.get(0).getPackageName());
  }

  @Test
  public void manualScroll_suspendedBeforeTheSettleDelay_neverFires() {
    interpreter.onManualScroll(scrollInterpretation("com.example.app"));

    interpreter.onSuspendInfrastructure();
    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS);

    assertTrue(receiver.interpretations.isEmpty());
  }

  @Test
  public void manualScroll_replacedBeforeFiring_onlyTheNewestScrollIsDelivered() {
    interpreter.onManualScroll(scrollInterpretation("com.example.first"));
    interpreter.onManualScroll(scrollInterpretation("com.example.second"));

    shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS);

    assertEquals(1, receiver.interpretations.size());
    assertEquals("com.example.second", receiver.events.get(0).getPackageName());
  }

  @Test
  public void manualScroll_suspended_thePendingScrollIsCleared() {
    interpreter.onManualScroll(scrollInterpretation("com.example.app"));

    interpreter.onSuspendInfrastructure();

    assertNull(pendingField());
  }

  private Object pendingField() {
    try {
      Field field = AccessibilityFocusInterpreter.class.getDeclaredField("pendingManualScroll");
      field.setAccessible(true);
      return field.get(interpreter);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static ManualScrollInterpretation scrollInterpretation(String packageName) {
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
    event.setPackageName(packageName);
    return ManualScrollInterpretation.create(
        /* eventId= */ null, event, TraversalStrategy.SEARCH_FOCUS_FORWARD);
  }
}
