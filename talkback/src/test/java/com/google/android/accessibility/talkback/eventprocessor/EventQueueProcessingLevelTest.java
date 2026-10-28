/*
 * Copyright 2026 Backtalk contributors
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
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.google.android.accessibility.talkback.eventprocessor;

import static org.junit.Assert.assertEquals;

import android.view.accessibility.AccessibilityEvent;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests the selectable event-queue policy used for swipe-latency experiments. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EventQueueProcessingLevelTest {

  @After
  public void resetPolicy() {
    ProcessorEventQueue.setProcessingLevel("medium");
    ProcessorEventQueue.setFocusPriorityEnabled(true);
  }

  @Test
  public void veryLow_prunesAggressivelyAndYieldsOften() {
    ProcessorEventQueue.setProcessingLevel("very_low");

    assertEquals("very_low", ProcessorEventQueue.getProcessingLevel());
    assertEquals(8L, EventQueue.processingSliceMs());
    assertEquals(1, EventQueue.maximumQualifyingEvents());
    assertEquals(6, EventQueue.maxConsecutiveFocusJumps());
  }

  @Test
  public void veryLow_prioritizesFocusEvenWhenPinnedOptimizationIsOff() {
    ProcessorEventQueue.setFocusPriorityEnabled(false);
    ProcessorEventQueue.setProcessingLevel("very_low");
    EventQueue queue = new EventQueue();
    AccessibilityEvent contentChange = AccessibilityEvent.obtain();
    contentChange.setEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    AccessibilityEvent focus = AccessibilityEvent.obtain();
    focus.setEventType(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    queue.enqueue(contentChange);
    queue.enqueue(focus);
    contentChange.recycle();
    focus.recycle();

    AccessibilityEvent next = queue.dequeue();

    assertEquals(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, next.getEventType());
    next.recycle();
  }

  @Test
  public void medium_keepsTheOriginalQueuePolicy() {
    ProcessorEventQueue.setProcessingLevel("medium");

    assertEquals("medium", ProcessorEventQueue.getProcessingLevel());
    assertEquals(16L, EventQueue.processingSliceMs());
    assertEquals(2, EventQueue.maximumQualifyingEvents());
    assertEquals(3, EventQueue.maxConsecutiveFocusJumps());
  }

  @Test
  public void low_prunesRepeatedEventsAndKeepsFocusPriority() {
    ProcessorEventQueue.setProcessingLevel("low");

    assertEquals("low", ProcessorEventQueue.getProcessingLevel());
    assertEquals(12L, EventQueue.processingSliceMs());
    assertEquals(1, EventQueue.maximumQualifyingEvents());
    assertEquals(4, EventQueue.maxConsecutiveFocusJumps());
  }

  @Test
  public void high_retainsMoreEventsThanMedium() {
    ProcessorEventQueue.setProcessingLevel("high");

    assertEquals("high", ProcessorEventQueue.getProcessingLevel());
    assertEquals(24L, EventQueue.processingSliceMs());
    assertEquals(4, EventQueue.maximumQualifyingEvents());
    assertEquals(2, EventQueue.maxConsecutiveFocusJumps());
  }

  @Test
  public void veryHigh_retainsMoreEventsAndYieldsLessOften() {
    ProcessorEventQueue.setProcessingLevel("very_high");

    assertEquals("very_high", ProcessorEventQueue.getProcessingLevel());
    assertEquals(32L, EventQueue.processingSliceMs());
    assertEquals(8, EventQueue.maximumQualifyingEvents());
    assertEquals(1, EventQueue.maxConsecutiveFocusJumps());
  }

  @Test
  public void invalidLevel_fallsBackToMedium() {
    ProcessorEventQueue.setProcessingLevel("unknown");

    assertEquals("medium", ProcessorEventQueue.getProcessingLevel());
    assertEquals(16L, EventQueue.processingSliceMs());
  }
}
