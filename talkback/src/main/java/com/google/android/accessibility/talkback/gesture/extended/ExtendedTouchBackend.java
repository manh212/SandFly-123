package com.google.android.accessibility.talkback.gesture.extended;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.TouchInteractionController;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.gesture.TwoFingerRotationTracker;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.gestures.GestureManifold;
import com.google.android.accessibility.utils.gestures.TwoFingerDirectionalGesture;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
final class ExtendedTouchBackend implements TouchInteractionController.Callback, GestureManifold.Listener {
  private final TalkBackService service;
  private final TouchInteractionController controller;
  private final GestureManifold detector;
  private final Handler handler;
  private final Runnable failure;
  private final boolean rotationEnabled;
  private final boolean extendedEnabled;
  private final TwoFingerRotationTracker rotationTracker;
  private final ArrayDeque<MotionEvent> queued = new ArrayDeque<>();
  private final float[] downY = new float[32];
  private final float[] downX = new float[32];
  private boolean completedInteraction;
  private boolean active;
  private boolean transitionPending;
  private boolean gestureStarted;
  private int state = TouchInteractionController.STATE_CLEAR;
  private final Runnable explore = () -> {
    if (active && !gestureStarted && controllerState() == TouchInteractionController.STATE_TOUCH_INTERACTING) {
      requestExploration();
    }
  };

  ExtendedTouchBackend(TalkBackService service, Runnable failure) {
    this(service, failure, false, true);
  }

  ExtendedTouchBackend(
      TalkBackService service, Runnable failure, boolean rotationEnabled, boolean extendedEnabled) {
    this.service = service;
    this.failure = failure;
    this.rotationEnabled = rotationEnabled;
    this.extendedEnabled = extendedEnabled;
    float touchSlop = ViewConfiguration.get(service).getScaledTouchSlop();
    rotationTracker =
        new TwoFingerRotationTracker(
            touchSlop * 2,
            touchSlop,
            clockwise ->
                service.performCatalogAction(
                    service.getString(
                        clockwise
                            ? R.string.shortcut_value_select_next_setting
                            : R.string.shortcut_value_select_previous_setting)));
    handler = new Handler(service.getMainLooper());
    controller = service.getTouchInteractionController(Display.DEFAULT_DISPLAY);
    if (controller == null) throw new IllegalStateException("No touch controller");
    detector = new GestureManifold(service, this, new GestureManifold.GestureConfigProvider() {
      @Override public boolean getSpeedUpTouchExploreState() { return true; }
    }, event -> {}, Display.DEFAULT_DISPLAY,
        ImmutableList.copyOf(service.getResources().getStringArray(R.array.extended_standard_gestures)));
    detector.setMultiFingerGesturesEnabled(true);
    detector.setTwoFingerPassthroughEnabled(false);
    if (extendedEnabled) {
      for (int id = ExtendedGestures.FIRST_ID; id <= ExtendedGestures.LAST_ID; id++) {
        detector.addGestureMatcher(new TwoFingerDirectionalGesture(service, id,
            ExtendedGestures.first(id), ExtendedGestures.second(id), detector, event -> {}));
      }
    }
  }

  void start() {
    active = true;
    try {
      controller.registerCallback(Runnable::run, this);
    } catch (RuntimeException error) {
      active = false;
      try { controller.unregisterCallback(this); } catch (RuntimeException ignored) {}
      throw error;
    }
  }

  void stop() {
    active = false;
    handler.removeCallbacks(explore);
    detector.clear();
    rotationTracker.clear();
    clearQueue();
    try { controller.unregisterCallback(this); } catch (RuntimeException ignored) {}
  }

  void configurationChanged(Configuration configuration) {
    if (active) detector.onConfigurationChanged(service);
  }

