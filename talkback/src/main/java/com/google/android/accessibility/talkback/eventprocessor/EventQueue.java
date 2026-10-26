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
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityEventCompat;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.Performance;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * This class is a queue that tracks events that TalkBack will process. This queue self-prunes
 * events that exceed a maximum threshold for certain event types.
 */
class EventQueue {

  /**
   * The maximum number of events for each type included in {@code MASK_LIMITED_EVENT_TYPES} that
   * may remain in the queue.
   */
  private static final int MAXIMUM_QUALIFYING_EVENTS = 2;

  /**
   * The types of events that should be pruned if there are more than {@code
   * MAXIMUM_QUALIFYING_EVENTS} of these events in the queue.
   */
  private static final int MASK_LIMITED_EVENT_TYPES =
      AccessibilityEventCompat.TYPE_VIEW_HOVER_ENTER
          | AccessibilityEventCompat.TYPE_VIEW_ACCESSIBILITY_FOCUSED
          | AccessibilityEventCompat.TYPE_VIEW_SCROLLED
          | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;

  /**
   * How many focus events in a row may be let through ahead of the queue's head before the
   * head is served anyway. A burst of ordinary events — a chat window churning, say — must
   * not starve behind focus events, and focus events must not wait behind the whole burst:
   * this is the bargain between the two.
   */
  static final int MAX_CONSECUTIVE_FOCUS_JUMPS = 3;

  /** Focus events let through in a row since the head of the queue was last served. */
  private int consecutiveFocusJumps;

  /** The list responsible for maintaining events in the event queue. */
  private final ArrayList<AccessibilityEvent> eventQueue = new ArrayList<>();

  /**
   * The current number of events in the queue for each event type that match a type defined in
   * {@code MASK_LIMITED_EVENT_TYPES}.
   */
  private final SparseIntArray qualifyingEvents = new SparseIntArray();

  /**
   * Adds an {@link AccessibilityEvent} to the queue for processing. If this addition causes the
   * queue to exceed the maximum allowable events for an event's type, earlier events of this type
   * will be pruned from the queue.
   *
   * @param event The event to add to the queue
   */
  public void enqueue(AccessibilityEvent event) {
    final AccessibilityEvent clone = AccessibilityEvent.obtain(event);
    final int eventType = clone.getEventType();

    if (AccessibilityEventUtils.eventMatchesAnyType(clone, MASK_LIMITED_EVENT_TYPES)) {
      final int eventCountOfType = qualifyingEvents.get(eventType, 0);
      qualifyingEvents.put(eventType, (eventCountOfType + 1));
    }

    eventQueue.add(clone);
    enforceEventLimits();
  }

  /**
   * Removes and returns an AccessibilityEvent from the queue. The head is served as it always
   * was, except that a waiting accessibility-focus event may be served ahead of it: the focus
   * a swipe just set is the event the user is listening for, and every ordinary event in front
   * of it is time added to the swipe. At most {@link #MAX_CONSECUTIVE_FOCUS_JUMPS} focus
   * events jump in a row; then the head is served once, so nothing else starves.
   *
   * @return The event to process next.
   */
  public AccessibilityEvent dequeue() {
    if (eventQueue.isEmpty()) {
      return null;
    }

    int index = 0;
    if (consecutiveFocusJumps < MAX_CONSECUTIVE_FOCUS_JUMPS) {
      index =
          jumpIndex(
              eventQueue.get(0).getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
              oldestFocusEventIndex());
    }
    final AccessibilityEvent event = eventQueue.remove(index);

    if (index > 0) {
      consecutiveFocusJumps++;
      Performance.getInstance().onFocusEventQueueJump(eventQueue.size() + 1);
    } else {
      consecutiveFocusJumps = 0;
    }

    if (event != null
        && AccessibilityEventUtils.eventMatchesAnyType(event, MASK_LIMITED_EVENT_TYPES)) {
      final int eventType = event.getEventType();
      final int eventCountOfType = qualifyingEvents.get(eventType, 0);
      qualifyingEvents.put(eventType, (eventCountOfType - 1));
    }
    return event;
  }

  /**
   * The index a dequeue serves: the head's, unless the head is not itself a focus event and a
   * focus event waits behind it at {@code oldestFocusIndex}, in which case that event's.
   */
  static int jumpIndex(boolean headIsFocusEvent, int oldestFocusIndex) {
    return !headIsFocusEvent && oldestFocusIndex > 0 ? oldestFocusIndex : 0;
  }

  /** The index of the oldest waiting accessibility-focus event, or -1 when none waits. */
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
    eventQueue.clear();
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
   * Enforces that the event queue has no more than {@code MAXIMUM_QUALIFYING_EVENTS} events of each
   * type defined by {@code MASK_LIMITED_EVENT_TYPES}. The excessive events are pruned by removing
   * the oldest event first.
   */
  private void enforceEventLimits() {
    int eventTypesToPrune = 0;
    // Locate event types which exceed the allowable limit
    for (int i = 0; i < qualifyingEvents.size(); i++) {
      final int eventType = qualifyingEvents.keyAt(i);
      final int eventsOfType = qualifyingEvents.valueAt(i);
      if (eventsOfType > MAXIMUM_QUALIFYING_EVENTS) {
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

        // Stop pruning further events of this type if the number of
        // events is below the limit
        if (eventCountOfType <= MAXIMUM_QUALIFYING_EVENTS) {
          eventTypesToPrune &= ~eventType;
        }
      }
    }
  }
}
