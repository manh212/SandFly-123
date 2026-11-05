package com.Braille;

import android.content.SharedPreferences;
import java.util.Map;

public final class BrailleSpeedSettings {
    public static final int MIN_LETTER_DELAY_MS = 1;
    public static final int MAX_LETTER_DELAY_MS = 300;
    public static final int DEFAULT_LETTER_DELAY_MS = 50;
    public static final int MIN_GESTURE_DELAY_MS = 100;
    public static final int MAX_GESTURE_DELAY_MS = 5000;
    public static final int DEFAULT_GESTURE_DELAY_MS = 1000;
    public static final int MIN_SWIPE_DISTANCE_DP = 24;
    public static final int MAX_SWIPE_DISTANCE_DP = 120;
    public static final int DEFAULT_SWIPE_DISTANCE_DP = 68;
    public static final int MIN_LONG_SWIPE_DISTANCE_DP = 130;
    public static final int MAX_LONG_SWIPE_DISTANCE_DP = 300;
    public static final int DEFAULT_LONG_SWIPE_DISTANCE_DP = 160;
    public static final String KEY_LETTER_DELAY = "letter_delay_ms";
    public static final String KEY_GESTURE_DELAY = "gesture_delay_ms";
    public static final String KEY_SWIPE_DISTANCE = "swipe_distance_dp";
    public static final String KEY_LONG_SWIPE_DISTANCE = "long_swipe_distance_dp";
    public static final String KEY_DISTINGUISH_SWIPE_LENGTH = "distinguish_swipe_length";

    private BrailleSpeedSettings() {}

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int letterDelay(SharedPreferences prefs) {
        return clamp(prefs.getInt(KEY_LETTER_DELAY, DEFAULT_LETTER_DELAY_MS),
                MIN_LETTER_DELAY_MS, MAX_LETTER_DELAY_MS);
    }

    public static int gestureDelay(SharedPreferences prefs) {
        return clamp(prefs.getInt(KEY_GESTURE_DELAY, DEFAULT_GESTURE_DELAY_MS),
                MIN_GESTURE_DELAY_MS, MAX_GESTURE_DELAY_MS);
    }

    public static int swipeDistance(SharedPreferences prefs) {
        return clamp(prefs.getInt(KEY_SWIPE_DISTANCE, DEFAULT_SWIPE_DISTANCE_DP),
                MIN_SWIPE_DISTANCE_DP, MAX_SWIPE_DISTANCE_DP);
    }

    public static int longSwipeDistance(SharedPreferences prefs) {
        return clamp(prefs.getInt(KEY_LONG_SWIPE_DISTANCE, DEFAULT_LONG_SWIPE_DISTANCE_DP),
                MIN_LONG_SWIPE_DISTANCE_DP, MAX_LONG_SWIPE_DISTANCE_DP);
    }

    public static boolean isSwipe(float dx, float dy, long elapsed,
            long timeLimit, float minimumDistance) {
        return elapsed >= 0 && elapsed < timeLimit
                && Math.max(Math.abs(dx), Math.abs(dy)) > minimumDistance;
    }

    public static boolean isLongSwipe(float dx, float dy, float minimumDistance) {
        return Math.max(Math.abs(dx), Math.abs(dy)) >= minimumDistance;
    }

    public static String swipeAction(Map<String, String> assignments, String key,
            boolean distinguish, boolean longSwipe) {
        if (distinguish && longSwipe) {
            String action = assignments.get(key + "_LONG");
            if (action != null) return action;
        }
        return assignments.get(key);
    }
}
