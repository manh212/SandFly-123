package com.google.android.accessibility.utils.gestures;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;
import com.google.android.accessibility.utils.Performance;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class TwoFingerDirectionalGestureTest {
  private final List<Integer> completed = new ArrayList<>();
  private GestureMatcher matcher;
  private long time;

  @Before public void setUp() { completed.clear(); time = 100; }

  private void create(int first, int second) {
    Context context = RuntimeEnvironment.getApplication();
    matcher = new TwoFingerDirectionalGesture(context, 10001, first, second,
        (id, state, event) -> { if (state == GestureMatcher.STATE_GESTURE_COMPLETED) completed.add(id); }, event -> {});
  }

  @Test public void recognizesAllTwelveDirectionPairsOnlyAfterBothFingersLift() {
    for (int first = 0; first < 4; first++) {
      for (int second = 0; second < 4; second++) {
        if (first == second) continue;
        create(first, second);
        completed.clear();
        begin();
        float x = 400, y = 400;
        for (int step = 1; step <= 5; step++) move(x + dx(first) * step * 25, y + dy(first) * step * 25);
        x += dx(first) * 125; y += dy(first) * 125;
        for (int step = 1; step <= 5; step++) move(x + dx(second) * step * 25, y + dy(second) * step * 25);
        x += dx(second) * 125; y += dy(second) * 125;
        assertTrue(completed.isEmpty());
        event(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2},
            new float[] {x, x + 90}, new float[] {y, y});
        assertTrue(completed.isEmpty());
        event(MotionEvent.ACTION_UP, new int[] {7}, new float[] {x}, new float[] {y});
        assertEquals("pair " + first + ":" + second, List.of(10001), completed);
      }
    }
  }

  @Test public void ignoresStraightSwipesAndShortTurns() {
    create(0, 1); begin(); move(400, 275); move(400, 285); finish(400, 285);
    assertTrue(completed.isEmpty());
    create(0, 2); begin(); move(400, 275); finish(400, 275);
    assertTrue(completed.isEmpty());
  }

  @Test public void rejectsPinchRotationThirdFingerAndCancelledInput() {
    create(0, 2); begin();
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {300, 590}, new float[] {300, 300});
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
    create(0, 2); begin();
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {450, 450}, new float[] {355, 445});
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
    create(0, 2); begin();
    event(MotionEvent.ACTION_POINTER_DOWN | (2 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2, 4},
        new float[] {400, 490, 550}, new float[] {400, 400, 400});
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
    create(0, 2); begin();
    event(MotionEvent.ACTION_CANCEL, new int[] {7, 2}, new float[] {400, 490}, new float[] {400, 400});
    assertTrue(completed.isEmpty());
  }

  @Test public void rejectsFingersFollowingDifferentDirections() {
    create(0, 2); begin();
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {400, 490}, new float[] {275, 525});
    finish(400, 275);
    assertTrue(completed.isEmpty());
  }

  @Test public void rejectsASecondFingerAddedAfterExplorationAndLongReleaseGaps() {
    create(0, 2);
    event(MotionEvent.ACTION_DOWN, new int[] {7}, new float[] {400}, new float[] {400});
    time += 400;
    event(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2},
        new float[] {400, 490}, new float[] {400, 400});
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, matcher.getState());
    create(0, 2); begin(); move(400, 275); move(275, 275);
    event(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2},
        new float[] {275, 365}, new float[] {275, 275});
    time += 300;
    event(MotionEvent.ACTION_UP, new int[] {7}, new float[] {275}, new float[] {275});
    assertTrue(completed.isEmpty());
  }

  private void begin() {
    event(MotionEvent.ACTION_DOWN, new int[] {7}, new float[] {400}, new float[] {400});
    event(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2},
        new float[] {400, 490}, new float[] {400, 400});
  }
  private void move(float x, float y) {
    event(MotionEvent.ACTION_MOVE, new int[] {7, 2}, new float[] {x, x + 90}, new float[] {y, y});
  }
  private void finish(float x, float y) {
    event(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[] {7, 2},
        new float[] {x, x + 90}, new float[] {y, y});
    event(MotionEvent.ACTION_UP, new int[] {7}, new float[] {x}, new float[] {y});
  }
  private void event(int action, int[] ids, float[] xs, float[] ys) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
    for (int index = 0; index < ids.length; index++) {
      properties[index] = new MotionEvent.PointerProperties();
      properties[index].id = ids[index];
      properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[index] = new MotionEvent.PointerCoords();
      coordinates[index].x = xs[index]; coordinates[index].y = ys[index]; coordinates[index].pressure = 1;
    }
    MotionEvent event = MotionEvent.obtain(100, time += 30, action, ids.length, properties, coordinates,
        0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    try { matcher.onMotionEvent(Performance.EVENT_ID_UNTRACKED, event); } finally { event.recycle(); }
  }
  private int dx(int direction) { return direction == 2 ? -1 : direction == 3 ? 1 : 0; }
  private int dy(int direction) { return direction == 0 ? -1 : direction == 1 ? 1 : 0; }
}
