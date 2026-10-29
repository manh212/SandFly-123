/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.util.SparseIntArray;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.Performance;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.Locale;

/**
 * This class is a queue that tracks events that TalkBack will process. This queue coalesces
 * replaceable events so stale updates do not hold up newer focus feedback.
 */
class EventQueue {

  /**
   * The maximum number of coalescible events of each type that may remain in the queue.
   */
  private static volatile int maximumQualifyingEvents = 2;

  /**
   * Events that represent discrete user-facing feedback or input state must not be discarded by
   * queue coalescing. Repeated updates of all other event types can be replaced by newer updates.
   */
  private static final int MASK_PRESERVED_EVENT_TYPES =
      AccessibilityEvent.TYPE_ANNOUNCEMENT
          | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
          | AccessibilityEvent.TYPE_WINDOWS_CHANGED
          | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
          | AccessibilityEvent.TYPE_VIEW_CLICKED
          | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
          | AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
          | AccessibilityEvent.TYPE_VIEW_FOCUSED
          | AccessibilityEvent.TYPE_VIEW_SELECTED
          | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
          | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
          | AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
          | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
          | AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
          | AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
          | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
          | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END
          | AccessibilityEvent.TYPE_GESTURE_DETECTION_START
          | AccessibilityEvent.TYPE_GESTURE_DETECTION_END;

  /** Repeatable event types subject to the selected per-type queue limit. */
  private static final int MASK_COALESCIBLE_EVENT_TYPES =
      AccessibilityEvent.TYPES_ALL_MASK & ~MASK_PRESERVED_EVENT_TYPES;

  /** The list responsible for maintaining events in the event queue. */
  private final ArrayList<AccessibilityEvent> eventQueue = new ArrayList<>();
  private final IdentityHashMap<AccessibilityEvent, Long> enqueueTimes = new IdentityHashMap<>();

  /**
   * The current number of coalescible events in the queue for each event type.
   */
  private final SparseIntArray qualifyingEvents = new SparseIntArray();

  private static volatile int maxConsecutiveFocusJumps = 3;
  private static volatile long processingSliceMs = 16;
  private static volatile String processingLevel = "medium";

  static volatile boolean focusPriorityEnabled = true;
  private static volatile boolean focusPriorityEnabledByProcessingLevel;

  private int consecutiveFocusJumps;

  /**
   * Sets how much replaceable event detail is retained. Lower levels keep fewer updates per event
   * type and give queued focus events more opportunities to jump ahead. Higher levels retain more
   * event detail and yield less often. Discrete feedback, text/input, and touch-state events are
   * always kept.
   */
  static void setProcessingLevel(String level) {
    String normalized = level == null ? "medium" : level.trim().toLowerCase(Locale.ROOT);
    normalized = normalized.replace('-', '_').replace(' ', '_');
    switch (normalized) {
      case "very_low":
        processingLevel = "very_low";
        focusPriorityEnabledByProcessingLevel = true;
        maximumQualifyingEvents = 1;
        maxConsecutiveFocusJumps = 6;
        processingSliceMs = 8;
        break;
      case "low":
        processingLevel = "low";
        focusPriorityEnabledByProcessingLevel = true;
        maximumQualifyingEvents = 1;
        maxConsecutiveFocusJumps = 4;
        processingSliceMs = 12;
        break;
      case "high":
        processingLevel = "high";
        focusPriorityEnabledByProcessingLevel = false;
        maximumQualifyingEvents = 4;
        maxConsecutiveFocusJumps = 2;
        processingSliceMs = 24;
        break;
      case "very_high":
        processingLevel = "very_high";
        focusPriorityEnabledByProcessingLevel = false;
        maximumQualifyingEvents = 8;
        maxConsecutiveFocusJumps = 1;
        processingSliceMs = 32;
        break;
      case "medium":
      default:
        processingLevel = "medium";
        focusPriorityEnabledByProcessingLevel = false;
        maximumQualifyingEvents = 2;
        maxConsecutiveFocusJumps = 3;
        processingSliceMs = 16;
        break;
    }
  }

  static String processingLevel() {
    return processingLevel;
  }

  static long processingSliceMs() {
    return processingSliceMs;
  }

  static int maximumQualifyingEvents() {
    return maximumQualifyingEvents;
  }

  static int maxConsecutiveFocusJumps() {
    return maxConsecutiveFocusJumps;
  }

