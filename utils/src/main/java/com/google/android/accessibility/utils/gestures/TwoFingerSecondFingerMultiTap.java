/*
 * Copyright (C) 2023 Google Inc.
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

import static android.util.Log.VERBOSE;
import static android.view.MotionEvent.INVALID_POINTER_ID;

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.IntDef;
import com.google.android.accessibility.utils.Performance.EventId;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

public class TwoFingerSecondFingerMultiTap extends GestureMatcher {
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({ROTATE_DIRECTION_DONT_CARE, ROTATE_DIRECTION_FORWARD, ROTATE_DIRECTION_BACKWARD})
  @interface RotateDirection {}

  public static final int ROTATE_DIRECTION_DONT_CARE = 0;
  public static final int ROTATE_DIRECTION_FORWARD = 1;
  public static final int ROTATE_DIRECTION_BACKWARD = 2;

  private final int targetTapCount;

  private final int targetFingerCount;
  private final int tapTimeout;

  private final int touchSlop;

  private int completedTapCount;

  private boolean isTargetFingerCountReached = false;

  private final PointF[] bases;
  private final int[] pointerIds;
  private long lastDownTime;
  private long lastUpTime;
  @RotateDirection private final int rotateDirection;
  private int tappingIndex;

  TwoFingerSecondFingerMultiTap(
      Context context,
      int taps,
      @RotateDirection int rotateDirection,
      int gestureId,
      GestureMatcher.StateChangeListener listener,
      GestureMatcher.AnalyticsEventLogger logger) {
    super(gestureId, new Handler(context.getMainLooper()), listener, logger);
    this.rotateDirection = rotateDirection;
    targetTapCount = taps;
    targetFingerCount = 2;
    pointerIds = new int[targetFingerCount];
    tapTimeout = targetFingerCount * ViewConfiguration.getTapTimeout();
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop() * targetFingerCount;

    bases = new PointF[targetFingerCount];
    for (int i = 0; i < bases.length; i++) {
      bases[i] = new PointF();
    }
    clear();
  }

  @Override
  public void clear() {
    completedTapCount = 0;
    isTargetFingerCountReached = false;
    for (int i = 0; i < targetFingerCount; ++i) {
      pointerIds[i] = INVALID_POINTER_ID;
      bases[i].x = Float.NaN;
      bases[i].y = Float.NaN;
    }
    lastDownTime = Long.MAX_VALUE;
    lastUpTime = Long.MAX_VALUE;
    super.clear();
  }

  @Override
  protected void onDown(EventId eventId, MotionEvent event) {
    lastDownTime = event.getEventTime();
    if (pointerIds[0] != INVALID_POINTER_ID) {

      cancelGesture(event);
      return;
    }
    pointerIds[0] = 0;
    if (Float.isNaN(bases[0].x) && Float.isNaN(bases[0].y)) {
      final float x = event.getX(0);
      final float y = event.getY(0);
      if (x < 0f || y < 0f) {
        gestureMotionEventLog(VERBOSE, "MotionEvent position's incorrect.");
        cancelGesture(event);
        return;
      }
      bases[0].x = x;
      bases[0].y = y;
    } else {
      gestureMotionEventLog(VERBOSE, "MotionEvent comes out of sync.");

      cancelGesture(event);
      return;
    }
  }

  @Override
  protected void onUp(EventId eventId, MotionEvent event) {

    if (completedTapCount != targetTapCount) {
      gestureMotionEventLog(VERBOSE, "The expected tap count does not reach.");
      cancelGesture(event);
      return;
    }
    completeGesture(eventId, event);
  }

  @Override
  protected void onMove(EventId eventId, MotionEvent event) {
    if (Float.isNaN(bases[0].x) && Float.isNaN(bases[0].y)) {
      return;
    }

    final int currentFingerCount = event.getPointerCount();
    for (int i = 0; i < currentFingerCount; i++) {
      final float delta =
          (float)
              Math.hypot(
                  event.getX(i) - bases[event.getPointerId(i)].x,
                  event.getY(i) - bases[event.getPointerId(i)].y);
      if (delta > ((completedTapCount + 1) * touchSlop)) {

        gestureMotionEventLog(VERBOSE, "MotionEvent positions move Excessively.");
        cancelGesture(event);
        return;
      }
    }
    if (currentFingerCount > targetFingerCount) {
      gestureMotionEventLog(VERBOSE, "Too many fingers involved.");
      cancelGesture(event);
      return;
    }
  }

  @Override
  protected void onPointerDown(EventId eventId, MotionEvent event) {
    if (Float.isNaN(bases[0].x) && Float.isNaN(bases[0].y)) {
      return;
    }
    long timeDelta = event.getEventTime() - lastUpTime;
    if (timeDelta > tapTimeout) {
      gestureMotionEventLog(VERBOSE, "The 2nd finger taps occur too slow.");
      cancelGesture(event);
      return;
    }
    lastDownTime = event.getEventTime();
    final int currentFingerCount = event.getPointerCount();

    if (currentFingerCount > targetFingerCount) {
      gestureMotionEventLog(VERBOSE, "Too many fingers involved.");
      cancelGesture(event);
      return;
    }
    completedTapCount++;
    if ((completedTapCount > 1) && (event.getActionIndex() != tappingIndex)) {
      gestureMotionEventLog(VERBOSE, "The tapping finger is not persistent.");
      cancelGesture(event);
      return;
    }
    if ((getState() == STATE_GESTURE_STARTED || getState() == STATE_CLEAR)) {

      if (currentFingerCount == targetFingerCount) {
        isTargetFingerCountReached = true;
      }
    } else {
      gestureMotionEventLog(VERBOSE, "MotionEvent state's out of sync.");
      cancelGesture(event);
      return;
    }
    if (completedTapCount == 1) {

      bases[1].x = event.getX(1);
      bases[1].y = event.getY(1);
    }
  }

  @Override
  protected void onPointerUp(EventId eventId, MotionEvent event) {

    if (Float.isNaN(bases[0].x) && Float.isNaN(bases[0].y)) {
      return;
    }
    if (!isTargetFingerCountReached) {
      cancelGesture(event);
      return;
    }
    if (completedTapCount == 1) {
      tappingIndex = event.getActionIndex();
      float deltaX = event.getX(tappingIndex) - bases[1 - tappingIndex].x;
      switch (rotateDirection) {
        case ROTATE_DIRECTION_FORWARD -> {
          if (deltaX <= 0) {
            gestureMotionEventLog(VERBOSE, "Rotating direction mismatches.");
            cancelGesture(event);
            return;
          }
        }
        case ROTATE_DIRECTION_BACKWARD -> {
          if (deltaX >= 0) {
            gestureMotionEventLog(VERBOSE, "Rotating direction mismatches.");
            cancelGesture(event);
            return;
          }
        }
        case ROTATE_DIRECTION_DONT_CARE -> {}
        default -> {}
      }
      if (completedTapCount == (targetTapCount - 1)) {
        startGesture(event);
      }
    } else if (tappingIndex != event.getActionIndex()) {
      gestureMotionEventLog(VERBOSE, "The tapping finger is not persistent.");
      cancelGesture(event);
      return;
    }

    if (getState() == STATE_GESTURE_STARTED || getState() == STATE_CLEAR) {

      long timeDelta = event.getEventTime() - lastDownTime;
      if (timeDelta > tapTimeout) {
        gestureMotionEventLog(VERBOSE, "The tapping finger holds too long time.");
        cancelGesture(event);
        return;
      }
    } else {
      gestureMotionEventLog(VERBOSE, "MotionEvent state's out of sync.");
      cancelGesture(event);
      return;
    }
    lastUpTime = event.getEventTime();
    if (completedTapCount == targetTapCount) {
      completeAfterDoubleTapTimeout(eventId, event);
    }
  }

  @Override
  public String getGestureName() {
    final StringBuilder builder = new StringBuilder();
    builder.append("One").append("-Finger Tap-and-hold with 2nd finger");
    if (targetTapCount == 2) {
      builder.append("Double");
    } else if (targetTapCount == 3) {
      builder.append("Triple");
    } else if (targetTapCount > 3) {
      builder.append(targetTapCount);
    }
    return builder.append(" Tap").toString();
  }

  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder(super.toString());
    if (getState() != STATE_GESTURE_CANCELED) {
      builder.append(", CompletedTapCount: ");
      builder.append(completedTapCount);
      builder.append(", IsTargetFingerCountReached: ");
      builder.append(isTargetFingerCountReached);
      builder.append(", Bases: ");
      builder.append(Arrays.toString(bases));
    }
    return builder.toString();
  }
}
