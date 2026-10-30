package com.google.android.accessibility.talkback.gesture.extended;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.TouchInteractionController;
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

  @Test public void stoppedBackendCannotDispatchAnotherGesture() {
    backend.stop();
    backend.onGestureCompleted(new AccessibilityGestureEvent(
      AccessibilityService.GESTURE_SWIPE_UP, Display.DEFAULT_DISPLAY, List.of()));
    assertTrue(service.gestures.isEmpty());
  }

  private long time = 100;
  private void motion(int action, float y) {
    MotionEvent event = MotionEvent.obtain(100, time += 20, action, 400, y, 0);
    try { touch.callback.onMotionEvent(event); } finally { event.recycle(); }
  }
}