  /**
   * Adds an {@link AccessibilityEvent} to the queue for processing. If this addition causes the
   * queue to exceed the maximum allowable coalescible events for an event's type, earlier events
   * of this type will be pruned from the queue.
   *
   * @param event The event to add to the queue
   */
  public void enqueue(AccessibilityEvent event) {
    final AccessibilityEvent clone = AccessibilityEvent.obtain(event);
    final int eventType = clone.getEventType();

    if (AccessibilityEventUtils.eventMatchesAnyType(clone, MASK_COALESCIBLE_EVENT_TYPES)) {
      final int eventCountOfType = qualifyingEvents.get(eventType, 0);
      qualifyingEvents.put(eventType, (eventCountOfType + 1));
    }

    eventQueue.add(clone);
    if (Performance.getInstance().getComputeStatsEnabled()) {
      enqueueTimes.put(clone, SystemClock.uptimeMillis());
    }
    enforceEventLimits();
  }

  /**
   * Removes and returns an AccessibilityEvent from the front of the event queue.
   *
   * @return The event at the front of the queue.
   */
  public AccessibilityEvent dequeue() {
    if (eventQueue.isEmpty()) {
      return null;
    }

    int index = 0;
    if ((focusPriorityEnabled || focusPriorityEnabledByProcessingLevel)
        && consecutiveFocusJumps < maxConsecutiveFocusJumps) {
      index =
          jumpIndex(
              eventQueue.get(0).getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
              oldestFocusEventIndex());
    }
    final AccessibilityEvent event = eventQueue.remove(index);
    Long enqueuedAt = enqueueTimes.remove(event);
    if (enqueuedAt != null) {
      EventProcessingStats.dequeued(
          event, SystemClock.uptimeMillis() - enqueuedAt, eventQueue.size() + 1);
    }

    if (index > 0) {
      consecutiveFocusJumps++;
      Performance.getInstance().onFocusEventQueueJump(eventQueue.size() + 1);
    } else {
      consecutiveFocusJumps = 0;
    }

    if (event != null
        && AccessibilityEventUtils.eventMatchesAnyType(event, MASK_COALESCIBLE_EVENT_TYPES)) {
      final int eventType = event.getEventType();
      final int eventCountOfType = qualifyingEvents.get(eventType, 0);
      qualifyingEvents.put(eventType, (eventCountOfType - 1));
    }
    return event;
  }

  static int jumpIndex(boolean headIsFocusEvent, int oldestFocusIndex) {
    return !headIsFocusEvent && oldestFocusIndex > 0 ? oldestFocusIndex : 0;
  }

  private int oldestFocusEventIndex() {
    for (int i = 1; i < eventQueue.size(); i++) {
      if (eventQueue.get(i).getEventType()
          == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
        return i;
      }
    }
    return -1;
  }

  /** Clears the event queue and discards all events waiting for processing. */
  public void clear() {
    for (AccessibilityEvent event : eventQueue) {
      EventProcessingStats.dropped(event);
      event.recycle();
    }
    eventQueue.clear();
    enqueueTimes.clear();
    qualifyingEvents.clear();
    consecutiveFocusJumps = 0;
  }

  /**
   * Determines if the event queue is empty.
   *
   * @return {@code true} if the queue is empty, {@code false} otherwise
   */
  public boolean isEmpty() {
    return eventQueue.isEmpty();
  }

  /**
   * Enforces that the event queue has no more than {@code maximumQualifyingEvents} events of each
   * coalescible type. The excessive events are pruned by removing the oldest event first, retaining
   * the newest state for that type.
   */
  private void enforceEventLimits() {
    int eventTypesToPrune = 0;
    // Locate event types which exceed the allowable limit
    for (int i = 0; i < qualifyingEvents.size(); i++) {
      final int eventType = qualifyingEvents.keyAt(i);
      final int eventsOfType = qualifyingEvents.valueAt(i);
      if (eventsOfType > maximumQualifyingEvents) {
        eventTypesToPrune |= eventType;
      }
    }

    final Iterator<AccessibilityEvent> iterator = eventQueue.iterator();
    while (iterator.hasNext() && (eventTypesToPrune != 0)) {
      final AccessibilityEvent next = iterator.next();

      // Prune offending events
      if (AccessibilityEventUtils.eventMatchesAnyType(next, eventTypesToPrune)) {
        final int eventType = next.getEventType();
        int eventCountOfType = qualifyingEvents.get(eventType, 0);
        eventCountOfType--;
        qualifyingEvents.put(eventType, eventCountOfType);
        iterator.remove();
        enqueueTimes.remove(next);
        EventProcessingStats.coalesced(next);
        next.recycle();

        // Stop pruning further events of this type if the number of
        // events is below the limit
        if (eventCountOfType <= maximumQualifyingEvents) {
          eventTypesToPrune &= ~eventType;
        }
      }
    }
  }
}
