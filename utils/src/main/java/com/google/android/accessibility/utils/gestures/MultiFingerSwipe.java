/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.R;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class MultiFingerSwipe extends GestureMatcher {

  public static final int LEFT = 0;
  public static final int RIGHT = 1;
  public static final int UP = 2;
  public static final int DOWN = 3;
  public static final int UNCERTAIN = 4;

  private final List<List<PointF>> strokeBuffers;

  private int targetDirection;
  private int[] pointerIds;

  private PointF[] base;

  private PointF[] previousGesturePoint;
  private int targetFingerCount;
  private int currentFingerCount;

  private boolean targetFingerCountReached = false;

  private static final float MIN_CM_BETWEEN_SAMPLES = 0.25f;
  private float minPixelsBetweenSamplesX;
  private float minPixelsBetweenSamplesY;

  private int touchSlop;
  private int tapTimeout;

  private long lastDownTime;

  MultiFingerSwipe(
      Context context,
      int fingerCount,
      int direction,
      int gesture,
      GestureMatcher.StateChangeListener listener,
      GestureMatcher.AnalyticsEventLogger logger) {
    super(gesture, new Handler(context.getMainLooper()), listener, logger);
    targetFingerCount = fingerCount;
    pointerIds = new int[targetFingerCount];
    base = new PointF[targetFingerCount];
    previousGesturePoint = new PointF[targetFingerCount];
    strokeBuffers = new ArrayList<>();
    for (int i = 0; i < targetFingerCount; ++i) {
      strokeBuffers.add(new ArrayList<PointF>());
    }
    targetDirection = direction;
    initializeViewConfigurationParameters(context);
    clear();
  }

  @Override
  public void onConfigurationChanged(Context context) {
    initializeViewConfigurationParameters(context);
  }

  private void initializeViewConfigurationParameters(Context context) {
    DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();

    final float pixelsPerCmX = displayMetrics.xdpi / GestureUtils.CM_PER_INCH;
    final float pixelsPerCmY = displayMetrics.ydpi / GestureUtils.CM_PER_INCH;
    minPixelsBetweenSamplesX = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmX;
    minPixelsBetweenSamplesY = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmY;
    tapTimeout = targetFingerCount * ViewConfiguration.getTapTimeout();
    touchSlop =
        ViewConfiguration.get(context).getScaledTouchSlop()
            * context.getResources().getInteger(R.integer.config_slop_default_multiplier);
  }

  @Override
  public void clear() {
    targetFingerCountReached = false;
    currentFingerCount = 0;
    for (int i = 0; i < targetFingerCount; ++i) {
      pointerIds[i] = INVALID_POINTER_ID;
      if (base[i] == null) {
        base[i] = new PointF();
      }
      base[i].x = Float.NaN;
      base[i].y = Float.NaN;
      if (previousGesturePoint[i] == null) {
        previousGesturePoint[i] = new PointF();
      }
      previousGesturePoint[i].x = Float.NaN;
      previousGesturePoint[i].y = Float.NaN;
      strokeBuffers.get(i).clear();
    }
    lastDownTime = Long.MAX_VALUE;
    super.clear();
  }

  @Override
  protected void onDown(EventId eventId, MotionEvent event) {
    if (currentFingerCount > 0) {
      cancelGesture(event);
      return;
    }
    currentFingerCount = 1;
    final int actionIndex = event.getActionIndex();
    final int pointerId = event.getPointerId(actionIndex);
    int pointerIndex = event.getPointerCount() - 1;
    if (pointerId < 0) {

      cancelGesture(event);
      return;
    }
    if (pointerIds[pointerIndex] != INVALID_POINTER_ID) {

      cancelGesture(event);
      return;
    }
    lastDownTime = event.getEventTime();
    pointerIds[pointerIndex] = pointerId;
    if (Float.isNaN(base[pointerIndex].x) && Float.isNaN(base[pointerIndex].y)) {
      final float x = event.getX(actionIndex);
      final float y = event.getY(actionIndex);
      if (x < 0f || y < 0f) {
        cancelGesture(event);
        return;
      }
      base[pointerIndex].x = x;
      base[pointerIndex].y = y;
      previousGesturePoint[pointerIndex].x = x;
      previousGesturePoint[pointerIndex].y = y;
    } else {

      cancelGesture(event);
      return;
    }
  }

  @Override
  protected void onPointerDown(EventId eventId, MotionEvent event) {
    if (event.getPointerCount() > targetFingerCount) {
      cancelGesture(event);
      return;
    }
    long timeDelta = event.getEventTime() - lastDownTime;
    if (timeDelta > tapTimeout) {
      cancelGesture(event);
      return;
    }
    lastDownTime = event.getEventTime();
    currentFingerCount += 1;
    if (currentFingerCount != event.getPointerCount()) {
      cancelGesture(event);
      return;
    }
    if (currentFingerCount == targetFingerCount) {
      targetFingerCountReached = true;
    }
    final int actionIndex = event.getActionIndex();
    final int pointerId = event.getPointerId(actionIndex);
    if (pointerId < 0) {

      cancelGesture(event);
      return;
    }
    int pointerIndex = currentFingerCount - 1;
    if (pointerIds[pointerIndex] != INVALID_POINTER_ID) {

      cancelGesture(event);
      return;
    }
    pointerIds[pointerIndex] = pointerId;
    if (Float.isNaN(base[pointerIndex].x) && Float.isNaN(base[pointerIndex].y)) {
      final float x = event.getX(actionIndex);
      final float y = event.getY(actionIndex);
      if (x < 0f || y < 0f) {
        cancelGesture(event);
        return;
      }
      base[pointerIndex].x = x;
      base[pointerIndex].y = y;
      previousGesturePoint[pointerIndex].x = x;
      previousGesturePoint[pointerIndex].y = y;
    } else {
      cancelGesture(event);
      return;
    }
  }

  @Override
  protected void onPointerUp(EventId eventId, MotionEvent event) {
    if (!targetFingerCountReached) {
      cancelGesture(event);
      return;
    }
    currentFingerCount -= 1;
    final int actionIndex = event.getActionIndex();
    final int pointerId = event.getPointerId(actionIndex);
    if (pointerId < 0) {

      cancelGesture(event);
      return;
    }
    final int pointerIndex = Arrays.binarySearch(pointerIds, pointerId);
    if (pointerIndex < 0) {
      cancelGesture(event);
      return;
    }
    final float x = event.getX(actionIndex);
    final float y = event.getY(actionIndex);
    if (x < 0f || y < 0f) {
      cancelGesture(event);
      return;
    }
    final float dX = Math.abs(x - previousGesturePoint[pointerIndex].x);
    final float dY = Math.abs(y - previousGesturePoint[pointerIndex].y);
    if (dX >= minPixelsBetweenSamplesX || dY >= minPixelsBetweenSamplesY) {
      strokeBuffers.get(pointerIndex).add(new PointF(x, y));
    }

  }

  @Override
  protected void onMove(EventId eventId, MotionEvent event) {
    for (int pointerIndex = 0; pointerIndex < targetFingerCount; ++pointerIndex) {
      if (pointerIds[pointerIndex] == INVALID_POINTER_ID) {

        continue;
      }
      gestureMotionEventLog(
          VERBOSE, getGestureName(), "Processing move on finger %d", pointerIndex);
      int index = event.findPointerIndex(pointerIds[pointerIndex]);
      if (index < 0) {

        gestureMotionEventLog(
            VERBOSE, "Finger %d not found in this event. skipping.", pointerIndex);
        continue;
      }
      final float x = event.getX(index);
      final float y = event.getY(index);
      if (x < 0f || y < 0f) {
        cancelGesture(event);
        return;
      }
      final float dX = Math.abs(x - previousGesturePoint[pointerIndex].x);
      final float dY = Math.abs(y - previousGesturePoint[pointerIndex].y);
      final double moveDelta =
          Math.hypot(Math.abs(x - base[pointerIndex].x), Math.abs(y - base[pointerIndex].y));
      gestureMotionEventLog(VERBOSE, "moveDelta%g", moveDelta);
      if (getState() == STATE_CLEAR) {
        if (moveDelta < (targetFingerCount * touchSlop)) {

          continue;
        }

        if (currentFingerCount != targetFingerCount) {
          cancelGesture(event);
          return;
        }

        int direction = toDirection(x - base[pointerIndex].x, y - base[pointerIndex].y);
        if (direction != targetDirection) {
          cancelGesture(event);
          return;
        }

        startGesture(event);
        for (int i = 0; i < targetFingerCount; ++i) {
          strokeBuffers.get(i).add(new PointF(base[i]));
        }
      } else if (getState() == STATE_GESTURE_STARTED) {

        int direction = toDirection(x - base[pointerIndex].x, y - base[pointerIndex].y);
        if (direction != UNCERTAIN && direction != targetDirection) {
          cancelGesture(event);
          return;
        }
        if (dX >= minPixelsBetweenSamplesX || dY >= minPixelsBetweenSamplesY) {

          previousGesturePoint[pointerIndex].x = x;
          previousGesturePoint[pointerIndex].y = y;
          strokeBuffers.get(pointerIndex).add(new PointF(x, y));
        }
      }
    }
  }

  @Override
  protected void onUp(EventId eventId, MotionEvent event) {
    switch (getState()) {
      case STATE_GESTURE_STARTED -> {}
      case STATE_CLEAR -> {

        return;
      }
      default -> {
        cancelGesture(event);
        return;
      }
    }
    currentFingerCount = 0;
    final int actionIndex = event.getActionIndex();
    final int pointerId = event.getPointerId(actionIndex);
    final int pointerIndex = Arrays.binarySearch(pointerIds, pointerId);
    if (pointerIndex < 0) {
      cancelGesture(event);
      return;
    }
    final float x = event.getX(actionIndex);
    final float y = event.getY(actionIndex);
    if (x < 0f || y < 0f) {
      cancelGesture(event);
      return;
    }
    final float dX = Math.abs(x - previousGesturePoint[pointerIndex].x);
    final float dY = Math.abs(y - previousGesturePoint[pointerIndex].y);
    if (dX >= minPixelsBetweenSamplesX || dY >= minPixelsBetweenSamplesY) {
      strokeBuffers.get(pointerIndex).add(new PointF(x, y));
    }
    recognizeGesture(eventId, event);
  }

  private void recognizeGesture(EventId eventId, MotionEvent event) {

    for (int i = 0; i < targetFingerCount; ++i) {
      gestureMotionEventLog(VERBOSE, "Recognizing finger: %d", i);
      if (strokeBuffers.get(i).size() < 2) {
        Log.d(getGestureName(), "Too few points.");
        cancelGesture(event);
        return;
      }
      List<PointF> path = strokeBuffers.get(i);

      gestureMotionEventLog(VERBOSE, "path= %s", path.toString());

      if (!recognizeGesturePath(path)) {
        cancelGesture(event);
        return;
      }
    }

    completeGesture(eventId, event);
  }

  private boolean recognizeGesturePath(List<PointF> path) {
    for (int i = 0; i < path.size() - 1; ++i) {
      PointF start = path.get(i);
      PointF end = path.get(i + 1);

      float dX = end.x - start.x;
      float dY = end.y - start.y;
      int direction = toDirection(dX, dY);
      if (direction != targetDirection) {
        gestureMotionEventLog(
            VERBOSE,
            "Found direction %s when expecting %s",
            directionToString(direction),
            directionToString(this.targetDirection));
        return false;
      }
    }
    gestureMotionEventLog(VERBOSE, "Completed.");
    return true;
  }

  private static int toDirection(float dX, float dY) {
    if (dX == 0 && dY == 0) {
      return UNCERTAIN;
    }
    if (Math.abs(dX) > Math.abs(dY)) {

      return (dX < 0) ? LEFT : RIGHT;
    } else {

      return (dY < 0) ? UP : DOWN;
    }
  }

  public static String directionToString(int direction) {
    return switch (direction) {
      case LEFT -> "left";
      case RIGHT -> "right";
      case UP -> "up";
      case DOWN -> "down";
      case UNCERTAIN -> "still";
      default -> "Unknown Direction";
    };
  }

  @Override
  protected String getGestureName() {
    StringBuilder builder = new StringBuilder();
    builder.append(targetFingerCount).append("-finger ");
    builder.append("Swipe ").append(directionToString(targetDirection));
    return builder.toString();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder(super.toString());
    if (getState() != STATE_GESTURE_CANCELED) {
      builder
          .append(", mBase: ")
          .append(Arrays.toString(base))
          .append(", mMinPixelsBetweenSamplesX:")
          .append(minPixelsBetweenSamplesX)
          .append(", mMinPixelsBetweenSamplesY:")
          .append(minPixelsBetweenSamplesY);
    }
    return builder.toString();
  }
}
