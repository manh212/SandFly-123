package com.google.android.accessibility.talkback.selector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.XmlResourceParser;
import com.google.android.accessibility.talkback.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CopyModeSettingsTest {
  @Test public void copyModeIsEnabledInReadingControlsAndStoredAsASelectorSetting() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    assertTrue(context.getResources().getBoolean(R.bool.pref_selector_copy_mode_default));
    assertTrue(SelectorController.SELECTOR_SETTINGS.contains(SelectorController.Setting.COPY_MODE));
    assertEquals(
        SelectorController.Setting.COPY_MODE,
        SelectorController.Setting.getSettingFromPrefValue(
            context, context.getString(R.string.selector_copy_mode_value)));
    String key = context.getString(R.string.pref_selector_copy_mode_key);
    boolean found = false;
    try (XmlResourceParser parser = context.getResources().getXml(R.xml.selector_menu_preferences)) {
      for (int event = parser.getEventType();
          event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
        if (event != XmlPullParser.START_TAG) continue;
        int res = parser.getAttributeResourceValue(
            "http://schemas.android.com/apk/res/android", "key", 0);
        if (res != 0 && key.equals(context.getString(res))) found = true;
      }
    }
    assertTrue(found);
  }
}
