package com.google.android.accessibility.utils.gestures;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;
import com.google.android.accessibility.utils.Performance;
import com.google.common.collect.ImmutableList;
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
public class GestureManifoldCompatibilityTest {
  private GestureManifold detector;
  private final List<Integer> recognized = new ArrayList<>();
  private long time;

  @Before public void setUp() {
    Context context = RuntimeEnvironment.getApplication();
    detector = new GestureManifold(context, new GestureManifold.Listener() {
      @Override public void onGestureStarted(int id) {}
      @Override public void onGestureCancelled(int id) {}
      @Override public void onGestureCompleted(AccessibilityGestureEvent event) { recognized.add(event.getGestureId()); }
    }, new GestureManifold.GestureConfigProvider() {}, event -> {}, 0,
        ImmutableList.of("MAPPER_GESTURE_SWIPE_UP", "MAPPER_GESTURE_2_FINGER_SWIPE_UP"));
    detector.setMultiFingerGesturesEnabled(true);
    detector.addGestureMatcher(new TwoFingerDirectionalGesture(context, 10001, 0, 1, detector, event -> {}));
    time = 100;
  }

  @Test public void oneFingerSwipeStillUsesTheExistingGestureId() {
    send(MotionEvent.ACTION_DOWN, 1, 400, 700);
    for (int step = 1; step <= 6; step++) send(MotionEvent.ACTION_MOVE, 1, 400, 700 - step * 30);
    send(MotionEvent.ACTION_UP, 1, 400, 520);
    assertEquals(List.of(AccessibilityService.GESTURE_SWIPE_UP), recognized);
  }

  @Test public void straightTwoFingerSwipeDoesNotBecomeAnExtendedGesture() {
    send(MotionEvent.ACTION_DOWN, 1, 400, 700);
    send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 700);
    for (int step = 1; step <= 6; step++) send(MotionEvent.ACTION_MOVE, 2, 400, 700 - step * 30);
    send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 520);
    send(MotionEvent.ACTION_UP, 1, 400, 520);
    assertEquals(List.of(AccessibilityService.GESTURE_2_FINGER_SWIPE_UP), recognized);
  }

  @Test public void reversingTwoFingerSwipeProducesOnlyTheExtendedCommand() {
    send(MotionEvent.ACTION_DOWN, 1, 400, 700);
    send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 700);
    for (int step = 1; step <= 6; step++) send(MotionEvent.ACTION_MOVE, 2, 400, 700 - step * 30);
    for (int step = 1; step <= 6; step++) send(MotionEvent.ACTION_MOVE, 2, 400, 520 + step * 30);
    send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 700);
    send(MotionEvent.ACTION_UP, 1, 400, 700);
    assertEquals(List.of(10001), recognized);
  }

  private void send(int action, int count, float x, float y) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    for (int index = 0; index < count; index++) {
      properties[index] = new MotionEvent.PointerProperties(); properties[index].id = index;
      properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[index] = new MotionEvent.PointerCoords();
      coords[index].x = x + index * 100; coords[index].y = y; coords[index].pressure = 1;
    }
    MotionEvent event = MotionEvent.obtain(100, time += 20, action, count, properties, coords,
        0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    try { detector.onMotionEvent(Performance.EVENT_ID_UNTRACKED, event); } finally { event.recycle(); }
  }
}
