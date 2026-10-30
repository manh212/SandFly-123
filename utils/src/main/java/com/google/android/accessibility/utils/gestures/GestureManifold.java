/*
 * Copyright (C) 2021s The Android Open Source Project
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

import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_UP;
import static com.google.android.accessibility.utils.gestures.TwoFingerSecondFingerMultiTap.ROTATE_DIRECTION_BACKWARD;
import static com.google.android.accessibility.utils.gestures.TwoFingerSecondFingerMultiTap.ROTATE_DIRECTION_FORWARD;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.content.Context;
import android.os.Build;
import android.view.MotionEvent;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.gestures.GestureMatcher.AnalyticsEventLogger;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class GestureManifold implements GestureMatcher.StateChangeListener {
  public static final int GESTURE_FAKED_SPLIT_TYPING = -3;
  public static final int GESTURE_TAP_HOLD_AND_2ND_FINGER_FORWARD_DOUBLE_TAP = -4;
  public static final int GESTURE_TAP_HOLD_AND_2ND_FINGER_BACKWARD_DOUBLE_TAP = -5;
  public static final int GESTURE_TOUCH_EXPLORE = -6;
  public static final int GESTURE_TAP_UP_TOUCH_EXPLORE = -7;
  public static final int GESTURE_FAKED_SPLIT_TYPING_AND_HOLD = -8;

  public static final int GESTURE_2_FINGER_SINGLE_TAP_AND_HOLD = 63;

  private static final String LOG_TAG = "GestureManifold";

  private final List<GestureMatcher> gestures = new ArrayList<>();
  private final int displayId;

  private final Listener listener;

  boolean multiFingerGesturesEnabled;

  private boolean twoFingerPassthroughEnabled;

  private final List<GestureMatcher> multiFingerGestures = new ArrayList<>();

  private final List<GestureMatcher> twoFingerSwipes = new ArrayList<>();
  private boolean logMotionEvent = false;

  public interface GestureConfigProvider {
    default float getDoubleTapSlopMultiplier() {
      return (float) 1.0;
    }

    default boolean getSpeedUpTouchExploreState() {
      return false;
    }

    default boolean invalidSwipeGestureEarlyDetection() {
      return false;
    }

    default boolean useMultipleGestureSet() {
      return false;
    }

    default boolean enableSplitTapAndHold() {
      return false;
    }
  }

  public GestureManifold(
      Context context,
      Listener listener,
      GestureConfigProvider configResolver,
      AnalyticsEventLogger logger,
      int displayId,
      ImmutableList<String> supportGestureList) {
    this.listener = listener;
    this.displayId = displayId;
    multiFingerGesturesEnabled = false;
    twoFingerPassthroughEnabled = false;

    List<GestureMatcher> gestureMatcherList =
        GestureMatcherFactory.getGestureMatcherList(
            context, supportGestureList, this, configResolver, logger);

    for (GestureMatcher gestureMatcher : gestureMatcherList) {
      if (gestureMatcher != null) {
        if ((gestureMatcher instanceof Swipe)
            || (gestureMatcher instanceof MultiTap)
            || (gestureMatcher instanceof MultiTapAndHold)
            || (gestureMatcher instanceof SecondFingerTap)) {
          gestures.add(gestureMatcher);
        } else {
          multiFingerGestures.add(gestureMatcher);
          int gestureId = gestureMatcher.getGestureId();
          if ((gestureId == GESTURE_2_FINGER_SWIPE_DOWN)
              || (gestureId == GESTURE_2_FINGER_SWIPE_LEFT)
              || (gestureId == GESTURE_2_FINGER_SWIPE_RIGHT)
              || (gestureId == GESTURE_2_FINGER_SWIPE_UP)) {
            twoFingerSwipes.add(gestureMatcher);
          }
        }
      }
    }
    if (configResolver.useMultipleGestureSet()) {
      gestures.add(
          new TwoFingerSecondFingerMultiTap(
              context,
              2,
              ROTATE_DIRECTION_FORWARD,
              GESTURE_TAP_HOLD_AND_2ND_FINGER_FORWARD_DOUBLE_TAP,
              this,
              logger));
      gestures.add(
          new TwoFingerSecondFingerMultiTap(
              context,
              2,
              ROTATE_DIRECTION_BACKWARD,
              GESTURE_TAP_HOLD_AND_2ND_FINGER_BACKWARD_DOUBLE_TAP,
              this,
              logger));
    }
  }

  public void onConfigurationChanged(Context context) {
    for (GestureMatcher gestureDetector : gestures) {
      gestureDetector.onConfigurationChanged(context);
    }
  }

  public void enableLogMotionEvent() {
    logMotionEvent = true;
    for (GestureMatcher gestureDetector : gestures) {
      gestureDetector.enableLogMotionEvent();
    }
  }

  @CanIgnoreReturnValue
  public boolean onMotionEvent(EventId eventId, MotionEvent event) {
    for (GestureMatcher matcher : gestures) {
      if (matcher.getState() != GestureMatcher.STATE_GESTURE_CANCELED) {
        if (logMotionEvent) {
          LogUtils.v(LOG_TAG, matcher.toString());
        }
        matcher.onMotionEvent(eventId, event);
        if (logMotionEvent) {
          LogUtils.v(LOG_TAG, matcher.toString());
        }

        if (matcher.getState() == GestureMatcher.STATE_GESTURE_COMPLETED) {

          return true;
        }
      }
    }
    return false;
  }

  public void addGestureMatcher(GestureMatcher matcher) {
    gestures.add(0, matcher);
  }

  public void clear() {
    for (GestureMatcher matcher : gestures) {
      matcher.clear();
    }
  }

  public interface Listener {

    void onGestureStarted(int gestureId);

    void onGestureCompleted(AccessibilityGestureEvent gestureEvent);

    void onGestureCancelled(int gestureId);

    default void onGestureProcessing(int gestureId) {}
  }

  @Override
  public void onStateChanged(int gestureId, int state, MotionEvent event) {
    if (state == GestureMatcher.STATE_GESTURE_STARTED) {
      listener.onGestureStarted(gestureId);
    } else if (state == GestureMatcher.STATE_GESTURE_COMPLETED) {
      onGestureCompleted(gestureId, event);
    } else if (state == GestureMatcher.STATE_GESTURE_CANCELED) {
      listener.onGestureCancelled(gestureId);
    } else if (state == GestureMatcher.STATE_GESTURE_PROCESSING) {
      listener.onGestureProcessing(gestureId);
    }
  }

  private void onGestureCompleted(int gestureId, MotionEvent event) {

    ArrayList<MotionEvent> eventList = new ArrayList<>();
    eventList.add(event);
    AccessibilityGestureEvent gestureEvent =
        new AccessibilityGestureEvent(gestureId, displayId, eventList);
    for (GestureMatcher matcher : gestures) {
      if (gestureId == GESTURE_TAP_UP_TOUCH_EXPLORE
          && matcher.bypassCancelByTapUpToTouchExplore()) {

        continue;
      } else if (gestureId == GESTURE_FAKED_SPLIT_TYPING
          && matcher.getGestureId() == GESTURE_FAKED_SPLIT_TYPING_AND_HOLD) {
        matcher.restart(true);
        continue;
      } else if (gestureId == GESTURE_FAKED_SPLIT_TYPING_AND_HOLD
          && matcher.getGestureId() == GESTURE_FAKED_SPLIT_TYPING) {
        matcher.restart(true);
        continue;
      }
      if (matcher.getGestureId() != gestureId) {
        matcher.cancelGesture(event, false);
      }
    }
    listener.onGestureCompleted(gestureEvent);
  }

  public boolean isMultiFingerGesturesEnabled() {
    return multiFingerGesturesEnabled;
  }

  public void setMultiFingerGesturesEnabled(boolean mode) {
    if (multiFingerGesturesEnabled != mode) {
      multiFingerGesturesEnabled = mode;
      if (mode) {
        gestures.addAll(multiFingerGestures);
      } else {
        gestures.removeAll(multiFingerGestures);
      }
    }
  }

  public boolean isTwoFingerPassthroughEnabled() {
    return twoFingerPassthroughEnabled;
  }

  public void setTwoFingerPassthroughEnabled(boolean mode) {
    if (twoFingerPassthroughEnabled != mode) {
      twoFingerPassthroughEnabled = mode;
      if (!mode) {
        multiFingerGestures.addAll(twoFingerSwipes);
        if (multiFingerGesturesEnabled) {
          gestures.addAll(twoFingerSwipes);
        }
      } else {
        multiFingerGestures.removeAll(twoFingerSwipes);
        gestures.removeAll(twoFingerSwipes);
      }
    }
  }

  public static class TapToTouchExploreAnalyticsEvent extends GestureAnalyticsEvent {
    public int savedTimeMs;

    public TapToTouchExploreAnalyticsEvent(int event, int gestureId, int savedTimeMs) {
      super(event, gestureId);
      this.savedTimeMs = savedTimeMs;
    }
  }
}
