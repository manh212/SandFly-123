package com.google.android.accessibility.talkback.eventprocessor;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.function.Predicate;
import java.util.function.Consumer;
import com.google.android.accessibility.utils.LatencyExperiments;
import com.google.android.accessibility.utils.NodeOverrides;
import com.google.android.accessibility.utils.input.ScrollEventInterpreter;
import com.google.android.accessibility.utils.WindowCachePolicy;
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache;

public final class BackgroundEventGate {
  public interface Receiver {
    void accept(AccessibilityEvent event, long receivedAt);
  }
  private static final long INTERVAL_MS = 500;
  private static final long INPUT_GRACE_MS = 150;
  // Busy active windows need the same protection from update floods as background windows.
  private static final long ACTIVE_UPDATE_INTERVAL_MS = 200;
  private static final long RECENT_INPUT_MS = 500;
  private static final int STRICT_EVENTS =
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
          | AccessibilityEvent.TYPE_WINDOWS_CHANGED
          | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          | AccessibilityEvent.TYPE_VIEW_SCROLLED
          | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
          | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
          | AccessibilityEvent.TYPE_VIEW_FOCUSED
          | AccessibilityEvent.TYPE_VIEW_SELECTED
          | AccessibilityEvent.TYPE_VIEW_CLICKED
          | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
          | AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
          | AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
          | AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
          | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
          | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
          | AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
          | AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
          | AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
          | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
          | AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END
          | AccessibilityEvent.TYPE_GESTURE_DETECTION_START
          | AccessibilityEvent.TYPE_GESTURE_DETECTION_END
          | AccessibilityEvent.TYPE_ANNOUNCEMENT
          | AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE
          | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
  private static final int WINDOW_TRANSITIONS = AccessibilityEvent.WINDOWS_CHANGE_ADDED
      | AccessibilityEvent.WINDOWS_CHANGE_REMOVED
      | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
      | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
      | AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED;
  private static final int UPDATE_TYPES = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
      | AccessibilityEvent.TYPE_VIEW_SELECTED | AccessibilityEvent.TYPE_VIEW_SCROLLED
      | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Receiver receiver;
  private final Predicate<AccessibilityEvent> inputResponse;
  private final Consumer<AccessibilityEvent> deferredInvalidation;
  private final Runnable releasePending = this::releasePending;
  private final Runnable releaseActive = this::releaseActive;
  private boolean strict;
  private boolean interactionPriorityEnabled;
  private boolean touching;
  private boolean releaseScheduled;
  private long lastInput;
  private long lastDispatch = SystemClock.uptimeMillis();
  private long pendingSince;
  private int interactionWindowId = -1;
  private String interactionPackage;
  private AccessibilityEvent pending;
  private AccessibilityEvent pendingActive;
  private long pendingActiveSince;
  private boolean activeScheduled;
  private long lastActiveDispatch;

  public BackgroundEventGate(Receiver receiver) {
    this(receiver, event -> false);
  }

  public BackgroundEventGate(Receiver receiver, Predicate<AccessibilityEvent> inputResponse) {
    this(receiver, inputResponse, event -> {});
  }

  public BackgroundEventGate(Receiver receiver, Predicate<AccessibilityEvent> inputResponse,
      Consumer<AccessibilityEvent> deferredInvalidation) {
    this.receiver = receiver;
    this.inputResponse = inputResponse;
    this.deferredInvalidation = deferredInvalidation;
  }

  public void setProcessingLevel(String level) {
    boolean nextStrict = "very_low".equals(level);
    if (strict != nextStrict) {
      boolean wasTouching = touching;
      clear();
      touching = wasTouching;
      strict = nextStrict;
      lastDispatch = SystemClock.uptimeMillis();
    }
  }

  public boolean isStrict() {
    return strict;
  }

  public int subscribedEventTypes() {
    return strict ? STRICT_EVENTS : AccessibilityEvent.TYPES_ALL_MASK;
  }

  public void setInteractionPriorityEnabled(boolean enabled) {
    interactionPriorityEnabled = enabled;
  }

  public void onUserInput() {
    lastInput = SystemClock.uptimeMillis();
  }

  public boolean onAccessibilityEvent(AccessibilityEvent event) {
    return onAccessibilityEvent(event, SystemClock.uptimeMillis());
  }

