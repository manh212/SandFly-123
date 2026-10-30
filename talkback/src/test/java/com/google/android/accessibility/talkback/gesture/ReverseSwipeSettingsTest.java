package com.google.android.accessibility.talkback.gesture;

import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en-rUS-ldltr")
public class ReverseSwipeSettingsTest {
  private static final int[][] PAIRS = {
    {GESTURE_SWIPE_UP, GESTURE_SWIPE_LEFT},
    {GESTURE_SWIPE_DOWN, GESTURE_SWIPE_RIGHT},
    {GESTURE_SWIPE_UP_AND_DOWN, GESTURE_SWIPE_LEFT_AND_RIGHT},
    {GESTURE_SWIPE_DOWN_AND_UP, GESTURE_SWIPE_RIGHT_AND_LEFT},
    {GESTURE_SWIPE_UP_AND_LEFT, GESTURE_SWIPE_LEFT_AND_UP},
    {GESTURE_SWIPE_UP_AND_RIGHT, GESTURE_SWIPE_LEFT_AND_DOWN},
    {GESTURE_SWIPE_DOWN_AND_LEFT, GESTURE_SWIPE_RIGHT_AND_UP},
    {GESTURE_SWIPE_DOWN_AND_RIGHT, GESTURE_SWIPE_RIGHT_AND_DOWN},
  };

