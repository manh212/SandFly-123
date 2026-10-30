package com.google.android.accessibility.talkback.gesture.extended;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.TwoFingerRotationSettings;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExtendedGesturesTest {
  private Context context;
  private SharedPreferences prefs;
  private static final String ANDROID = "http://schemas.android.com/apk/res/android";
  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
  }

  @Test public void rotationIsOffByDefaultAndIndependentOfExtendedGestures() {
    assertFalse(TwoFingerRotationSettings.isEnabled(prefs));
    prefs.edit().putBoolean(TwoFingerRotationSettings.ENABLED, true).commit();
    assertTrue(TwoFingerRotationSettings.isEnabled(prefs));
    assertFalse(ExtendedGestures.isEnabled(prefs));
    prefs.edit().putBoolean(TwoFingerRotationSettings.ENABLED, false).commit();
    assertFalse(TwoFingerRotationSettings.isEnabled(prefs));
  }

  @Test @Config(sdk = 32) public void rotationDoesNotEnableOnUnsupportedAndroid() {
    prefs.edit().putBoolean(TwoFingerRotationSettings.ENABLED, true).commit();
    assertFalse(TwoFingerRotationSettings.isEnabled(prefs));
  }

  @Test public void rotationSwitchBelongsToMovementSettings() throws Exception {
    assertTrue(keys(R.xml.navigation_preferences).contains(TwoFingerRotationSettings.ENABLED));
    assertFalse(keys(R.xml.settings_group_navigation).contains(TwoFingerRotationSettings.ENABLED));
    assertFalse(keys(R.xml.touch_activation_preferences).contains(TwoFingerRotationSettings.ENABLED));
  }

  @Test public void isOffByDefaultAndDoesNotEraseAssignmentsWhenDisabled() {
    assertFalse(ExtendedGestures.isEnabled(prefs));
    String key = context.getString(ExtendedGestures.key(ExtendedGestures.FIRST_ID));
    prefs.edit().putString(key, "CLIPBOARD_HISTORY").putBoolean(ExtendedGestures.ENABLED, true).commit();
    assertTrue(ExtendedGestures.isEnabled(prefs));
    prefs.edit().putBoolean(ExtendedGestures.ENABLED, false).commit();
    assertFalse(ExtendedGestures.isEnabled(prefs));
    assertEquals("CLIPBOARD_HISTORY", prefs.getString(key, null));
  }

  @Test @Config(sdk = 32) public void ignoresEnabledBackupsOnUnsupportedAndroid() {
    prefs.edit().putBoolean(ExtendedGestures.ENABLED, true).commit();
    assertFalse(ExtendedGestures.isEnabled(prefs));
  }

  @Test public void switchIsInsideTouchSettingsAndAssignmentsStayInTheExistingTwoFingerGroup() throws Exception {
    assertTrue(keys(R.xml.touch_activation_preferences).contains(ExtendedGestures.ENABLED));
    assertFalse(keys(R.xml.settings_group_navigation).contains(ExtendedGestures.ENABLED));
    Set<String> names = new HashSet<>();
    String category = null;
    int count = 0;
    try (XmlResourceParser parser = context.getResources().getXml(R.xml.gesture_preferences)) {
      for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
        if (event != XmlPullParser.START_TAG) continue;
        String key = resourceText(parser, "key");
        if (parser.getName().endsWith("PreferenceCategory")) category = key;
        if (key.startsWith("pref_extended_gesture_")) {
          count++;
          assertEquals(context.getString(R.string.pref_category_2finger_shortcuts_key), category);
          assertEquals(context.getString(R.string.shortcut_value_unassigned), resourceText(parser, "defaultValue"));
          assertTrue(names.add(key));
        }
      }
    }
    assertEquals(12, count);
    Set<String> resettable = new HashSet<>(java.util.Arrays.asList(context.getResources().getStringArray(R.array.pref_shortcut_keys)));
    assertTrue(resettable.containsAll(names));
  }

  private Set<String> keys(int xml) throws Exception {
    Set<String> result = new HashSet<>();
    try (XmlResourceParser parser = context.getResources().getXml(xml)) {
      for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
        if (event == XmlPullParser.START_TAG) result.add(resourceText(parser, "key"));
      }
    }
    return result;
  }
  private String resourceText(XmlResourceParser parser, String name) {
    int resource = parser.getAttributeResourceValue(ANDROID, name, 0);
    if (resource != 0) return context.getString(resource);
    String value = parser.getAttributeValue(ANDROID, name);
    return value == null ? "" : value;
  }
}
