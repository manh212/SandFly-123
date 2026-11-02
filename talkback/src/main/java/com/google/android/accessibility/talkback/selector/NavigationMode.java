package com.google.android.accessibility.talkback.selector;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

public final class NavigationMode {
  public static final String PREF_KEY = "pref_navigation_mode";
  private static final String MODE_ONE = "1";
  private static final String MODE_TWO = "2";
  private static final String MODE_ONE_UP_DOWN = "pref_navigation_mode_one_up_down";
  private static final String MODE_TWO_UP_DOWN = "pref_navigation_mode_two_up_down";

  public enum SwipeAction {
    NONE,
    SELECT_PREVIOUS,
    SELECT_NEXT,
    ADJUST_PREVIOUS,
    ADJUST_NEXT
  }

  private NavigationMode() {}

  public static boolean isModeTwo(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    return MODE_TWO.equals(prefs.getString(PREF_KEY, MODE_ONE));
  }

  public static SwipeAction swipeAction(int gestureId) {
    return switch (gestureId) {
      case AccessibilityService.GESTURE_SWIPE_UP -> SwipeAction.SELECT_PREVIOUS;
      case AccessibilityService.GESTURE_SWIPE_DOWN -> SwipeAction.SELECT_NEXT;
      case AccessibilityService.GESTURE_SWIPE_LEFT -> SwipeAction.ADJUST_PREVIOUS;
      case AccessibilityService.GESTURE_SWIPE_RIGHT -> SwipeAction.ADJUST_NEXT;
      default -> SwipeAction.NONE;
    };
  }

  public static void setMode(Context context, String selected) {
    String next = MODE_TWO.equals(selected) ? MODE_TWO : MODE_ONE;
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    String current = prefs.getString(PREF_KEY, MODE_ONE);
    if (current.equals(next)) {
      return;
    }

    String gestureKey = context.getString(R.string.pref_shortcut_up_and_down_key);
    String originalDefault = context.getString(R.string.pref_shortcut_up_and_down_default);
    String unassigned = context.getString(R.string.shortcut_value_unassigned);
    String activeAction = prefs.getString(gestureKey, originalDefault);
    String saveKey = MODE_TWO.equals(current) ? MODE_TWO_UP_DOWN : MODE_ONE_UP_DOWN;
    String restoreKey = MODE_TWO.equals(next) ? MODE_TWO_UP_DOWN : MODE_ONE_UP_DOWN;
    String restoredAction = prefs.getString(
        restoreKey, MODE_TWO.equals(next) ? unassigned : originalDefault);

    prefs.edit()
        .putString(saveKey, activeAction)
        .putString(gestureKey, restoredAction)
        .putString(PREF_KEY, next)
        .apply();
  }
}
