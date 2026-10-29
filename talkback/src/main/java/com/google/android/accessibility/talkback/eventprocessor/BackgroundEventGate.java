package com.google.android.accessibility.talkback.eventprocessor;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.function.Predicate;

public final class BackgroundEventGate {
  public interface Receiver {
    void accept(AccessibilityEvent event, long receivedAt);
  }
  private static final long INTERVAL_MS = 500;
  private static final long INPUT_GRACE_MS = 150;
  private static final int UPDATE_TYPES = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
      | AccessibilityEvent.TYPE_VIEW_SELECTED | AccessibilityEvent.TYPE_VIEW_SCROLLED
      | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Receiver receiver;
  private final Predicate<AccessibilityEvent> inputResponse;
  private final Runnable releasePending = this::releasePending;
  private boolean enabled;
  private boolean touching;
  private boolean releaseScheduled;
  private long lastInput;
  private long lastDispatch = SystemClock.uptimeMillis();
  private long pendingSince;
  private int interactionWindowId = -1;
  private String interactionPackage;
  private AccessibilityEvent pending;

  public BackgroundEventGate(Receiver receiver) {
    this(receiver, event -> false);
  }

  public BackgroundEventGate(Receiver receiver, Predicate<AccessibilityEvent> inputResponse) {
    this.receiver = receiver;
    this.inputResponse = inputResponse;
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
    EventProcessingStats.received(event);
    int type = event.getEventType();
    if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
      touching = true;
      onUserInput();
      handler.removeCallbacks(releasePending);
      releaseScheduled = false;
    } else if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
      touching = false;
      onUserInput();
      schedulePending();
    }
    if (type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        || type == AccessibilityEvent.TYPE_VIEW_FOCUSED
        || type == AccessibilityEvent.TYPE_VIEW_CLICKED
        || (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && event.getPackageName() != null
            && !"com.android.systemui".contentEquals(event.getPackageName()))) {
      interactionWindowId = event.getWindowId();
      interactionPackage = event.getPackageName() == null ? null : event.getPackageName().toString();
    }
    if (!enabled || !isBackgroundUpdate(event)) {
      EventProcessingStats.admitted(event, 0);
      return true;
    }
    if (pending != null) {
      EventProcessingStats.coalesced(pending);
      pending.recycle();
    }
    pending = AccessibilityEvent.obtain(event);
    pendingSince = receivedAt;
    EventProcessingStats.deferred(event);
    schedulePending();
    return false;
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
      if (interactionWindowId >= 0 && event.getWindowId() == interactionWindowId) {
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
    if (pending != null) {
      EventProcessingStats.dropped(pending);
      pending.recycle();
      pending = null;
    }
    touching = false;
  }
}
