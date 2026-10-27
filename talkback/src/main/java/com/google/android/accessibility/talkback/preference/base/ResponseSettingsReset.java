package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;

final class ResponseSettingsReset {
  private ResponseSettingsReset() {}

  static void bind(PreferenceFragmentCompat fragment) {
    Preference row = fragment.findPreference(fragment.getString(R.string.pref_reset_latency_settings_key));
    if (row == null) {
      return;
    }
    row.setOnPreferenceClickListener(
        preference -> {
          Context context = fragment.requireContext();
          A11yAlertDialogWrapper.materialDialogBuilder(context, fragment.getParentFragmentManager())
              .setTitle(R.string.title_pref_reset_responsiveness)
              .setMessage(R.string.message_reset_responsiveness)
              .setNegativeButton(android.R.string.cancel, null)
              .setPositiveButton(
                  android.R.string.ok,
                  (dialog, which) -> {
                    reset(context);
                    PreferencesActivityUtils.announceText(
                        context.getString(R.string.responsiveness_reset_done), context);
                  })
              .create()
              .show();
          return true;
        });
  }

  static void reset(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    SharedPreferencesUtils.remove(
        prefs,
        context.getString(R.string.pref_single_tap_key),
        context.getString(R.string.pref_reduce_window_delay_key),
        context.getString(R.string.pref_typing_confirmation_key),
        context.getString(R.string.pref_typing_long_press_duration_key),
        context.getString(R.string.pref_cache_keyboard_keys),
        context.getString(R.string.pref_speak_items_before_scroll_key),
        FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY,
        FailoverTextToSpeech.PREF_ALWAYS_INTERRUPT_SPEECH_KEY);
  }
}
