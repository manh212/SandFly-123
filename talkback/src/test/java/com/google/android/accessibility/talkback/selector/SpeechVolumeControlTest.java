package com.google.android.accessibility.talkback.selector;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpeechVolumeControlTest {
  private Context context;
  private SharedPreferences prefs;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
  }

  @Test
  public void changesSpeechGainByTenPointsWithoutSwitchingAudioStreams() {
    prefs.edit().putString(FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY, "media").commit();
    assertEquals(110, SpeechVolumeControl.change(context, prefs, true));
    assertEquals(100, SpeechVolumeControl.change(context, prefs, false));
    assertEquals("100", prefs.getString(context.getString(R.string.pref_speech_volume_key), ""));
    assertEquals("media", prefs.getString(FailoverTextToSpeech.PREF_SPEECH_STREAM_KEY, ""));
  }

  @Test
  public void stopsAtTheExistingVolumeBounds() {
    String key = context.getString(R.string.pref_speech_volume_key);
    prefs.edit().putString(key, "30").commit();
    assertEquals(25, SpeechVolumeControl.change(context, prefs, false));
    assertEquals(25, SpeechVolumeControl.change(context, prefs, false));
    prefs.edit().putString(key, "195").commit();
    assertEquals(200, SpeechVolumeControl.change(context, prefs, true));
    assertEquals(200, SpeechVolumeControl.change(context, prefs, true));
  }

  @Test
  public void adjustsOnlyTheActiveVoiceProfile() {
    prefs.edit()
        .putString(VoiceProfiles.PREF_IDS, "1,2")
        .putString(VoiceProfiles.PREF_ACTIVE, "2")
        .putString(VoiceProfiles.key("1", VoiceProfiles.VOLUME), "140")
        .putString(VoiceProfiles.key("2", VoiceProfiles.VOLUME), "80")
        .putString(context.getString(R.string.pref_speech_volume_key), "100")
        .commit();
    assertEquals(90, SpeechVolumeControl.change(context, prefs, true));
    assertEquals("90", prefs.getString(VoiceProfiles.key("2", VoiceProfiles.VOLUME), ""));
    assertEquals("140", prefs.getString(VoiceProfiles.key("1", VoiceProfiles.VOLUME), ""));
    assertEquals("100", prefs.getString(context.getString(R.string.pref_speech_volume_key), ""));
  }
}
