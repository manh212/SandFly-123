package com.google.android.accessibility.talkback.volume;

import android.view.accessibility.AccessibilityEvent;

public final class TouchVolumeState {
  private boolean interacting;
  private boolean exploring;

  public void onEvent(int eventType) {
    switch (eventType) {
      case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> interacting = true;
      case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> reset();
      case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> exploring = true;
      case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> exploring = false;
      default -> {}
    }
  }

  public boolean isTouching() {
    return interacting || exploring;
  }

  public void reset() {
    interacting = false;
    exploring = false;
  }
}
