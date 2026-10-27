/*
 * Copyright 2025 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.preference.base;

import static com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.eventprocessor.PhoneticLetterMode;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.speech.SpeechCacheController;
import com.google.android.accessibility.talkback.utils.VerbosityPreferences;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;

/** Fragment holding a set of onscreen keyboard preferences. */
public class OnScreenKeyboardFragment extends TalkBackPreferenceFragment {
  private static final String TAG = "OnScreenKeyboardFragment";
  private Context context;
  private SharedPreferences prefs;

  /** Listener for verbosity preset preference changes. */
  private final OnSharedPreferenceChangeListener onSharedPreferenceChangeListener =
      (prefs, key) -> {
        if (!isAdded() || (getActivity() == null)) {
          return;
        }
        if (TextUtils.equals(key, getString(R.string.pref_verbosity_preset_key))) {
          String verbosityValue =
              SharedPreferencesUtils.getStringPref(
                  prefs,
                  getResources(),
                  R.string.pref_verbosity_preset_key,
                  R.string.pref_verbosity_preset_value_default);
          updateSpeakPhoneticLetters(verbosityValue);
        } else if (key != null) {
          Preference preference = findPreference(key);
          if (preference instanceof ListPreference list) {
            String value = prefs.getString(key, null);
            if (value != null && !TextUtils.equals(value, list.getValue())) {
              list.setValue(value);
            }
          }
        }
        if (TextUtils.equals(key, getString(R.string.pref_typing_confirmation_key))) {
          updateLongPressPreference();
        }
      };

  public OnScreenKeyboardFragment() {
    super(R.xml.on_screen_keyboard_preferences);
  }

  protected OnScreenKeyboardFragment(int preferenceResId) {
    super(preferenceResId);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_on_screen_keyboard);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);

    context = getContext();

    if (context == null) {
      return;
    }
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    if (!FeatureFlagReader.hasLocalCacheCapability(context)) {
      PreferenceSettingsUtils.hidePreference(
          context, getPreferenceScreen(), R.string.pref_cache_keyboard_keys);
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    AccessibilitySuitePreference typingMethodPreference =
        (AccessibilitySuitePreference)
            findPreference(getString(R.string.pref_typing_confirmation_key));
    if (typingMethodPreference != null) {
      typingMethodPreference.setSummaryProvider(
          preference -> {
            int method =
                SharedPreferencesUtils.getIntFromStringPref(
                    prefs,
                    context.getResources(),
                    R.string.pref_typing_confirmation_key,
                    R.string.pref_typing_confirmation_default);

            String[] typingValues =
                context.getResources().getStringArray(R.array.pref_typing_types_talkback_values);
            int position = typingValues.length - 1;
            for (int index = 0; index < typingValues.length; index++) {
              if (method == Integer.parseInt(typingValues[index])) {
                position = index;
                break;
              }
            }
            return context.getResources()
                .getStringArray(R.array.pref_typing_types_talkback_entries)[position];
          });
    }

    // Use verbosity prefix to keep settings consistent with Verbosity settings.
    String verbosityValue =
        SharedPreferencesUtils.getStringPref(
            prefs,
            getResources(),
            R.string.pref_verbosity_preset_key,
            R.string.pref_verbosity_preset_value_default);
    updateSpeakPhoneticLetters(verbosityValue);
    updateLongPressPreference();
    updateKeyboardCachePreference();
    prefs.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
  }

  private void updateSpeakPhoneticLetters(String verbosityValue) {
    // Use verbosity prefix to keep settings consistent with Verbosity settings.
    String verbosityPrefKey =
        VerbosityPreferences.toVerbosityPrefKey(
            verbosityValue, getString(R.string.pref_phonetic_letters_mode_key));
    ListPreference preference = findPreference(verbosityPrefKey);
    if (preference == null) {
      preference = findPreference(getString(R.string.pref_phonetic_letters_mode_key));
      if (preference == null) {
        return;
      }
      preference.setKey(verbosityPrefKey);
    }

    preference.setValue(PhoneticLetterMode.read(prefs, getResources()));
    // Disable default verbosity preference details if it is high or low.
    preference.setEnabled(!VerbosityPreferences.isVerbosityValueHighOrLow(verbosityValue, context));
  }

  private void updateLongPressPreference() {
    Preference preference = findPreference(getString(R.string.pref_typing_long_press_duration_key));
    if (!(preference instanceof ListPreference list)) {
      return;
    }
    int method = SharedPreferencesUtils.getIntFromStringPref(
        prefs, getResources(), R.string.pref_typing_confirmation_key,
        R.string.pref_typing_confirmation_default);
    list.setValue(prefs.getString(
        getString(R.string.pref_typing_long_press_duration_key),
        getString(R.string.pref_typing_long_press_duration_default)));
    list.setEnabled(method != DOUBLE_TAP);
  }

  private void updateKeyboardCachePreference() {
    Preference preference = findPreference(getString(R.string.pref_cache_keyboard_keys));
    if (!(preference instanceof TwoStatePreference cache)) {
      return;
    }
    TextToSpeech engine = new TextToSpeech(context.getApplicationContext(), status -> {});
    boolean supported = TextUtils.equals(
        SpeechCacheController.DEFAULT_CACHE_SUPPORT_TTS_ENGINE, engine.getDefaultEngine());
    engine.shutdown();
    cache.setEnabled(supported);
    cache.setSummary(supported
        ? R.string.summary_cache_keyboard_keys : R.string.summary_cache_keyboard_keys_disabled);
    cache.setChecked(SharedPreferencesUtils.getBooleanPref(
        prefs, getResources(), R.string.pref_cache_keyboard_keys,
        R.bool.pref_cache_keyboard_keys_default));
  }
}
