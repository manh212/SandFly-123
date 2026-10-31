package com.google.android.accessibility.talkback.gesture.extended;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Display;
import android.view.MotionEvent;
import com.google.android.accessibility.talkback.TalkBackService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi", shadows = ExtendedTouchBackendTest.TouchShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ExtendedTouchBackendTest {
  public static class TestService extends TalkBackService {
    final List<Integer> gestures = new ArrayList<>();
    final List<String> actions = new ArrayList<>();
    final List<Path> scrolls = new ArrayList<>();
    final List<Path> secondScrolls = new ArrayList<>();
    boolean replayScrolls = true;
    @Override public boolean performTwoFingerScroll(Path path, long duration) {
      scrolls.add(path);
      return true;
    }
    @Override public boolean performTwoFingerScroll(Path firstPath, Path secondPath, long duration) {
      scrolls.add(firstPath);
      secondScrolls.add(secondPath);
      return replayScrolls;
    }
    @Override public void performCatalogAction(String action) { actions.add(action); }
    @Override public void onCreate() {}
    @Override public boolean onGesture(AccessibilityGestureEvent event) {
      gestures.add(event.getGestureId());
      return true;
    }
  }

  @Implements(TouchInteractionController.class)
  public static class TouchShadow {
    Executor executor;
    TouchInteractionController.Callback callback;
    int state = TouchInteractionController.STATE_TOUCH_INTERACTING;
    @Implementation protected void registerCallback(Executor executor, TouchInteractionController.Callback callback) {
      this.executor = executor;
      this.callback = callback;
    }
    @Implementation protected boolean unregisterCallback(TouchInteractionController.Callback callback) {
      this.callback = null;
      return true;
    }
    @Implementation protected int getState() { return state; }
  }

  private TestService service;
  private ExtendedTouchBackend backend;
  private TouchShadow touch;

  @Before public void setUp() {
    service = Robolectric.buildService(TestService.class).create().get();
    touch = Shadow.extract(service.getTouchInteractionController(Display.DEFAULT_DISPLAY));
    backend = new ExtendedTouchBackend(service, () -> { throw new AssertionError("Gesture backend failed"); });
    backend.start();
  }

  @After public void tearDown() { backend.stop(); }

  @Test public void callbackExecutorDoesNotPostAnotherMainThreadTask() {
    assertNotNull(touch.executor);
    List<Integer> calls = new ArrayList<>();
    touch.executor.execute(() -> calls.add(1));
    assertEquals(List.of(1), calls);
  }

  @Test public void oneFingerSwipeDispatchesWhenTheFingerLiftsWithoutAnExtraQueue() {
    motion(MotionEvent.ACTION_DOWN, 700);
    for (int step = 1; step <= 6; step++) motion(MotionEvent.ACTION_MOVE, 700 - step * 30);
    assertTrue(service.gestures.isEmpty());
    motion(MotionEvent.ACTION_UP, 520);
    assertEquals(List.of(AccessibilityService.GESTURE_SWIPE_UP), service.gestures);
  }

  @Test public void rotationDispatchesNextReadingControlWhenEnabled() {
    backend.stop();
    backend = new ExtendedTouchBackend(
        service, () -> { throw new AssertionError("Rotation backend failed"); }, true, false);
    backend.start();
    touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
    multiMotion(MotionEvent.ACTION_DOWN, 0, 1);
    multiMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0, 2);
    multiMotion(MotionEvent.ACTION_MOVE, 25, 2);
    assertEquals(
        List.of(service.getString(com.google.android.accessibility.talkback.R.string.shortcut_value_select_next_setting)),
        service.actions);
  }

  private void multiMotion(int action, double degrees, int count) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    double radians = Math.toRadians(degrees);
    float dx = (float) (50 * Math.cos(radians));
    float dy = (float) (50 * Math.sin(radians));
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = i == 0 ? 150 - dx : 150 + dx;
      coords[i].y = i == 0 ? 100 - dy : 100 + dy;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent event = MotionEvent.obtain(
        100, time += 20, action, count, properties, coords, 0, 0, 1, 1, 0, 0, 0, 0);
    try {
      touch.callback.onMotionEvent(event);
    } finally {
      event.recycle();
    }
  }

  @Test public void stoppedBackendCannotDispatchAnotherGesture() {
    backend.stop();
    backend.onGestureCompleted(new AccessibilityGestureEvent(
      AccessibilityService.GESTURE_SWIPE_UP, Display.DEFAULT_DISPLAY, List.of()));
    assertTrue(service.gestures.isEmpty());
  }

  @Test public void fourStraightTwoFingerSwipesReplayTheOriginalDrag() {
    int[] ids = {
      AccessibilityService.GESTURE_2_FINGER_SWIPE_UP,
      AccessibilityService.GESTURE_2_FINGER_SWIPE_DOWN,
      AccessibilityService.GESTURE_2_FINGER_SWIPE_LEFT,
      AccessibilityService.GESTURE_2_FINGER_SWIPE_RIGHT
    };
    float[][] ends = {{400, 300}, {400, 700}, {200, 500}, {600, 500}};
    for (int i = 0; i < ids.length; i++) {
      touch.callback.onStateChanged(TouchInteractionController.STATE_CLEAR);
      touch.callback.onStateChanged(TouchInteractionController.STATE_TOUCH_INTERACTING);
      dragMotion(MotionEvent.ACTION_DOWN, 1, 400, 500);
      dragMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 500);
      dragMotion(MotionEvent.ACTION_MOVE, 2, ends[i][0], ends[i][1]);
      backend.onGestureCompleted(new AccessibilityGestureEvent(ids[i], Display.DEFAULT_DISPLAY, List.of()));
      assertEquals(i + 1, service.scrolls.size());
      assertEquals(i + 1, service.secondScrolls.size());
      RectF firstBounds = new RectF();
      service.scrolls.get(i).computeBounds(firstBounds, true);
      assertEquals(Math.min(400, ends[i][0]), firstBounds.left, .01f);
      assertEquals(Math.max(400, ends[i][0]), firstBounds.right, .01f);
      assertEquals(Math.min(500, ends[i][1]), firstBounds.top, .01f);
      assertEquals(Math.max(500, ends[i][1]), firstBounds.bottom, .01f);
      RectF secondBounds = new RectF();
      service.secondScrolls.get(i).computeBounds(secondBounds, true);
      assertEquals(Math.min(460, ends[i][0] + 60), secondBounds.left, .01f);
      assertEquals(Math.max(460, ends[i][0] + 60), secondBounds.right, .01f);
      assertEquals(Math.min(500, ends[i][1]), secondBounds.top, .01f);
      assertEquals(Math.max(500, ends[i][1]), secondBounds.bottom, .01f);
    }
    assertTrue(service.gestures.isEmpty());
  }

  @Test public void standardTwoFingerSwipeDoesNotFallBackToOneFingerGesture() {
    service.replayScrolls = false;
    dragMotion(MotionEvent.ACTION_DOWN, 1, 400, 500);
    dragMotion(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        2,
        400,
        500);
    dragMotion(MotionEvent.ACTION_MOVE, 2, 400, 300);
    backend.onGestureCompleted(
        new AccessibilityGestureEvent(
            AccessibilityService.GESTURE_2_FINGER_SWIPE_UP, Display.DEFAULT_DISPLAY, List.of()));
    assertEquals(1, service.scrolls.size());
    assertTrue(service.gestures.isEmpty());
  }

  @Test public void extendedGestureKeepsItsAssignedAction() {
    dragMotion(MotionEvent.ACTION_DOWN, 1, 400, 500);
    dragMotion(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400, 500);
    backend.onGestureCompleted(new AccessibilityGestureEvent(ExtendedGestures.FIRST_ID, Display.DEFAULT_DISPLAY, List.of()));
    assertEquals(List.of(ExtendedGestures.FIRST_ID), service.gestures);
    assertTrue(service.scrolls.isEmpty());
  }

  private void dragMotion(int action, int count, float x, float y) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = x + i * 60;
      coords[i].y = y;
      coords[i].pressure = 1;
      coords[i].size = 1;
    }
    MotionEvent event = MotionEvent.obtain(100, time += 20, action, count, properties, coords,
        0, 0, 1, 1, 0, 0, 0, 0);
    try { touch.callback.onMotionEvent(event); } finally { event.recycle(); }
  }

  private long time = 100;
  private void motion(int action, float y) {
    MotionEvent event = MotionEvent.obtain(100, time += 20, action, 400, y, 0);
    try { touch.callback.onMotionEvent(event); } finally { event.recycle(); }
  }
}