  private Context context;
  private SharedPreferences prefs;
  private GestureShortcutMapping mapping;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    mapping = new GestureShortcutMapping(context);
  }

  @After public void tearDown() { mapping.onUnbind(); }

  @Test public void isOffByDefaultAndUsesTheSettingsKey() {
    assertFalse(ReverseSwipeSettings.isEnabled(prefs));
    assertEquals(ReverseSwipeSettings.KEY, context.getString(R.string.pref_reverse_swipe_gestures_key));
    for (int[] pair : PAIRS) {
      for (int id : pair) assertEquals(id, ReverseSwipeSettings.mapTouchGesture(id, false));
    }
  }

  @Test public void exchangesEveryDirectionInAllSixteenOneFingerSwipes() {
    Set<Integer> seen = new HashSet<>();
    for (int[] pair : PAIRS) {
      assertEquals(pair[1], ReverseSwipeSettings.mapTouchGesture(pair[0], true));
      assertEquals(pair[0], ReverseSwipeSettings.mapTouchGesture(pair[1], true));
      for (int id : pair) {
        assertTrue(seen.add(id));
        assertTrue(ReverseSwipeSettings.isOneFingerSwipe(id));
        assertEquals(id, ReverseSwipeSettings.mapTouchGesture(
            ReverseSwipeSettings.mapTouchGesture(id, true), true));
      }
    }
    assertEquals(16, seen.size());
  }

  @Test public void doesNotChangeMultiFingerSwipesTapsOrExtendedGestures() {
    int[] other = {GESTURE_DOUBLE_TAP, GESTURE_DOUBLE_TAP_AND_HOLD,
      GESTURE_2_FINGER_SWIPE_UP, GESTURE_2_FINGER_SWIPE_DOWN,
      GESTURE_2_FINGER_SWIPE_LEFT, GESTURE_2_FINGER_SWIPE_RIGHT,
      GESTURE_3_FINGER_SWIPE_UP, GESTURE_3_FINGER_SWIPE_DOWN,
      GESTURE_3_FINGER_SWIPE_LEFT, GESTURE_3_FINGER_SWIPE_RIGHT,
      GESTURE_4_FINGER_SWIPE_UP, GESTURE_4_FINGER_SWIPE_DOWN,
      GESTURE_4_FINGER_SWIPE_LEFT, GESTURE_4_FINGER_SWIPE_RIGHT,
      GESTURE_2_FINGER_SINGLE_TAP, GESTURE_3_FINGER_SINGLE_TAP,
      GESTURE_4_FINGER_SINGLE_TAP, -1, -2, 0};
    for (int id : other) assertEquals(id, ReverseSwipeSettings.mapTouchGesture(id, true));
    for (int id = 10001; id <= 10012; id++) {
      assertEquals(id, ReverseSwipeSettings.mapTouchGesture(id, true));
    }
    prefs.edit().putBoolean("pref_extended_two_finger_gestures", true)
        .putString(context.getString(R.string.pref_extended_gesture_up_down_key), "two_finger_turn")
        .putString(context.getString(R.string.pref_shortcut_3finger_swipe_right_key), "three_finger_right")
        .commit();
    Map<Integer, String> before = mapping.getGestureActions();
    assertEquals("two_finger_turn", before.get(10001));
    assertEquals("three_finger_right", before.get(GESTURE_3_FINGER_SWIPE_RIGHT));
    prefs.edit().putBoolean(ReverseSwipeSettings.KEY, true).commit();
    Map<Integer, String> after = mapping.getGestureActions();
    before.forEach((id, action) -> {
      if (!ReverseSwipeSettings.isOneFingerSwipe(id)) assertEquals(action, after.get(id));
    });
  }

  @Test public void swapsExistingCommandsAndRestoresThemWithoutRewritingAssignments() {
    prefs.edit().putString(context.getString(R.string.pref_shortcut_left_key), "custom_left")
        .putString(context.getString(R.string.pref_shortcut_right_key), "custom_right")
        .putString(context.getString(R.string.pref_shortcut_up_key), "custom_up")
        .putString(context.getString(R.string.pref_shortcut_down_key), "custom_down").commit();
    Map<String, ?> before = new HashMap<>(prefs.getAll());
    prefs.edit().putBoolean(ReverseSwipeSettings.KEY, true).commit();
    assertEquals("custom_left", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_UP));
    assertEquals("custom_right", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_DOWN));
    assertEquals("custom_up", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_LEFT));
    assertEquals("custom_down", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_RIGHT));
    assertEquals(GESTURE_SWIPE_LEFT, mapping.getActionGestureId(GESTURE_SWIPE_UP));
    prefs.edit().putBoolean(ReverseSwipeSettings.KEY, false).commit();
    assertEquals("custom_left", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_LEFT));
    assertEquals("custom_up", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_UP));
    Map<String, ?> after = new HashMap<>(prefs.getAll());
    after.remove(ReverseSwipeSettings.KEY);
    assertEquals(before, after);
  }

  @Test public void compoundAssignmentsHintsAndSecondGestureSetFollowThePhysicalSwipe() {
    String key = context.getString(R.string.pref_shortcut_up_and_right_key);
    prefs.edit().putString(key, "custom_turn").putString(key + "-1", "second_turn")
        .putBoolean(ReverseSwipeSettings.KEY, true).commit();
    assertEquals("custom_turn", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_LEFT_AND_DOWN));
    String label = GestureShortcutMapping.getGestureString(context, GESTURE_SWIPE_LEFT_AND_DOWN);
    assertEquals(label, mapping.getGestureFromActionKey("custom_turn"));
    assertEquals(label, mapping.getAllGestureTexts().get("custom_turn"));
    assertEquals(label, GestureShortcutMapping.getSwipePreferenceTitle(context, key, true));
    assertEquals(label, GestureShortcutMapping.getSwipePreferenceTitle(context, key + "-1", true));
    ReflectionHelpers.setField(mapping, "currentGestureSet", 1);
    assertEquals("second_turn", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_LEFT_AND_DOWN));
  }

  @Test @Config(sdk = 26) public void worksWithFrameworkGesturesOnAndroid8() {
    prefs.edit().putString(context.getString(R.string.pref_shortcut_right_key), "next_item")
        .putBoolean(ReverseSwipeSettings.KEY, true).commit();
    assertEquals("next_item", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_DOWN));
  }

  @Test @Config(qualifiers = "ar-rEG-ldrtl") public void keepsExistingRtlAssignmentsBeforeExchangingAxes() {
    prefs.edit().putString(context.getString(R.string.pref_shortcut_right_key), "rtl_left")
        .putBoolean(ReverseSwipeSettings.KEY, true).commit();
    assertEquals("rtl_left", mapping.getActionKeyFromGestureId(GESTURE_SWIPE_UP));
    assertEquals(GestureShortcutMapping.getGestureString(context, GESTURE_SWIPE_UP),
        GestureShortcutMapping.getSwipePreferenceTitle(context,
            context.getString(R.string.pref_shortcut_right_key), true));
  }
}
