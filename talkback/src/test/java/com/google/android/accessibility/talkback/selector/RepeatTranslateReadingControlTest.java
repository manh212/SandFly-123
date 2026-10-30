package com.google.android.accessibility.talkback.selector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.XmlResourceParser;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.ItemOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RepeatTranslateReadingControlTest {

  @Test
  public void swipeDownTranslatesMostRecentSpeech() {
    assertEquals(
        Feedback.Speech.Action.TRANSLATE_LAST,
        SelectorController.actionForRepeatAndTranslate(true));
  }

  @Test
  public void swipeUpRepeatsMostRecentSpeechIncludingTranslation() {
    assertEquals(
        Feedback.Speech.Action.REPEAT_LAST,
        SelectorController.actionForRepeatAndTranslate(false));
  }

  @Test
  public void controlIsEnabledByDefaultAndIncludedInReadingControls() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    assertTrue(context.getResources().getBoolean(R.bool.pref_selector_repeat_translate_default));
    assertTrue(
        SelectorController.SELECTOR_SETTINGS.contains(
            SelectorController.Setting.REPEAT_AND_TRANSLATE));
    assertEquals(
        SelectorController.Setting.REPEAT_AND_TRANSLATE,
        SelectorController.Setting.getSettingFromPrefValue(
            context, context.getString(R.string.selector_repeat_translate)));

    String key = context.getString(R.string.pref_selector_repeat_translate_key);
    boolean found = false;
    try (XmlResourceParser parser =
        context.getResources().getXml(R.xml.selector_menu_preferences)) {
      for (int event = parser.getEventType();
          event != XmlPullParser.END_DOCUMENT;
          event = parser.next()) {
        if (event != XmlPullParser.START_TAG) {
          continue;
        }
        int resId =
            parser.getAttributeResourceValue(
                "http://schemas.android.com/apk/res/android", "key", 0);
        if (resId != 0 && key.equals(context.getString(resId))) {
          found = true;
          break;
        }
      }
    }
    assertTrue(found);
  }

  @Test
  public void existingCustomOrderKeepsOldItemsAndAddsNewControl() {
    Context context = RuntimeEnvironment.getApplication();
    String words = context.getString(R.string.pref_selector_granularity_words_key);
    String characters = context.getString(R.string.pref_selector_granularity_characters_key);
    String combined = context.getString(R.string.pref_selector_repeat_translate_key);
    List<String> ordered =
        ItemOrder.ordered(
            Arrays.asList(words, characters), SelectorController.defaultOrderKeys(context));

    assertTrue(ordered.contains(combined));
    assertTrue(ordered.indexOf(words) < ordered.indexOf(characters));
    assertFalse(ordered.isEmpty());
  }
}
