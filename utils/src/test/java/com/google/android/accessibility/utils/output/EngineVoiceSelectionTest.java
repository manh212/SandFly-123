package com.google.android.accessibility.utils.output;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EngineVoiceSelectionTest {
  private final Context context = RuntimeEnvironment.getApplication();
  private final SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
  private final Voice first = new Voice("one", Locale.forLanguageTag("vi-VN"),
      Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet());
  private final Voice second = new Voice("two", first.getLocale(),
      Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet());

  private FailoverTextToSpeech engine(TextToSpeech backend) {
    FailoverTextToSpeech engine = new FailoverTextToSpeech(context, false);
    ReflectionHelpers.setField(engine, "tts", backend);
    ReflectionHelpers.setField(engine, "ttsEngine", "test.engine");
    ReflectionHelpers.setField(engine, "defaultTtsEngine", "test.engine");
    return engine;
  }

  private TextToSpeech backend(AtomicReference<Voice> current) {
    TextToSpeech backend = mock(TextToSpeech.class);
    when(backend.getVoices()).thenReturn(new LinkedHashSet<>(Arrays.asList(first, second)));
    when(backend.getVoice()).thenAnswer(call -> current.get());
    when(backend.setVoice(any(Voice.class))).thenAnswer(call -> {
      current.set(call.getArgument(0));
      return TextToSpeech.SUCCESS;
    });
    return backend;
  }

  @Test
  public void defaultVoiceIsPersistedAndRestoredAfterEngineChanges() {
    prefs.edit().clear().commit();
    AtomicReference<Voice> current = new AtomicReference<>(first);
    FailoverTextToSpeech engine = engine(backend(current));
    try {
      assertEquals("two", engine.cycleVoice(true));
      assertEquals("two", prefs.getString(VoiceSwitching.selectedKey("test.engine"), ""));
      assertEquals("", VoiceProfiles.activeId(prefs));
      current.set(first);
      assertEquals(first.getLocale(), ReflectionHelpers.callInstanceMethod(engine, "ensureSupportedLocale"));
      assertEquals(second, current.get());
      assertEquals("one", engine.cycleVoice(true));
    } finally {
      engine.shutdown();
    }
  }

  @Test
  public void failureDoesNotReplaceSavedVoice() {
    prefs.edit().clear().commit();
    TextToSpeech backend = backend(new AtomicReference<>(first));
    when(backend.setVoice(second)).thenReturn(TextToSpeech.ERROR);
    FailoverTextToSpeech engine = engine(backend);
    try {
      assertNull(engine.cycleVoice(true));
      assertFalse(prefs.contains(VoiceSwitching.selectedKey("test.engine")));
    } finally {
      engine.shutdown();
    }
  }

  @Test
  public void activeProfileKeepsItsEngineRatePitchAndVolume() {
    prefs.edit().clear().commit();
    String id = VoiceProfiles.create(prefs, "Đọc", "test.engine", "135", "1.7", "0.8", true);
    prefs.edit().putString(VoiceProfiles.PREF_ACTIVE, id).commit();
    FailoverTextToSpeech engine = engine(backend(new AtomicReference<>(first)));
    try {
      assertEquals("two", engine.cycleVoice(true));
      VoiceProfiles.VoiceProfile profile = VoiceProfiles.read(prefs, id);
      assertEquals(id, VoiceProfiles.activeId(prefs));
      assertEquals("two", profile.voice());
      assertEquals("test.engine", profile.engine());
      assertEquals(135, profile.volume());
      assertEquals(1.7f, profile.rate(), 0.001f);
      assertEquals(0.8f, profile.pitch(), 0.001f);
      assertTrue(profile.phrases());
    } finally {
      engine.shutdown();
      prefs.edit().clear().commit();
    }
  }
}
