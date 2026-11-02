package com.google.android.accessibility.talkback.selector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NavigationModeTest {
  private Context context;
  private SharedPreferences prefs;
  private String upDownKey;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    upDownKey = context.getString(R.string.pref_shortcut_up_and_down_key);
  }

  @Test
  public void startsInModeOne() {
    assertFalse(NavigationMode.isModeTwo(context));
  }

  @Test
  public void enteringModeTwoReleasesUpDownAndRestoresModeOneGesture() {
    prefs.edit().putString(upDownKey, "mode-one-action").commit();
    NavigationMode.setMode(context, "2");
    assertTrue(NavigationMode.isModeTwo(context));
    assertEquals(context.getString(R.string.shortcut_value_unassigned),
        prefs.getString(upDownKey, null));

    prefs.edit().putString(upDownKey, "mode-two-action").commit();
    NavigationMode.setMode(context, "1");
    assertFalse(NavigationMode.isModeTwo(context));
    assertEquals("mode-one-action", prefs.getString(upDownKey, null));

    NavigationMode.setMode(context, "2");
    assertEquals("mode-two-action", prefs.getString(upDownKey, null));
  }

  @Test
  public void swipeDirectionsOnlyOverrideFourSingleSwipes() {
    assertEquals(NavigationMode.SwipeAction.SELECT_PREVIOUS,
        NavigationMode.swipeAction(AccessibilityService.GESTURE_SWIPE_UP));
    assertEquals(NavigationMode.SwipeAction.SELECT_NEXT,
        NavigationMode.swipeAction(AccessibilityService.GESTURE_SWIPE_DOWN));
    assertEquals(NavigationMode.SwipeAction.ADJUST_PREVIOUS,
        NavigationMode.swipeAction(AccessibilityService.GESTURE_SWIPE_LEFT));
    assertEquals(NavigationMode.SwipeAction.ADJUST_NEXT,
        NavigationMode.swipeAction(AccessibilityService.GESTURE_SWIPE_RIGHT));
    assertEquals(NavigationMode.SwipeAction.NONE,
        NavigationMode.swipeAction(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN));
  }
}
