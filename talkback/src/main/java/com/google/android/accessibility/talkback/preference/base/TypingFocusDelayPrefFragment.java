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


import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.preference.SeekBarPreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.utils.TalkBackSettingDialogUtils;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;

/** Fragment to customize typing focus latency. */
public class TypingFocusDelayPrefFragment extends TalkbackBaseFragment {
  private static final String TAG = "TypingFocusDelayPrefFragment";

  /** Smallest typing focus delay the slider and the reading control allow, in milliseconds. */
  public static final int MIN_DELAY_MS = 1;

  /** Largest typing focus delay the slider and the reading control allow, in milliseconds. */
  public static final int MAX_DELAY_MS = 1000;

  private static final int SLIDER_STEP_MS = 5;

  private SeekBarPreference typingDelaySlider;
  private Context context;
  private SharedPreferences prefs;

  /** Preference items for Typing focus delay. */
  public enum TypingFocusDelayPref {
    DELAY_150_MS(R.string.value_touch_explore_time_out_150ms, 150),
    DELAY_200_MS(R.string.value_touch_explore_time_out_200ms, 200),
    DELAY_250_MS(R.string.value_touch_explore_time_out_250ms, 250),
    DELAY_300_MS(R.string.value_touch_explore_time_out_300ms, 300);

    private final int titleId;
    private final int delay;

    TypingFocusDelayPref(int titleId, int delay) {
      this.titleId = titleId;
      this.delay = delay;
    }

    public int getTitleId() {
      return titleId;
    }

    public int getDelay() {
      return delay;
    }
  }

  /** Refreshes the slider when the delay is changed elsewhere, e.g. by the reading control. */
  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (sharedPrefs, key) -> {
        if (typingDelaySlider != null
            && TextUtils.equals(key, context.getString(R.string.pref_typing_focus_time_out_key))) {
          typingDelaySlider.setValue(clampDelay(getStoredTypingFocusDelay()));
        }
      };

  public TypingFocusDelayPrefFragment() {
    super(R.xml.typing_focus_delay_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_touch_explore_timeout);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    context = getContext();

    if (context == null || !FeatureSupport.supportCustomizingFocusIndicator()) {
      return;
    }
    prefs = SharedPreferencesUtils.getSharedPreferences(context);

    initTypingDelaySlider();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (prefs != null) {
      prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (prefs != null) {
      prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    }
  }

  private void initTypingDelaySlider() {
    typingDelaySlider =
        (SeekBarPreference) findPreference(getString(R.string.pref_typing_focus_time_out_seekbar_key));
    if (typingDelaySlider == null) {
      return;
    }
    typingDelaySlider.setMin(MIN_DELAY_MS);
    typingDelaySlider.setMax(MAX_DELAY_MS);
    typingDelaySlider.setSeekBarIncrement(SLIDER_STEP_MS);
    typingDelaySlider.setShowSeekBarValue(true);
    typingDelaySlider.setValue(clampDelay(getStoredTypingFocusDelay()));
    typingDelaySlider.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          int newDelay = (Integer) newValue;
          if (newDelay != getStoredTypingFocusDelay()) {
            // Inform user a reading control item is added to adjust the latency
            // dynamically.
            A11yAlertDialogWrapper dialog =
                TalkBackSettingDialogUtils.createDialogForAddReadingControlItem(
                    this, prefs, context);
            if (dialog != null) {
              dialog.show();
            }
            SharedPreferencesUtils.putStringPref(
                prefs,
                context.getResources(),
                R.string.pref_typing_focus_time_out_key,
                String.valueOf(newDelay));
          }
          return true;
        });
  }

  private int getStoredTypingFocusDelay() {
    return SharedPreferencesUtils.getIntFromStringPref(
        prefs,
        context.getResources(),
        R.string.pref_typing_focus_time_out_key,
        R.string.pref_touch_explore_time_out_default);
  }

  private static int clampDelay(int delay) {
    return Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delay));
  }
}
