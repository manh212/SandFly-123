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

package com.google.android.accessibility.talkback.eventprocessor;

import static org.junit.Assert.assertEquals;

import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.utils.Performance;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The queue keeps at most two events of each limited type, dropping the oldest. Window content
 * changed events are limited like hover, focus and scroll events: apps send them in bursts, and
 * each one that waits in the queue is processed before the focus events queued behind it. Dropping
 * the older ones is safe for the reading-order cache because {@code TalkBackService} clears that
 * cache synchronously when the event arrives, before the event is queued at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EventQueueTest {

  private final EventQueue queue = new EventQueue();

  private static String tagOf(AccessibilityEvent event) {
    return event.getPackageName().toString();
  }

  private static AccessibilityEvent event(int type, String tag) {
    AccessibilityEvent event = AccessibilityEvent.obtain(type);
    event.setPackageName(tag);
    return event;
  }

  private List<AccessibilityEvent> drain() {
    List<AccessibilityEvent> events = new ArrayList<>();
    AccessibilityEvent event;
    while ((event = queue.dequeue()) != null) {
      events.add(event);
    }
    return events;
  }

  private static List<String> tagsOfType(List<AccessibilityEvent> events, int type) {
    List<String> tags = new ArrayList<>();
    for (AccessibilityEvent event : events) {
      if (event.getEventType() == type) {
        tags.add(event.getPackageName().toString());
      }
    }
    return tags;
  }

  @Test
  public void windowContentChangedEvents_prunedToTheNewestTwo() {
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc1"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc2"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc3"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc4"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc5"));

    List<AccessibilityEvent> events = drain();

    assertEquals(2, events.size());
    assertEquals(
        List.of("wcc4", "wcc5"),
        tagsOfType(events, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
  }

  @Test
  public void windowContentChangedEvents_prunedIndependentlyOfOtherTypes() {
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc1"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "state1"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc2"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "state2"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc3"));
    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "state3"));

    List<AccessibilityEvent> events = drain();

    // Window state changed is not a limited type: all three survive, in order.
    assertEquals(
        List.of("state1", "state2", "state3"),
        tagsOfType(events, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    assertEquals(
        List.of("wcc2", "wcc3"),
        tagsOfType(events, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
  }

  @Test
  public void previouslyLimitedTypes_stillPrunedToTheNewestTwo() {
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus1"));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus2"));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus3"));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "scroll1"));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "scroll2"));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "scroll3"));

    List<AccessibilityEvent> events = drain();

    assertEquals(4, events.size());
    assertEquals(
        List.of("focus2", "focus3"),
        tagsOfType(events, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
    assertEquals(
        List.of("scroll2", "scroll3"), tagsOfType(events, AccessibilityEvent.TYPE_VIEW_SCROLLED));
  }

  @Test
  public void focusEvent_jumpsAheadOfTheEventsInFrontOfIt() {
    Performance performance = Performance.getInstance();
    performance.setComputeStatsEnabled(true);
    long jumpsBefore = performance.getFocusEventQueueJumpCount();

    queue.enqueue(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "wcc1"));
    queue.enqueue(event(AccessibilityEvent.TYPE_ANNOUNCEMENT, "ann1"));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus1"));

    assertEquals("focus1", tagOf(queue.dequeue()));
    assertEquals("wcc1", tagOf(queue.dequeue()));
    assertEquals("ann1", tagOf(queue.dequeue()));
    assertEquals(jumpsBefore + 1, performance.getFocusEventQueueJumpCount());
  }

  @Test
  public void focusJumps_stopAtTheCap_soTheHeadIsServedBeforeAnyMoreJump() {
    queue.enqueue(event(AccessibilityEvent.TYPE_ANNOUNCEMENT, "head"));

    // Focus events keep arriving behind the same head: three jump, then the head is served,
    // then jumping resumes — nothing at the head starves, however long the burst runs.
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus1"));
    assertEquals("focus1", tagOf(queue.dequeue()));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus2"));
    assertEquals("focus2", tagOf(queue.dequeue()));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus3"));
    assertEquals("focus3", tagOf(queue.dequeue()));
    queue.enqueue(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "focus4"));
    assertEquals("head", tagOf(queue.dequeue()));
    assertEquals("focus4", tagOf(queue.dequeue()));
  }

}
