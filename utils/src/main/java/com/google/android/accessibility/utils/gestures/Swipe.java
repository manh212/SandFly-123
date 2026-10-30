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

import static android.util.Log.VERBOSE;
import static com.google.android.accessibility.utils.gestures.GestureUtils.MM_PER_CM;

import android.content.Context;
import android.graphics.PointF;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.R;
import com.google.android.accessibility.utils.gestures.GestureManifold.GestureConfigProvider;
import java.util.ArrayList;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class Swipe extends GestureMatcher {

  public static final int NONE = -1;
  public static final int LEFT = 0;
  public static final int RIGHT = 1;
  public static final int UP = 2;
  public static final int DOWN = 3;

  private final float gestureDetectionThresholdPixels;

  private final ArrayList<PointF> strokeBuffer = new ArrayList<>(100);

  private static final float MIN_CM_BETWEEN_SAMPLES = 0.25f;
  private final int[] directions;
  private float baseX;
  private float baseY;
  private long baseTime;
  private float previousGestureX;
  private float previousGestureY;
  private final float minPixelsBetweenSamplesX;
  private final float minPixelsBetweenSamplesY;

  private final int maxStartThreshold;

  private final int maxContinueThreshold;

  private final int touchSlop;

  private static final float ANGLE_THRESHOLD = 0.0f;
  private final boolean invalidSwipeGestureEarlyDetection;

  Swipe(
      Context context,
      int direction,
      int gesture,
      GestureMatcher.StateChangeListener listener,
      GestureConfigProvider configProvider,
      GestureMatcher.AnalyticsEventLogger logger) {
    this(context, new int[] {direction}, gesture, listener, configProvider, logger);
  }

  Swipe(
      Context context,
      int direction1,
      int direction2,
      int gesture,
      GestureMatcher.StateChangeListener listener,
      GestureConfigProvider configProvider,
      GestureMatcher.AnalyticsEventLogger logger) {
    this(context, new int[] {direction1, direction2}, gesture, listener, configProvider, logger);
  }

  private Swipe(
      Context context,
      int[] directions,
      int gesture,
      GestureMatcher.StateChangeListener listener,
      GestureConfigProvider configProvider,
      GestureMatcher.AnalyticsEventLogger logger) {
    super(gesture, new Handler(context.getMainLooper()), listener, logger);
    float gestureConfirmDistanceCm =
        context.getResources().getFloat(R.dimen.config_gesture_confirm_distance_cm);
    int maxTimeToStartSwipeMsPerCm =
        context.getResources().getInteger(R.integer.config_max_time_to_start_swipe_ms_per_cm);
    int maxTimeToContinueSwipeMsPerCm =
        context.getResources().getInteger(R.integer.config_max_time_to_continue_swipe_ms_per_cm);
    invalidSwipeGestureEarlyDetection = configProvider.invalidSwipeGestureEarlyDetection();
    maxStartThreshold = (int) (maxTimeToStartSwipeMsPerCm * gestureConfirmDistanceCm);
    maxContinueThreshold =

        invalidSwipeGestureEarlyDetection
            ? ((int) ((maxTimeToContinueSwipeMsPerCm * gestureConfirmDistanceCm) * 3))
            : (int) (maxTimeToContinueSwipeMsPerCm * gestureConfirmDistanceCm);

    this.directions = directions;
    DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
    gestureDetectionThresholdPixels =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, MM_PER_CM, displayMetrics)
            * gestureConfirmDistanceCm;

    final float pixelsPerCmX = displayMetrics.xdpi / 2.54f;
    final float pixelsPerCmY = displayMetrics.ydpi / 2.54f;
    minPixelsBetweenSamplesX = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmX;
    minPixelsBetweenSamplesY = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmY;
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    clear();
  }

  @Override
  public void clear() {
    baseX = Float.NaN;
    baseY = Float.NaN;
    baseTime = 0;
    previousGestureX = Float.NaN;
    previousGestureY = Float.NaN;
    strokeBuffer.clear();
    super.clear();
  }

  @Override
  protected void onDown(EventId eventId, MotionEvent event) {
    if (Float.isNaN(baseX) && Float.isNaN(baseY)) {
      baseX = event.getX();
      baseY = event.getY();
      baseTime = event.getEventTime();
      previousGestureX = baseX;
      previousGestureY = baseY;
    }

  }

  @Override
  protected void onMove(EventId eventId, MotionEvent event) {
    final float x = event.getX();
    final float y = event.getY();
    final long time = event.getEventTime();
    final float dX = Math.abs(x - previousGestureX);
    final float dY = Math.abs(y - previousGestureY);
    final double moveDelta = Math.hypot(Math.abs(x - baseX), Math.abs(y - baseY));
    final long timeDelta = time - baseTime;
    gestureMotionEventLog(
        VERBOSE,
        "moveDelta: %g,  mGestureDetectionThreshold: %g",
        moveDelta,
        gestureDetectionThresholdPixels);
    if (getState() == STATE_CLEAR) {
      if (moveDelta < touchSlop) {

        return;
      } else if (strokeBuffer.isEmpty()) {

        int direction = toDirection(x - baseX, y - baseY);
        if (direction != directions[0]) {
          cancelGesture(event);
          return;
        }

        strokeBuffer.add(new PointF(baseX, baseY));
      }
    }
    if (moveDelta > gestureDetectionThresholdPixels) {
      if (invalidSwipeGestureEarlyDetection && timeDelta > maxContinueThreshold) {
        cancelGesture(event);
        return;
      }
      if (!invalidSwipeGestureEarlyDetection || (getState() == STATE_CLEAR)) {

        baseX = x;
        baseY = y;
        baseTime = time;
        startGesture(event);
      }
    } else if (getState() == STATE_CLEAR && timeDelta > maxStartThreshold) {
        cancelGesture(event);
        return;
    } else if (getState() == STATE_GESTURE_STARTED && (timeDelta > maxContinueThreshold)) {
      cancelGesture(event);
      return;
    }

    if (dX >= minPixelsBetweenSamplesX || dY >= minPixelsBetweenSamplesY) {

      previousGestureX = x;
      previousGestureY = y;
      strokeBuffer.add(new PointF(x, y));
    }
  }

  @Override
  protected void onUp(EventId eventId, MotionEvent event) {
    switch (getState()) {
      case STATE_GESTURE_STARTED -> {}
      case STATE_CLEAR -> {

        clear();
        return;
      }
      default -> {
        cancelGesture(event);
        return;
      }
    }

    final float x = event.getX();
    final float y = event.getY();
    final float dX = Math.abs(x - previousGestureX);
    final float dY = Math.abs(y - previousGestureY);
    if (dX >= minPixelsBetweenSamplesX || dY >= minPixelsBetweenSamplesY) {
      strokeBuffer.add(new PointF(x, y));
    }
    recognizeGesture(eventId, event);
  }

  @Override
  protected void onPointerDown(EventId eventId, MotionEvent event) {
    cancelGesture(event);
  }

  @Override
  protected void onPointerUp(EventId eventId, MotionEvent event) {
    cancelGesture(event);
  }

  private void recognizeGesture(EventId eventId, MotionEvent event) {
    if (strokeBuffer.size() < 2) {
      cancelGesture(event);
      return;
    }

    ArrayList<PointF> path = new ArrayList<>();
    PointF lastDelimiter = strokeBuffer.get(0);
    path.add(lastDelimiter);

    float dX = 0;
    float dY = 0;
    int count = 0;
    float length = 0;

    PointF next = null;
    for (int i = 1; i < strokeBuffer.size(); ++i) {
      next = strokeBuffer.get(i);
      if (count > 0) {

        float currentDX = dX / count;
        float currentDY = dY / count;

        PointF newDelimiter =
            new PointF(length * currentDX + lastDelimiter.x, length * currentDY + lastDelimiter.y);

        float nextDX = next.x - newDelimiter.x;
        float nextDY = next.y - newDelimiter.y;
        float nextLength = (float) Math.hypot(nextDX, nextDY);
        nextDX = nextDX / nextLength;
        nextDY = nextDY / nextLength;

        float dot = currentDX * nextDX + currentDY * nextDY;
        if (dot < ANGLE_THRESHOLD) {
          path.add(newDelimiter);
          lastDelimiter = newDelimiter;
          dX = 0;
          dY = 0;
          count = 0;
        }
      }

      float currentDX = next.x - lastDelimiter.x;
      float currentDY = next.y - lastDelimiter.y;
      length = (float) Math.hypot(currentDX, currentDY);

      count = count + 1;
      dX = dX + currentDX / length;
      dY = dY + currentDY / length;
    }

    path.add(next);
    gestureMotionEventLog(VERBOSE, "path = %s", path.toString());

    recognizeGesturePath(eventId, event, path);
  }

  private void recognizeGesturePath(EventId eventId, MotionEvent event, ArrayList<PointF> path) {
    if (path.size() != directions.length + 1) {
      cancelGesture(event);
      return;
    }
    for (int i = 0; i < path.size() - 1; ++i) {
      PointF start = path.get(i);
      PointF end = path.get(i + 1);

      float dX = end.x - start.x;
      float dY = end.y - start.y;
      int direction = toDirection(dX, dY);
      if (direction != directions[i]) {
        gestureMotionEventLog(
            VERBOSE,
            "Found direction %s  when expecting %s",
            directionToString(direction),
            directionToString(directions[i]));
        cancelGesture(event);
        return;
      }
    }
    gestureMotionEventLog(VERBOSE, "Completed.");
    completeGesture(eventId, event);
  }

  private static int toDirection(float dX, float dY) {
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
      default -> "Unknown Direction";
    };
  }

  @Override
  protected String getGestureName() {
    StringBuilder builder = new StringBuilder();
    builder.append("Swipe ").append(directionToString(directions[0]));
    for (int i = 1; i < directions.length; ++i) {
      builder.append(" and ").append(directionToString(directions[i]));
    }
    return builder.toString();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder(super.toString());
    if (getState() != STATE_GESTURE_CANCELED) {
      builder
          .append(", mBaseX: ")
          .append(baseX)
          .append(", mBaseY: ")
          .append(baseY)
          .append(", mGestureDetectionThreshold:")
          .append(gestureDetectionThresholdPixels)
          .append(", mMinPixelsBetweenSamplesX:")
          .append(minPixelsBetweenSamplesX)
          .append(", mMinPixelsBetweenSamplesY:")
          .append(minPixelsBetweenSamplesY);
    }
    return builder.toString();
  }
}
