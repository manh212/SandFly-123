package com.google.android.accessibility.talkback.gesture;

import android.content.SharedPreferences;
import android.os.Build;

public final class TwoFingerRotationSettings {
  public static final String ENABLED = "pref_two_finger_rotation";

  private TwoFingerRotationSettings() {}

  public static boolean isEnabled(SharedPreferences preferences) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && preferences.getBoolean(ENABLED, false);
  }
}