  @Override public void onMotionEvent(MotionEvent event) {
    if (!active) return;
    if (transitionPending) {
      if (queued.size() >= 128) { fail(); return; }
      queued.add(MotionEvent.obtain(event));
      return;
    }
    try {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
        if (completedInteraction || state == TouchInteractionController.STATE_CLEAR) {
          detector.clear();
          gestureStarted = completedInteraction = false;
        }
        downY[event.getPointerId(0)] = event.getY();
        downX[event.getPointerId(0)] = event.getX();
        if (!gestureStarted) {
          handler.removeCallbacks(explore);
          handler.postDelayed(explore, Math.max(150, ViewConfiguration.getDoubleTapTimeout() - 50));
        }
      } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
        handler.removeCallbacks(explore);
        int index = event.getActionIndex();
        downY[event.getPointerId(index)] = event.getY(index);
        downX[event.getPointerId(index)] = event.getX(index);
        if (state == TouchInteractionController.STATE_TOUCH_EXPLORING) detector.clear();
      }
      if (rotationEnabled
          && state != TouchInteractionController.STATE_DELEGATING
          && state != TouchInteractionController.STATE_DRAGGING
          && (rotationTracker.isRotating()
              || state != TouchInteractionController.STATE_TOUCH_EXPLORING)) {
        boolean wasRotating = rotationTracker.isRotating();
        if (rotationTracker.onMotionEvent(event)) {
          handler.removeCallbacks(explore);
          if (!wasRotating) {
            detector.clear();
            gestureStarted = true;
          }
          if (event.getActionMasked() == MotionEvent.ACTION_UP
              || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            completedInteraction = true;
            gestureStarted = false;
          }
          return;
        }
        if (!extendedEnabled
            && rotationTracker.isScroll()
            && state == TouchInteractionController.STATE_TOUCH_INTERACTING) {
          delegate();
          return;
        }
      }
      if (event.getPointerCount() == 2
          && event.getActionMasked() == MotionEvent.ACTION_MOVE
          && !(rotationEnabled && rotationTracker.isPossibleRotation())
          && distorted(event)) {
        delegate();
        return;
      }
      if (event.getPointerCount() == 3 && bottomEdge(event)) {
        delegate();
        return;
      }
      if (state == TouchInteractionController.STATE_TOUCH_INTERACTING ||
          state == TouchInteractionController.STATE_TOUCH_EXPLORING || state == TouchInteractionController.STATE_CLEAR) {
        detector.onMotionEvent(Performance.EVENT_ID_UNTRACKED, event);
      }
      if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        detector.clear();
        rotationTracker.clear();
        gestureStarted = false;
        handler.removeCallbacks(explore);
      }
    } catch (RuntimeException error) {
      fail();
    }
  }

  @Override public void onStateChanged(int next) {
    if (!active) return;
    if (state == TouchInteractionController.STATE_CLEAR && next != state) {
      detector.clear();
      gestureStarted = completedInteraction = false;
    }
    state = next;
    transitionPending = false;
    handler.removeCallbacks(explore);
    if (next == TouchInteractionController.STATE_DELEGATING
        || next == TouchInteractionController.STATE_DRAGGING) {
      detector.clear();
      rotationTracker.clear();
      clearQueue();
      return;
    }
    while (active && !transitionPending && !queued.isEmpty()) {
      MotionEvent event = queued.removeFirst();
      try { onMotionEvent(event); } finally { event.recycle(); }
    }
  }

  @Override public void onGestureStarted(int gestureId) {
    gestureStarted = true;
    handler.removeCallbacks(explore);
  }

  @Override public void onGestureCancelled(int gestureId) {}

  @Override public void onGestureCompleted(AccessibilityGestureEvent event) {
    try { completeGesture(event); } catch (RuntimeException error) { fail(); }
  }

  private void completeGesture(AccessibilityGestureEvent event) {
    if (!active || completedInteraction || state == TouchInteractionController.STATE_DELEGATING || state == TouchInteractionController.STATE_DRAGGING) return;
    int id = event.getGestureId();
    handler.removeCallbacks(explore);
    if (id == GestureManifold.GESTURE_TOUCH_EXPLORE || id == GestureManifold.GESTURE_TAP_UP_TOUCH_EXPLORE) {
      requestExploration();
      return;
    }
    completedInteraction = true;
    if (id == AccessibilityService.GESTURE_DOUBLE_TAP) {
      AccessibilityServiceInfo info = service.getServiceInfo();
      if (info != null && (info.flags & AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP) != 0) dispatch(event);
      else controller.performClick();
    } else if (id == AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD) {
      controller.performLongClickAndStartDrag();
    } else if (id == GestureManifold.GESTURE_FAKED_SPLIT_TYPING) {
      service.performCatalogAction(service.getString(R.string.shortcut_value_split_typing));
    } else {
      dispatch(event);
    }
    gestureStarted = false;
  }

  private void dispatch(AccessibilityGestureEvent event) {
    if (active && !com.google.android.accessibility.talkback.pause.PauseController.isPaused()) {
      service.onGesture(event);
    }
  }

  private int controllerState() { return controller.getState(); }

  private void requestExploration() {
    if (!active || transitionPending) return;
    int current = controllerState();
    if (current == TouchInteractionController.STATE_DELEGATING || current == TouchInteractionController.STATE_TOUCH_EXPLORING) return;
    transitionPending = true;
    try { controller.requestTouchExploration(); } catch (RuntimeException error) { fail(); }
  }

  private void delegate() {
    if (!active || transitionPending || controllerState() != TouchInteractionController.STATE_TOUCH_INTERACTING) return;
    transitionPending = true;
    try { controller.requestDelegating(); } catch (RuntimeException error) { fail(); }
  }

  private boolean distorted(MotionEvent event) {
    int a = event.getPointerId(0), b = event.getPointerId(1);
    float dx = downX[b] - downX[a], dy = downY[b] - downY[a];
    float currentX = event.getX(1) - event.getX(0), currentY = event.getY(1) - event.getY(0);
    float initial = (float) Math.hypot(dx, dy), current = (float) Math.hypot(currentX, currentY);
    float margin = service.getResources().getDisplayMetrics().density * 30f;
    return Math.abs(current - initial) > Math.max(margin, initial * .5f) ||
        (initial > margin && current > margin && (dx * currentX + dy * currentY) / (initial * current) < .75f);
  }

  private boolean bottomEdge(MotionEvent event) {
    float edge = service.getResources().getDisplayMetrics().heightPixels -
        service.getResources().getDisplayMetrics().ydpi / 2.54f;
    for (int index = 0; index < event.getPointerCount(); index++) {
      int id = event.getPointerId(index);
      if (id < 0 || id >= downY.length || downY[id] < edge) return false;
    }
    return true;
  }

  private void clearQueue() { while (!queued.isEmpty()) queued.removeFirst().recycle(); }

  private void fail() {
    if (!active) return;
    stop();
    handler.post(failure);
  }
}
