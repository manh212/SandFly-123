/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils.gestures;

import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT;
import static android.util.Log.ERROR;
import static android.util.Log.VERBOSE;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.checkerframework.checker.nullness.qual.Nullable;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public abstract class GestureMatcher {

  public static final int STATE_CLEAR = 0;

  public static final int STATE_GESTURE_STARTED = 1;

  public static final int STATE_GESTURE_COMPLETED = 2;

  public static final int STATE_GESTURE_CANCELED = 3;

  public static final int STATE_GESTURE_PROCESSING = 4;

  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    STATE_CLEAR,
    STATE_GESTURE_STARTED,
    STATE_GESTURE_COMPLETED,
    STATE_GESTURE_CANCELED,
    STATE_GESTURE_PROCESSING
  })
  @interface State {}

  @State private int state = STATE_CLEAR;

  @IntDef({
    GESTURE_2_FINGER_SINGLE_TAP,
    GESTURE_2_FINGER_DOUBLE_TAP,
    GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD,
    GESTURE_2_FINGER_TRIPLE_TAP,
    GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD,
    GESTURE_3_FINGER_SINGLE_TAP,
    GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD,
    GESTURE_3_FINGER_DOUBLE_TAP,
    GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD,
    GESTURE_3_FINGER_TRIPLE_TAP,
    GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD,
    GESTURE_DOUBLE_TAP,
    GESTURE_DOUBLE_TAP_AND_HOLD,
    GESTURE_SWIPE_UP,
    GESTURE_SWIPE_UP_AND_LEFT,
    GESTURE_SWIPE_UP_AND_DOWN,
    GESTURE_SWIPE_UP_AND_RIGHT,
    GESTURE_SWIPE_DOWN,
    GESTURE_SWIPE_DOWN_AND_LEFT,
    GESTURE_SWIPE_DOWN_AND_UP,
    GESTURE_SWIPE_DOWN_AND_RIGHT,
    GESTURE_SWIPE_LEFT,
    GESTURE_SWIPE_LEFT_AND_UP,
    GESTURE_SWIPE_LEFT_AND_RIGHT,
    GESTURE_SWIPE_LEFT_AND_DOWN,
    GESTURE_SWIPE_RIGHT,
    GESTURE_SWIPE_RIGHT_AND_UP,
    GESTURE_SWIPE_RIGHT_AND_LEFT,
    GESTURE_SWIPE_RIGHT_AND_DOWN,
    GESTURE_2_FINGER_SWIPE_DOWN,
    GESTURE_2_FINGER_SWIPE_LEFT,
    GESTURE_2_FINGER_SWIPE_RIGHT,
    GESTURE_2_FINGER_SWIPE_UP,
    GESTURE_3_FINGER_SWIPE_DOWN,
    GESTURE_3_FINGER_SWIPE_LEFT,
    GESTURE_3_FINGER_SWIPE_RIGHT,
    GESTURE_3_FINGER_SWIPE_UP,
    GESTURE_4_FINGER_DOUBLE_TAP,
    GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD,
    GESTURE_4_FINGER_SINGLE_TAP,
    GESTURE_4_FINGER_SWIPE_DOWN,
    GESTURE_4_FINGER_SWIPE_LEFT,
    GESTURE_4_FINGER_SWIPE_RIGHT,
    GESTURE_4_FINGER_SWIPE_UP,
    GESTURE_4_FINGER_TRIPLE_TAP
  })
  @Retention(RetentionPolicy.SOURCE)
  @interface GestureId {}

  @GestureId private final int gestureId;

  private final Handler handler;

  private StateChangeListener listener = null;
  private final AnalyticsEventLogger logger;

  protected final DelayedTransition delayedTransition;

  protected boolean logMotionEvent = false;

  protected GestureMatcher(
      int gestureId, Handler handler, StateChangeListener listener, AnalyticsEventLogger logger) {
    this.gestureId = gestureId;
    this.handler = handler;
    delayedTransition = new DelayedTransition();
    this.listener = listener;
    this.logger = logger;
  }

  public void onConfigurationChanged(Context context) {}

  public void enableLogMotionEvent() {
    logMotionEvent = true;
  }

  public void clear() {
    state = STATE_CLEAR;
    cancelPendingTransitions();
  }

  public void restart(boolean pending) {
    state = STATE_CLEAR;
    cancelPendingTransitions();
  }

  public final int getState() {
    return state;
  }

  public void debugMotionEvent(String tag, String format, @Nullable Object... args) {
    if (logMotionEvent) {
      LogUtils.v(tag, format, args);
    }
  }

  private void setState(@State int state, MotionEvent event) {
    setState(state, event, true);
  }

  private void setState(@State int state, MotionEvent event, boolean notify) {
    if (state != STATE_GESTURE_PROCESSING) {
      this.state = state;
      cancelPendingTransitions();
    }
    if (notify && listener != null) {
      listener.onStateChanged(gestureId, state, event);
    }
  }

  protected final void startGesture(MotionEvent event) {
    setState(STATE_GESTURE_STARTED, event);
  }

  public final void cancelGesture(MotionEvent event, boolean notify) {
    setState(STATE_GESTURE_CANCELED, event, notify);
  }

  public final void cancelGesture(MotionEvent event) {
    setState(STATE_GESTURE_CANCELED, event);
  }

  protected final void completeGesture(EventId eventId, MotionEvent event) {
    Performance.getInstance().onGestureLastMotionEventTime(eventId, event.getEventTime());
    setState(STATE_GESTURE_COMPLETED, event);
  }

  protected final void processGesture(EventId eventId, MotionEvent event) {
    setState(STATE_GESTURE_PROCESSING, event);
  }

  protected final void analyticsEvent(GestureAnalyticsEvent analyticsEvent) {
    logger.logAnalyticsEvent(analyticsEvent);
  }

  public final void setListener(@NonNull StateChangeListener listener) {
    this.listener = listener;
  }

  public int getGestureId() {
    return gestureId;
  }

  public boolean bypassCancelByTapUpToTouchExplore() {
    return false;
  }

  @CanIgnoreReturnValue
  public final int onMotionEvent(EventId eventId, MotionEvent event) {
    if (state == STATE_GESTURE_CANCELED || state == STATE_GESTURE_COMPLETED) {
      return state;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> onDown(eventId, event);
      case MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(eventId, event);
      case MotionEvent.ACTION_MOVE -> onMove(eventId, event);
      case MotionEvent.ACTION_POINTER_UP -> onPointerUp(eventId, event);
      case MotionEvent.ACTION_UP -> onUp(eventId, event);
      default ->

          setState(STATE_GESTURE_CANCELED, event);
    }
    return state;
  }

  protected void onDown(EventId eventId, MotionEvent event) {}

  protected void onPointerDown(EventId eventId, MotionEvent event) {}

  protected void onMove(EventId eventId, MotionEvent event) {}

  protected void onPointerUp(EventId eventId, MotionEvent event) {}

  protected void onUp(EventId eventId, MotionEvent event) {}

  protected void cancelAfterTapTimeout(EventId eventId, MotionEvent event) {
    cancelAfter(ViewConfiguration.getTapTimeout(), event);
  }

  protected final void cancelAfterDoubleTapTimeout(MotionEvent event) {
    cancelAfter(ViewConfiguration.getDoubleTapTimeout(), event);
  }

  protected final void cancelAfter(long timeout, MotionEvent event) {
    delayedTransition.cancel();
    delayedTransition.post(STATE_GESTURE_CANCELED, timeout, event);
  }

  protected final void cancelPendingTransitions() {
    delayedTransition.cancel();
  }

  protected final void completeAfterLongPressTimeout(EventId eventId, MotionEvent event) {
    completeAfter(ViewConfiguration.getLongPressTimeout(), eventId, event);
  }

  protected final void completeAfterTapTimeout(EventId eventId, MotionEvent event) {
    completeAfter(ViewConfiguration.getTapTimeout(), eventId, event);
  }

  protected final void completeAfter(long timeout, EventId eventId, MotionEvent event) {
    delayedTransition.cancel();
    Performance.getInstance().onGestureLastMotionEventTime(eventId, event.getEventTime());
    delayedTransition.post(STATE_GESTURE_COMPLETED, timeout, event);
  }

  protected final void completeAfterDoubleTapTimeout(EventId eventId, MotionEvent event) {
    completeAfter(ViewConfiguration.getDoubleTapTimeout(), eventId, event);
  }

  void gestureMotionEventLog(int logLevel, String format, @Nullable Object... args) {
    if (logMotionEvent) {
      switch (logLevel) {
        case ERROR:
          LogUtils.e(getGestureName(), format, args);
          break;
        case VERBOSE:

        default:
          LogUtils.v(getGestureName(), format, args);
          break;
      }
    }
  }

  static String getStateSymbolicName(@State int state) {
    return switch (state) {
      case STATE_CLEAR -> "STATE_CLEAR";
      case STATE_GESTURE_STARTED -> "STATE_GESTURE_STARTED";
      case STATE_GESTURE_COMPLETED -> "STATE_GESTURE_COMPLETED";
      case STATE_GESTURE_CANCELED -> "STATE_GESTURE_CANCELED";
      case STATE_GESTURE_PROCESSING -> "STATE_GESTURE_PROCESSING";
      default -> "Unknown state: " + state;
    };
  }

  protected abstract String getGestureName();

  @Override
  public String toString() {
    return getGestureName() + ":" + getStateSymbolicName(state);
  }

  protected final class DelayedTransition implements Runnable {

    private static final String LOG_TAG = "GestureMatcher.DelayedTransition";
    int targetState;
    MotionEvent event;

    public void cancel() {

      synchronized (GestureMatcher.this) {
        if (isPending()) {
          LogUtils.v(
              LOG_TAG,
              "%s: canceling delayed transition to %s",
              getGestureName(),
              getStateSymbolicName(targetState));
        }
        handler.removeCallbacks(this);
        recycleEvent();
      }
    }

    public void post(int state, long delay, MotionEvent event) {
      synchronized (GestureMatcher.this) {
        this.targetState = state;

        recycleEvent();
        this.event = MotionEvent.obtain(event);
        handler.postDelayed(this, delay);
        LogUtils.v(
            LOG_TAG,
            "%s: posting delayed transition to %s",
            getGestureName(),
            getStateSymbolicName(targetState));
      }
    }

    public boolean isPending() {
      return handler.hasCallbacks(this);
    }

    public void forceSendAndRemove() {
      if (isPending()) {
        run();
        cancel();
      }
    }

    @Override
    public void run() {
      synchronized (GestureMatcher.this) {
        if (event == null) {
          return;
        }
        LogUtils.v(
            LOG_TAG,
            "%s: executing delayed transition to %s",
            getGestureName(),
            getStateSymbolicName(targetState));
        setState(targetState, event);
        recycleEvent();
      }
    }

    private void recycleEvent() {
      if (event == null) {
        return;
      }
      event.recycle();
      event = null;
    }
  }

  public interface StateChangeListener {

    void onStateChanged(int gestureId, int state, MotionEvent event);
  }

  public interface AnalyticsEventLogger {

    void logAnalyticsEvent(GestureAnalyticsEvent analyticsEvent);
  }
}
