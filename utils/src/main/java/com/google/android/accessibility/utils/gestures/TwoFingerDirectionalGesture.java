package com.google.android.accessibility.utils.gestures;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.utils.Performance.EventId;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public final class TwoFingerDirectionalGesture extends GestureMatcher {
  public static final int UP = 0;
  public static final int DOWN = 1;
  public static final int LEFT = 2;
  public static final int RIGHT = 3;

  private final int first;
  private final int second;
  private final float minimum;
  private final float slop;
  private final Stroke[] strokes = {new Stroke(), new Stroke()};
  private final int[] ids = {-1, -1};
  private long startedAt;
  private long liftedAt;
  private boolean paired;
  private boolean lifting;
  private float initialDx;
  private float initialDy;

  public TwoFingerDirectionalGesture(Context context, int id, int first, int second,
      StateChangeListener listener, AnalyticsEventLogger logger) {
    super(id, new Handler(context.getMainLooper()), listener, logger);
    this.first = first;
    this.second = second;
    minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 6f,
        context.getResources().getDisplayMetrics());
    slop = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(), minimum / 4f);
    clear();
  }

  @Override public void clear() {
    ids[0] = ids[1] = -1;
    paired = lifting = false;
    startedAt = liftedAt = 0;
    super.clear();
  }

  @Override protected void onDown(EventId eventId, MotionEvent event) {
    if (event.getPointerCount() != 1 || ids[0] != -1) { cancelGesture(event); return; }
    ids[0] = event.getPointerId(0);
    startedAt = event.getEventTime();
    strokes[0].reset(event.getX(), event.getY());
  }

  @Override protected void onPointerDown(EventId eventId, MotionEvent event) {
    if (paired || event.getPointerCount() != 2 || ids[0] < 0 ||
        event.getEventTime() - startedAt > 250L) { cancelGesture(event); return; }
    int firstIndex = event.findPointerIndex(ids[0]);
    int added = event.getActionIndex();
    if (firstIndex < 0 || added == firstIndex ||
        Math.hypot(event.getX(firstIndex) - strokes[0].startX,
            event.getY(firstIndex) - strokes[0].startY) > slop * 2) { cancelGesture(event); return; }
    ids[1] = event.getPointerId(added);
    strokes[0].reset(event.getX(firstIndex), event.getY(firstIndex));
    strokes[1].reset(event.getX(added), event.getY(added));
    initialDx = event.getX(added) - event.getX(firstIndex);
    initialDy = event.getY(added) - event.getY(firstIndex);
    paired = true;
  }

  @Override protected void onMove(EventId eventId, MotionEvent event) {
    if (event.getEventTime() - startedAt > 3500L || event.getPointerCount() > 2) {
      cancelGesture(event); return;
    }
    if (!paired) {
      if (Math.hypot(event.getX() - strokes[0].startX, event.getY() - strokes[0].startY) > slop * 2) cancelGesture(event);
      return;
    }
    if (lifting) return;
    int a = event.findPointerIndex(ids[0]);
    int b = event.findPointerIndex(ids[1]);
    if (a < 0 || b < 0 || !coherent(event.getX(b) - event.getX(a), event.getY(b) - event.getY(a))) {
      cancelGesture(event); return;
    }
    for (int history = 0; history < event.getHistorySize(); history++) {
      if (!update(event.getHistoricalX(a, history), event.getHistoricalY(a, history),
          event.getHistoricalX(b, history), event.getHistoricalY(b, history))) { cancelGesture(event); return; }
    }
    if (!update(event.getX(a), event.getY(a), event.getX(b), event.getY(b))) { cancelGesture(event); return; }
    if (getState() == STATE_CLEAR && strokes[0].phase > 0 && strokes[1].phase > 0) startGesture(event);
  }

  @Override protected void onPointerUp(EventId eventId, MotionEvent event) {
    if (!paired || lifting || event.getPointerCount() != 2) { cancelGesture(event); return; }
    onMove(eventId, event);
    if (getState() == STATE_GESTURE_CANCELED) return;
    if (!strokes[0].complete() || !strokes[1].complete()) { cancelGesture(event); return; }
    lifting = true;
    liftedAt = event.getEventTime();
  }

  @Override protected void onUp(EventId eventId, MotionEvent event) {
    if (!paired || !lifting || event.getEventTime() - liftedAt > 180L ||
        event.getEventTime() - startedAt > 3500L) { cancelGesture(event); return; }
    completeGesture(eventId, event);
  }

  private boolean update(float ax, float ay, float bx, float by) {
    return strokes[0].move(ax, ay) && strokes[1].move(bx, by);
  }

  private boolean coherent(float dx, float dy) {
    float initial = (float) Math.hypot(initialDx, initialDy);
    float current = (float) Math.hypot(dx, dy);
    return Math.abs(current - initial) <= Math.max(minimum, initial * .45f) &&
        (initial < slop || current < slop || (dx * initialDx + dy * initialDy) / (initial * current) > .8f);
  }

  private static float along(float dx, float dy, int direction) {
    return switch (direction) { case UP -> -dy; case DOWN -> dy; case LEFT -> -dx; default -> dx; };
  }

  private static float across(float dx, float dy, int direction) {
    return direction == UP || direction == DOWN ? Math.abs(dx) : Math.abs(dy);
  }

  private final class Stroke {
    float startX, startY, pivotX, pivotY, endX, endY;
    int phase;
    void reset(float x, float y) { startX = pivotX = endX = x; startY = pivotY = endY = y; phase = 0; }
    boolean move(float x, float y) {
      endX = x; endY = y;
      if (phase == 0) {
        float progress = along(x - startX, y - startY, first);
        if (progress < -slop || across(x - startX, y - startY, first) > Math.max(slop, progress * .55f)) return false;
        if (progress >= minimum) { phase = 1; pivotX = x; pivotY = y; }
        return true;
      }
      float dx = x - pivotX, dy = y - pivotY;
      if (phase == 1) {
        float forward = along(dx, dy, first);
        if (forward > 0 && across(dx, dy, first) < Math.max(slop, forward * .55f)) { pivotX = x; pivotY = y; return true; }
        float turn = along(dx, dy, second);
        if (turn >= slop && across(dx, dy, second) <= Math.max(slop, turn * .55f)) phase = 2;
        else if (Math.hypot(dx, dy) > slop * 2) return false;
        return true;
      }
      float progress = along(dx, dy, second);
      return progress >= -slop && across(dx, dy, second) <= Math.max(slop, progress * .55f);
    }
    boolean complete() { return phase == 2 && along(endX - pivotX, endY - pivotY, second) >= minimum; }
  }

  @Override protected String getGestureName() { return "TwoFingerDirectionalGesture:" + first + ":" + second; }
}
