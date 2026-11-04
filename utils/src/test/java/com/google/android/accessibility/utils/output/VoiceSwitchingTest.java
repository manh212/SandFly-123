package com.google.android.accessibility.utils.output;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class VoiceSwitchingTest {
  private Voice voice(String name, Locale locale, boolean installed) {
    return new Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false,
        installed ? Collections.emptySet()
            : Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
  }

  @Test
  public void defaultCycleUsesInstalledVoicesInCurrentLanguageAndWraps() {
    Voice first = voice("one", Locale.forLanguageTag("vi-VN"), true);
    Voice second = voice("two", Locale.forLanguageTag("vi-VN"), true);
    List<Voice> choices = VoiceSwitching.candidates(Arrays.asList(
        first, second, first, voice("download", first.getLocale(), false),
        voice("english", Locale.US, true)), first.getLocale(), Collections.emptyList());
    assertEquals(Arrays.asList(first, second), choices);
    assertEquals(second, VoiceSwitching.adjacent(choices, "one", true));
    assertEquals(first, VoiceSwitching.adjacent(choices, "two", true));
    assertEquals(second, VoiceSwitching.adjacent(choices, "one", false));
  }

  @Test
  public void explicitOrderCanCrossLanguagesAndSkipsMissingVoices() {
    Voice vietnamese = voice("viet", Locale.forLanguageTag("vi-VN"), true);
    Voice english = voice("eng", Locale.US, true);
    List<Voice> choices = VoiceSwitching.candidates(Arrays.asList(vietnamese, english), Locale.US,
        Arrays.asList("viet", "uninstalled", "eng"));
    assertEquals(Arrays.asList(vietnamese, english), choices);
    assertEquals(vietnamese, VoiceSwitching.adjacent(choices, "unknown", true));
    assertEquals(english, VoiceSwitching.adjacent(choices, "unknown", false));
    assertTrue(VoiceSwitching.candidates(Arrays.asList(vietnamese, english), Locale.US,
        Collections.singletonList("missing")).isEmpty());
  }

  @Test
  public void savedOrderSurvivesReloadAndIsIsolatedByEngine() {
    SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("voices", 0);
    prefs.edit().clear().commit();
    VoiceSwitching.saveOrder(prefs, "engine.one", Arrays.asList("b", "a", "b"));
    assertEquals(Arrays.asList("b", "a"), VoiceSwitching.readOrder(prefs, "engine.one"));
    assertTrue(VoiceSwitching.readOrder(prefs, "engine.two").isEmpty());
    VoiceSwitching.saveOrder(prefs, "engine.one", Collections.emptyList());
    assertTrue(VoiceSwitching.readOrder(prefs, "engine.one").isEmpty());
    assertNotEquals(VoiceSwitching.selectedKey("engine.one"), VoiceSwitching.selectedKey("engine.two"));
  }

  @Test
  public void emptyAndUnavailableEngineDoesNotInventAVoice() {
    assertTrue(VoiceSwitching.installed(null).isEmpty());
    assertNull(VoiceSwitching.adjacent(Collections.emptyList(), "one", true));
  }
}
