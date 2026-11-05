package com.google.android.accessibility.talkback.keyboardecho;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.input.TextEventFilter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BrailleKeyboardEchoSettingsTest {
  private Context context;
  private SharedPreferences prefs;
  private SharedPreferences legacy;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    legacy = context.getSharedPreferences("BraillePhoneBreakthroughV2Prefs", Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
    legacy.edit().clear().commit();
  }

  @Test
  public void legacyWordAndNoneValuesAreTranslated() {
    legacy.edit().putInt("echo_mode", 2).commit();
    assertEquals(TextEventFilter.PREF_ECHO_WORDS, BrailleKeyboardEchoSettings.read(context));
    legacy.edit().putInt("echo_mode", 3).commit();
    assertEquals(TextEventFilter.PREF_ECHO_NONE, BrailleKeyboardEchoSettings.read(context));
  }

  @Test
  public void meridianSettingWinsOverLegacySetting() {
    legacy.edit().putInt("echo_mode", 3).commit();
    prefs.edit().putString(context.getString(R.string.pref_echo_braille_keyboard_key), "0").commit();
    assertEquals(TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS,
        BrailleKeyboardEchoSettings.read(context));
  }

  @Test
  public void gestureCyclesAndStoresTheSameChoicesAsSettings() {
    int[] choices = KeyboardEchoSettings.choices(context.getResources());
    for (int choice : choices) {
      assertEquals(choice, BrailleKeyboardEchoSettings.cycle(context));
      assertEquals(choice, BrailleKeyboardEchoSettings.read(context));
    }
    assertEquals(TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS,
        BrailleKeyboardEchoSettings.read(context));
  }
}