  public boolean onAccessibilityEvent(AccessibilityEvent event, long receivedAt) {
    // Must observe the raw event before deciding to drop/defer it. An active feed can
    // otherwise suppress its own churn detector and keep invalidating the reading order.
    TraversalTreeCache.observeIncomingEvent(event);
    EventProcessingStats.received(event);
    int type = event.getEventType();
    if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
      touching = true;
      onUserInput();
      handler.removeCallbacks(releasePending);
      releaseScheduled = false;
      handler.removeCallbacks(releaseActive);
      activeScheduled = false;
    } else if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
      touching = false;
      onUserInput();
      schedulePending();
      scheduleActive();
    }
    if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        || type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        || type == AccessibilityEvent.TYPE_VIEW_CLICKED
        || (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && event.getPackageName() != null
            && !"com.android.systemui".contentEquals(event.getPackageName())
            && (!LatencyExperiments.Feature.SCOPED_CACHE.enabled()
                || interactionWindowId < 0 || interactionWindowId == event.getWindowId()))) {
      interactionWindowId = event.getWindowId();
      interactionPackage = event.getPackageName() == null ? null : event.getPackageName().toString();
    }
    if (strict) {
      return handleStrictEvent(event);
    }
    boolean prioritizeInteraction = interactionPriorityEnabled
        && !NodeOverrides.hasSource()
        && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        && event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        && (pendingActive != null || touching
            || SystemClock.uptimeMillis() - lastInput < INPUT_GRACE_MS);
    if (prioritizeInteraction
        && interactionWindowId >= 0 && event.getWindowId() == interactionWindowId
        && TraversalTreeCache.shouldUseLiveTraversal(interactionWindowId)
        && !ScrollEventInterpreter.hasScrollPosition(event)) {
      // Invalidate the cached root and traversal immediately, not when the deferred
      // event is replayed: a gesture between updates must never navigate a stale tree.
      deferredInvalidation.accept(event);
      if (pendingActive != null) {
        EventProcessingStats.coalesced(pendingActive);
        pendingActive.recycle();
      }
      pendingActive = AccessibilityEvent.obtain(event);
      pendingActiveSince = receivedAt;
      EventProcessingStats.deferred(event);
      scheduleActive();
      return false;
    }
    if (pendingActive != null
        && (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            || type == AccessibilityEvent.TYPE_VIEW_CLICKED
            || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
      // A newer interaction/window transition supersedes any queued geometry update.
      dropActivePending();
    }
    if (!LatencyExperiments.Feature.INPUT_PRIORITY.enabled() || !isBackgroundUpdate(event)) {
      EventProcessingStats.admitted(event, 0);
      return true;
    }
    if (pending != null) {
      EventProcessingStats.coalesced(pending);
      pending.recycle();
    }
    deferredInvalidation.accept(event);
    pending = AccessibilityEvent.obtain(event);
    pendingSince = receivedAt;
    EventProcessingStats.deferred(event);
    schedulePending();
    return false;
  }

  private boolean handleStrictEvent(AccessibilityEvent event) {
    int type = event.getEventType();
    if ((type & STRICT_EVENTS) == 0) {
      EventProcessingStats.dropped(event);
      return false;
    }
    switch (type) {
      case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
        if (!isWindowTransition(event)) {
          return cacheOnly(event);
        }
        break;
      case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
        if ((event.getContentChangeTypes() & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) == 0
            || !isRecentInteraction(event) || !ScrollEventInterpreter.hasScrollPosition(event)) {
          return cacheOnly(event);
        }
        break;
      case AccessibilityEvent.TYPE_VIEW_SCROLLED:
        if (!isRecentInteraction(event) && !inputResponse.test(event)) {
          if (interactionWindowId == event.getWindowId()) {
            TraversalTreeCache.notePassiveScroll(event.getWindowId());
          }
          return cacheOnly(event);
        }
        break;
      case AccessibilityEvent.TYPE_VIEW_SELECTED:
      case AccessibilityEvent.TYPE_ANNOUNCEMENT:
        if (!isRecentInteraction(event)) {
          EventProcessingStats.dropped(event);
          return false;
        }
        break;
      default:
        break;
    }
    EventProcessingStats.admitted(event, 0);
    return true;
  }

  private boolean cacheOnly(AccessibilityEvent event) {
    deferredInvalidation.accept(event);
    EventProcessingStats.cacheOnly(event);
    return false;
  }

  private boolean isRecentInteraction(AccessibilityEvent event) {
    return (touching || (lastInput > 0 && SystemClock.uptimeMillis() - lastInput < RECENT_INPUT_MS))
        && (interactionWindowId < 0 || event.getWindowId() < 0
            || event.getWindowId() == interactionWindowId);
  }

  private static boolean isWindowTransition(AccessibilityEvent event) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return true;
    }
    int changes = event.getWindowChanges();
    return changes == 0
        || (changes & (WINDOW_TRANSITIONS | AccessibilityEvent.WINDOWS_CHANGE_PIP)) != 0;
  }

  private void dropActivePending() {
    handler.removeCallbacks(releaseActive);
    activeScheduled = false;
    if (pendingActive != null) {
      EventProcessingStats.dropped(pendingActive);
      pendingActive.recycle();
      pendingActive = null;
    }
  }

  private void scheduleActive() {
    if (pendingActive == null || touching || activeScheduled) return;
    activeScheduled = true;
    handler.postDelayed(
        releaseActive,
        Math.max(0, Math.max(
            lastActiveDispatch + ACTIVE_UPDATE_INTERVAL_MS - SystemClock.uptimeMillis(),
            lastInput + INPUT_GRACE_MS - SystemClock.uptimeMillis())));
  }

  private void releaseActive() {
    activeScheduled = false;
    if (pendingActive == null || touching) return;
    long now = SystemClock.uptimeMillis();
    long delay = Math.max(lastActiveDispatch + ACTIVE_UPDATE_INTERVAL_MS - now,
        lastInput + INPUT_GRACE_MS - now);
    if (delay > 0) {
      activeScheduled = true;
      handler.postDelayed(releaseActive, delay);
      return;
    }
    AccessibilityEvent event = pendingActive;
    pendingActive = null;
    lastActiveDispatch = now;
    try {
      if (event.getWindowId() != interactionWindowId) {
        EventProcessingStats.dropped(event);
        return;
      }
      EventProcessingStats.admitted(event, now - pendingActiveSince);
      receiver.accept(event, pendingActiveSince);
    } finally {
      event.recycle();
    }
  }

  private void schedulePending() {
    if (pending != null && !touching && !releaseScheduled) {
      releaseScheduled = true;
      handler.postDelayed(releasePending, nextDelay());
    }
  }

  private boolean isBackgroundUpdate(AccessibilityEvent event) {
    int type = event.getEventType();
    if ((type & UPDATE_TYPES) == 0) {
      return false;
    }
    if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
            || (event.getWindowChanges() & WINDOW_TRANSITIONS) != 0)) {
      return false;
    }
    if (WindowCachePolicy.isNonStructuralContent(event)
        && (touching || SystemClock.uptimeMillis() - lastInput < INPUT_GRACE_MS)) return true;
    CharSequence sourcePackage = event.getPackageName();
    boolean background;
    if (sourcePackage != null && "com.android.systemui".contentEquals(sourcePackage)
        && !"com.android.systemui".equals(interactionPackage)) {
      background = true;
    } else if (interactionWindowId >= 0 && event.getWindowId() >= 0) {
      background = event.getWindowId() != interactionWindowId;
    } else {
      background = interactionPackage != null && sourcePackage != null
          && !interactionPackage.contentEquals(sourcePackage);
    }
    return background
        && !(type == AccessibilityEvent.TYPE_VIEW_SCROLLED && inputResponse.test(event));
  }

  private long nextDelay() {
    long now = SystemClock.uptimeMillis();
    return Math.max(touching ? INPUT_GRACE_MS : 0,
        Math.max(lastDispatch + INTERVAL_MS - now, lastInput + INPUT_GRACE_MS - now));
  }

  private void releasePending() {
    releaseScheduled = false;
    if (pending == null || touching) {
      return;
    }
    long delay = nextDelay();
    if (delay > 0) {
      releaseScheduled = true;
      handler.postDelayed(releasePending, delay);
      return;
    }
    AccessibilityEvent event = pending;
    pending = null;
    lastDispatch = SystemClock.uptimeMillis();
    try {
      if (interactionWindowId >= 0 && event.getWindowId() == interactionWindowId
          && !LatencyExperiments.Feature.INPUT_PRIORITY.enabled()) {
        EventProcessingStats.dropped(event);
        return;
      }
      EventProcessingStats.admitted(event, lastDispatch - pendingSince);
      receiver.accept(event, pendingSince);
    } finally {
      event.recycle();
    }
  }

  public void clear() {
    handler.removeCallbacks(releasePending);
    releaseScheduled = false;
    dropActivePending();
    TraversalTreeCache.resetChurnTracking();
    if (pending != null) {
      EventProcessingStats.dropped(pending);
      pending.recycle();
      pending = null;
    }
    touching = false;
  }
}
