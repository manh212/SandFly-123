package com.google.android.accessibility.talkback.gesture.extended;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.google.android.accessibility.talkback.R;

public final class ExtendedGestures {
  public static final String ENABLED = "pref_extended_two_finger_gestures";
  public static final int FIRST_ID = 10001;
  public static final int LAST_ID = 10012;
  private static final int[][] ITEMS = {
    {10001, 0, 1, R.string.pref_extended_gesture_up_down_key, R.string.extended_gesture_up_down},
    {10002, 1, 0, R.string.pref_extended_gesture_down_up_key, R.string.extended_gesture_down_up},
    {10003, 2, 3, R.string.pref_extended_gesture_left_right_key, R.string.extended_gesture_left_right},
    {10004, 3, 2, R.string.pref_extended_gesture_right_left_key, R.string.extended_gesture_right_left},
    {10005, 0, 2, R.string.pref_extended_gesture_up_left_key, R.string.extended_gesture_up_left},
    {10006, 0, 3, R.string.pref_extended_gesture_up_right_key, R.string.extended_gesture_up_right},
    {10007, 1, 2, R.string.pref_extended_gesture_down_left_key, R.string.extended_gesture_down_left},
    {10008, 1, 3, R.string.pref_extended_gesture_down_right_key, R.string.extended_gesture_down_right},
    {10009, 2, 0, R.string.pref_extended_gesture_left_up_key, R.string.extended_gesture_left_up},
    {10010, 2, 1, R.string.pref_extended_gesture_left_down_key, R.string.extended_gesture_left_down},
    {10011, 3, 0, R.string.pref_extended_gesture_right_up_key, R.string.extended_gesture_right_up},
    {10012, 3, 1, R.string.pref_extended_gesture_right_down_key, R.string.extended_gesture_right_down},
  };
  private ExtendedGestures() {}
  public static boolean isEnabled(SharedPreferences prefs) {
    return Build.VERSION.SDK_INT >= 33 && prefs.getBoolean(ENABLED, false);
  }
  public static boolean contains(int id) { return id >= FIRST_ID && id <= LAST_ID; }
  public static int first(int id) { return ITEMS[id - FIRST_ID][1]; }
  public static int second(int id) { return ITEMS[id - FIRST_ID][2]; }
  public static int key(int id) { return ITEMS[id - FIRST_ID][3]; }
  public static String name(Context context, int id) { return context.getString(ITEMS[id - FIRST_ID][4]); }
}
