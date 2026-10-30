package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TwoFingerRotationTrackerTest {
  private final List<Boolean> turns = new ArrayList<>();
  private final TwoFingerRotationTracker tracker =
      new TwoFingerRotationTracker(24f, 8f, turns::add);
  private long time = 100;

  @Test public void clockwiseTurnChangesToTheNextControlAndConsumesTheTouch() {
    begin();
    assertTrue(rotate(25));
    assertEquals(List.of(true), turns);
    time += 250;
    assertTrue(rotate(65));
    assertTrue(turns.size() >= 2);
    end();
    assertFalse(tracker.isRotating());
  }

  @Test public void counterclockwiseTurnChangesToThePreviousControl() {
    begin();
    assertTrue(rotate(-25));
    assertEquals(List.of(false), turns);
    end();
  }

  @Test public void twoFingerScrollDoesNotSwitchControls() {
    begin();
    assertFalse(send(MotionEvent.ACTION_MOVE, 100, 160, 200, 160, 2));
    assertTrue(tracker.isScroll());
    assertTrue(turns.isEmpty());
    end();
  }

  @Test public void pinchDoesNotSwitchControls() {
    begin();
    assertFalse(send(MotionEvent.ACTION_MOVE, 70, 100, 230, 100, 2));
    assertFalse(tracker.isRotating());
    assertTrue(turns.isEmpty());
    end();
  }

  private void begin() {
    send(MotionEvent.ACTION_DOWN, 100, 100, 200, 100, 1);
    send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        100, 100, 200, 100, 2);
  }

  private boolean rotate(double degrees) {
    double radians = Math.toRadians(degrees);
    float dx = (float) (50 * Math.cos(radians));
    float dy = (float) (50 * Math.sin(radians));
    return send(MotionEvent.ACTION_MOVE, 150 - dx, 100 - dy, 150 + dx, 100 + dy, 2);
  }

  private void end() {
    send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        100, 100, 200, 100, 2);
    send(MotionEvent.ACTION_UP, 100, 100, 200, 100, 1);
  }

  private boolean send(int action, float x0, float y0, float x1, float y1, int count) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = i == 0 ? x0 : x1;
      coords[i].y = i == 0 ? y0 : y1;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent event = MotionEvent.obtain(
        100, time += 20, action, count, properties, coords, 0, 0, 1, 1, 0, 0, 0, 0);
    try {
      return tracker.onMotionEvent(event);
    } finally {
      event.recycle();
    }
  }
}
