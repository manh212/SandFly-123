package com.google.android.accessibility.talkback.gesture;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;

public final class ReverseSwipeSettings {
  public static final String KEY = "pref_reverse_swipe_gestures";

  private ReverseSwipeSettings() {}

  public static boolean isEnabled(SharedPreferences prefs) {
    return prefs.getBoolean(KEY, false);
  }

  public static int mapTouchGesture(int gestureId, boolean enabled) {
    if (!enabled) {
      return gestureId;
    }
    return switch (gestureId) {
      case AccessibilityService.GESTURE_SWIPE_UP ->
          AccessibilityService.GESTURE_SWIPE_LEFT;
      case AccessibilityService.GESTURE_SWIPE_LEFT ->
          AccessibilityService.GESTURE_SWIPE_UP;
      case AccessibilityService.GESTURE_SWIPE_DOWN ->
          AccessibilityService.GESTURE_SWIPE_RIGHT;
      case AccessibilityService.GESTURE_SWIPE_RIGHT ->
          AccessibilityService.GESTURE_SWIPE_DOWN;
      case AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN ->
          AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT;
      case AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT ->
          AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN;
      case AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP ->
          AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT;
      case AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT ->
          AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP;
      case AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT ->
          AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP;
      case AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP ->
          AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT;
      case AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT ->
          AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN;
      case AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN ->
          AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT;
      case AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT ->
          AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP;
      case AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP ->
          AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT;
      case AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT ->
          AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN;
      case AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN ->
          AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT;
      default -> gestureId;
    };
  }

  public static boolean isOneFingerSwipe(int gestureId) {
    return mapTouchGesture(gestureId, true) != gestureId;
  }
}
