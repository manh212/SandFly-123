package com.google.android.accessibility.talkback.selector;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.VoiceProfiles;

public final class SpeechVolumeControl {
  private static final int STEP = 10;

  private SpeechVolumeControl() {}

  public static int change(Context context, SharedPreferences prefs, boolean increase) {
    String profileId = VoiceProfiles.activeId(prefs);
    String key =
        profileId.isEmpty()
            ? context.getString(R.string.pref_speech_volume_key)
            : VoiceProfiles.key(profileId, VoiceProfiles.VOLUME);
    int current =
        profileId.isEmpty()
            ? SharedPreferencesUtils.getIntFromStringPref(
                prefs,
                context.getResources(),
                R.string.pref_speech_volume_key,
                R.string.pref_speech_volume_default)
            : VoiceProfiles.read(prefs, profileId).volume();
    int minimum = context.getResources().getInteger(R.integer.pref_speech_volume_min);
    current = Math.max(minimum, Math.min(VoiceProfiles.MAX_VOLUME, current));
    int next =
        Math.max(minimum, Math.min(VoiceProfiles.MAX_VOLUME, current + (increase ? STEP : -STEP)));
    prefs.edit().putString(key, Integer.toString(next)).apply();
    return next;
  }
}
