package com.google.android.accessibility.talkback.eventprocessor;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.function.Predicate;
import java.util.function.Consumer;
import com.google.android.accessibility.utils.LatencyExperiments;
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
  private static final long RECENT_SCROLL_INPUT_MS = 500;
  private static final int UPDATE_TYPES = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
      | AccessibilityEvent.TYPE_VIEW_SELECTED | AccessibilityEvent.TYPE_VIEW_SCROLLED
      | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Receiver receiver;
  private final Predicate<AccessibilityEvent> inputResponse;
  private final Consumer<AccessibilityEvent> deferredInvalidation;
  private final Runnable releasePending = this::releasePending;
  private final Runnable releaseActive = this::releaseActive;
  private boolean enabled;
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
    boolean nextEnabled = "very_low".equals(level);
    if (enabled != nextEnabled) {
      boolean wasTouching = touching;
      clear();
      touching = wasTouching;
      enabled = nextEnabled;
      lastDispatch = SystemClock.uptimeMillis();
    }
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
    // Structural changes and unsolicited scrolls from an active news feed may arrive
    // many times a second. Once detected, keep just the latest update and deliver it
    // between user interactions. Focus, clicks, text edits and expected scroll callbacks
    // always take the original immediate path.
    if (enabled && isChurningActiveUpdate(event)) {
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
    if ((!enabled && !LatencyExperiments.Feature.INPUT_PRIORITY.enabled())
        || !isBackgroundUpdate(event)) {
      EventProcessingStats.admitted(event, 0);
      return true;
    }
    if (pending != null) {
      EventProcessingStats.coalesced(pending);
      pending.recycle();
    }
    if (LatencyExperiments.Feature.INPUT_PRIORITY.enabled()) deferredInvalidation.accept(event);
    pending = AccessibilityEvent.obtain(event);
    pendingSince = receivedAt;
    EventProcessingStats.deferred(event);
    schedulePending();
    return false;
  }

  private boolean isChurningActiveUpdate(AccessibilityEvent event) {
    int type = event.getEventType();
    if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return false;
    if (interactionWindowId < 0 || event.getWindowId() != interactionWindowId
        || !TraversalTreeCache.shouldUseLiveTraversal(interactionWindowId)) return false;
    if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      // Text/state/content-description changes may be meaningful feedback.
      return !WindowCachePolicy.isNonStructuralContent(event);
    }
    // Never postpone a scroll response while the user is touching/swiping, nor
    // any delayed callback for a programmatic scroll that TalkBack is expecting.
    long now = SystemClock.uptimeMillis();
    return !touching && now - lastInput >= RECENT_SCROLL_INPUT_MS
        && !inputResponse.test(event);
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
    if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return false;
      }
      int transition = AccessibilityEvent.WINDOWS_CHANGE_ADDED
          | AccessibilityEvent.WINDOWS_CHANGE_REMOVED | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
          | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
          | AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED;
      if ((event.getWindowChanges() & transition) != 0) {
        return false;
      }
    }
    if (LatencyExperiments.Feature.INPUT_PRIORITY.enabled()
        && WindowCachePolicy.isNonStructuralContent(event)
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
    handler.removeCallbacks(releaseActive);
    activeScheduled = false;
    if (pendingActive != null) {
      EventProcessingStats.dropped(pendingActive);
      pendingActive.recycle();
      pendingActive = null;
    }
    if (pending != null) {
      EventProcessingStats.dropped(pending);
      pending.recycle();
      pending = null;
    }
    touching = false;
  }
}
