package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.ItemOrder;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.HashSet;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;

public final class MenuControlsReset {
  private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

  private MenuControlsReset() {}

  public static void reset(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    Set<String> keys = keysForReset(context.getResources());
    keys.add(ItemOrder.SANDFLY_MENU_KEY);
    keys.add(ItemOrder.READING_CONTROLS_KEY);
    keys.add(context.getString(R.string.pref_quick_settings_key));
    SharedPreferences.Editor editor = prefs.edit();
    for (String key : keys) {
      editor.remove(key);
    }
    editor.apply();
  }

  static Set<String> keysForReset(Resources resources) {
    Set<String> keys = new HashSet<>();
    for (int xmlId :
        new int[] {
          R.xml.context_menu_preferences,
          R.xml.navigation_menu_preferences,
          R.xml.selector_menu_preferences
        }) {
      try (XmlResourceParser parser = resources.getXml(xmlId)) {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
          if (parser.getEventType() != XmlPullParser.START_TAG) {
            continue;
          }
          int resourceId = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "key", 0);
          String key =
              resourceId != 0
                  ? resources.getString(resourceId)
                  : parser.getAttributeValue(ANDROID_NAMESPACE, "key");
          if (key != null && !key.isEmpty()) {
            keys.add(key);
          }
        }
      } catch (Exception e) {
        throw new IllegalStateException("Cannot load menu defaults", e);
      }
    }
    return keys;
  }
}
