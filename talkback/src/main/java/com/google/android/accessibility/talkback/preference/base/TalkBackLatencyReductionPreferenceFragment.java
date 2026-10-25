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
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import androidx.annotation.VisibleForTesting;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.TypingMethod;
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils;
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter;
import com.google.android.accessibility.talkback.speech.SpeechCacheController;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.libraries.accessibility.utils.log.LogUtils;

/** Fragment to customize latency related preferences. */
public class TalkBackLatencyReductionPreferenceFragment extends TalkbackBaseFragment {
  private static final String TAG = "TalkBackLatencyReductionPreferenceFragment";

  private Context context;
  private SharedPreferences prefs;

  public TalkBackLatencyReductionPreferenceFragment() {
    super(R.xml.latency_reduction_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_latency_reduction);
  }

  /** Updates fragment whenever their values change. */
  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (prefs, key) -> {
        if (getActivity() == null) {
          LogUtils.w(TAG, "Fragment is not attached to activity, do not update this setting page.");
          return;
        }
        if (TextUtils.equals(key, getString(R.string.pref_typing_confirmation_key))) {
          // For touch latency
          AccessibilitySuitePreference preference = findPreference(key);

          if (preference != null) {
            preference.notifyChanged();
          }
        }
      };

  @Override
  public void onResume() {
    super.onResume();
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    updatePreference(false);

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
    updateCacheKeyBoardKeyPreference();
  }

  @Override
  public void onPause() {
    super.onPause();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

  private int getPreferenceResourceId() {
    return R.xml.latency_reduction_preferences;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);

    context = getContext();
    setPreferencesFromResource(getPreferenceResourceId(), rootKey);
    prefs = SharedPreferencesUtils.getSharedPreferences(context.getApplicationContext());

    updatePreference(false);
    removeCacheKeyBoardPreferenceIfNeeded();
  }

  @VisibleForTesting
  final DialogInterface.OnClickListener resetLatencyReductionConfirmDialogPositive =
      (dialogInterface, i) -> {
        PreferencesActivityUtils.announceText(
            getString(R.string.gestures_announce_reset_latency_reduction_settings), context);
        // Reset logic here
        SharedPreferencesUtils.remove(
            prefs,
            context.getString(R.string.pref_single_tap_key),
            context.getString(R.string.pref_reduce_window_delay_key),
            context.getString(R.string.pref_typing_confirmation_key),
            context.getString(R.string.pref_typing_long_press_duration_key),
            context.getString(R.string.pref_cache_keyboard_keys),
            context.getString(R.string.pref_speak_items_before_scroll_key),
            context.getString(R.string.pref_background_order_build_key),
            context.getString(R.string.pref_touch_order_build_key),
            context.getString(R.string.pref_settle_order_build_key),
            context.getString(R.string.pref_lean_order_builder_key),
            context.getString(R.string.pref_trust_untouched_nodes_key),
            context.getString(R.string.pref_keep_prepared_across_unrelated_key),
            context.getString(R.string.pref_serve_stale_order_key),
            context.getString(R.string.pref_navigation_interrupt_key),
            context.getString(R.string.pref_window_focus_interrupt_key),
            context.getString(R.string.pref_speech_wait_cap_key),
            context.getString(R.string.pref_small_audio_buffer_key),
            context.getString(R.string.pref_split_speech_key),
            context.getString(R.string.pref_presynthesize_prepared_key),
            context.getString(R.string.pref_prepare_on_settle_key),
            context.getString(R.string.pref_prepare_wider_key),
            context.getString(R.string.pref_yielding_prepare_key),
            context.getString(R.string.pref_coalesce_listener_builds_key),
            context.getString(R.string.pref_speech_pipeline_key),
            context.getString(R.string.pref_speculative_speech_key),
            context.getString(R.string.pref_focus_priority_lane_key),
            context.getString(R.string.pref_fast_navigator_key),
            FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY,
            FailoverTextToSpeech.PREF_ALWAYS_INTERRUPT_SPEECH_KEY);
        dialogInterface.dismiss();
        updatePreference(true);
        updateCacheKeyBoardKeyPreference();
      };

  private final Preference.OnPreferenceClickListener resetPreferenceClickListener =
      (preference) -> {

        // Show confirm dialog.
        A11yAlertDialogWrapper alertDialog =
            A11yAlertDialogWrapper.materialDialogBuilder(
                    context, getActivity().getSupportFragmentManager())
                .setTitle(getString(R.string.reset_letency_reduction_settings_dialog_title))
                .setMessage(
                    getString(R.string.message_reset_letency_reduction_settings_confirm_dialog))
                .setPositiveButton(
                    R.string.reset_button_in_reset_letency_reduction_settings_confirm_dialog,
                    resetLatencyReductionConfirmDialogPositive)
                .setNegativeButton(android.R.string.cancel, (dialog, i) -> dialog.cancel())
                .create();
        alertDialog.show();

        return true;
      };

  /** Updates preference, including UI, by KeyComboModel. */
  private void updatePreference(boolean reset) {
    TalkBackPreferenceFilter talkBackPreferenceFilter =
        new TalkBackPreferenceFilter(getActivity().getApplicationContext());
    talkBackPreferenceFilter.filterPreferences(getPreferenceScreen());

    Preference resetTypeLatencyPreferenceScreen =
        (Preference) findPreference(getString(R.string.pref_reset_latency_settings_key));
    resetTypeLatencyPreferenceScreen.setOnPreferenceClickListener(resetPreferenceClickListener);

    if (reset) {
      TwoStatePreference singleTapPreference =
          findPreference(getString(R.string.pref_single_tap_key));
      if (singleTapPreference != null) {
        singleTapPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_single_tap_default));
      }

      TwoStatePreference reduceWindowDelayPreference =
          findPreference(getString(R.string.pref_reduce_window_delay_key));
      if (reduceWindowDelayPreference != null) {
        reduceWindowDelayPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_reduce_window_delay_default));
      }

      TwoStatePreference speakItemsBeforeScrollPreference =
          findPreference(getString(R.string.pref_speak_items_before_scroll_key));
      if (speakItemsBeforeScrollPreference != null) {
        speakItemsBeforeScrollPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_speak_items_before_scroll_default));
      }

      TwoStatePreference lowLatencyAudioPreference =
          findPreference(FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY);
      if (lowLatencyAudioPreference != null) {
        lowLatencyAudioPreference.setChecked(
            FailoverTextToSpeech.lowLatencyAudioDefault(context));
      }

      TwoStatePreference alwaysInterruptSpeechPreference =
          findPreference(FailoverTextToSpeech.PREF_ALWAYS_INTERRUPT_SPEECH_KEY);
      if (alwaysInterruptSpeechPreference != null) {
        alwaysInterruptSpeechPreference.setChecked(false);
      }

      TwoStatePreference backgroundOrderBuildPreference =
          findPreference(getString(R.string.pref_background_order_build_key));
      if (backgroundOrderBuildPreference != null) {
        backgroundOrderBuildPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_background_order_build_default));
      }

      TwoStatePreference touchOrderBuildPreference =
          findPreference(getString(R.string.pref_touch_order_build_key));
      if (touchOrderBuildPreference != null) {
        touchOrderBuildPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_touch_order_build_default));
      }

      TwoStatePreference settleOrderBuildPreference =
          findPreference(getString(R.string.pref_settle_order_build_key));
      if (settleOrderBuildPreference != null) {
        settleOrderBuildPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_settle_order_build_default));
      }

      TwoStatePreference leanOrderBuilderPreference =
          findPreference(getString(R.string.pref_lean_order_builder_key));
      if (leanOrderBuilderPreference != null) {
        leanOrderBuilderPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_lean_order_builder_default));
      }

      TwoStatePreference trustUntouchedNodesPreference =
          findPreference(getString(R.string.pref_trust_untouched_nodes_key));
      if (trustUntouchedNodesPreference != null) {
        trustUntouchedNodesPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_trust_untouched_nodes_default));
      }

      TwoStatePreference keepPreparedAcrossUnrelatedPreference =
          findPreference(getString(R.string.pref_keep_prepared_across_unrelated_key));
      if (keepPreparedAcrossUnrelatedPreference != null) {
        keepPreparedAcrossUnrelatedPreference.setChecked(
            context
                .getResources()
                .getBoolean(R.bool.pref_keep_prepared_across_unrelated_default));
      }

      TwoStatePreference serveStaleOrderPreference =
          findPreference(getString(R.string.pref_serve_stale_order_key));
      if (serveStaleOrderPreference != null) {
        serveStaleOrderPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_serve_stale_order_default));
      }

      TwoStatePreference navigationInterruptPreference =
          findPreference(getString(R.string.pref_navigation_interrupt_key));
      if (navigationInterruptPreference != null) {
        navigationInterruptPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_navigation_interrupt_default));
      }

      TwoStatePreference windowFocusInterruptPreference =
          findPreference(getString(R.string.pref_window_focus_interrupt_key));
      if (windowFocusInterruptPreference != null) {
        windowFocusInterruptPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_window_focus_interrupt_default));
      }

      TwoStatePreference speechWaitCapPreference =
          findPreference(getString(R.string.pref_speech_wait_cap_key));
      if (speechWaitCapPreference != null) {
        speechWaitCapPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_speech_wait_cap_default));
      }

      TwoStatePreference smallAudioBufferPreference =
          findPreference(getString(R.string.pref_small_audio_buffer_key));
      if (smallAudioBufferPreference != null) {
        smallAudioBufferPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_small_audio_buffer_default));
      }

      TwoStatePreference splitSpeechPreference =
          findPreference(getString(R.string.pref_split_speech_key));
      if (splitSpeechPreference != null) {
        splitSpeechPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_split_speech_default));
      }

      TwoStatePreference presynthesizePreparedPreference =
          findPreference(getString(R.string.pref_presynthesize_prepared_key));
      if (presynthesizePreparedPreference != null) {
        presynthesizePreparedPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_presynthesize_prepared_default));
      }

      TwoStatePreference prepareOnSettlePreference =
          findPreference(getString(R.string.pref_prepare_on_settle_key));
      if (prepareOnSettlePreference != null) {
        prepareOnSettlePreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_prepare_on_settle_default));
      }

      TwoStatePreference prepareWiderPreference =
          findPreference(getString(R.string.pref_prepare_wider_key));
      if (prepareWiderPreference != null) {
        prepareWiderPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_prepare_wider_default));
      }

      TwoStatePreference yieldingPreparePreference =
          findPreference(getString(R.string.pref_yielding_prepare_key));
      if (yieldingPreparePreference != null) {
        yieldingPreparePreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_yielding_prepare_default));
      }

      TwoStatePreference coalesceListenerBuildsPreference =
          findPreference(getString(R.string.pref_coalesce_listener_builds_key));
      if (coalesceListenerBuildsPreference != null) {
        coalesceListenerBuildsPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_coalesce_listener_builds_default));
      }

      TwoStatePreference speechPipelinePreference =
          findPreference(getString(R.string.pref_speech_pipeline_key));
      if (speechPipelinePreference != null) {
        speechPipelinePreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_speech_pipeline_default));
      }

      TwoStatePreference speculativeSpeechPreference =
          findPreference(getString(R.string.pref_speculative_speech_key));
      if (speculativeSpeechPreference != null) {
        speculativeSpeechPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_speculative_speech_default));
      }

      TwoStatePreference focusPriorityLanePreference =
          findPreference(getString(R.string.pref_focus_priority_lane_key));
      if (focusPriorityLanePreference != null) {
        focusPriorityLanePreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_focus_priority_lane_default));
      }

      TwoStatePreference fastNavigatorPreference =
          findPreference(getString(R.string.pref_fast_navigator_key));
      if (fastNavigatorPreference != null) {
        fastNavigatorPreference.setChecked(
            context.getResources().getBoolean(R.bool.pref_fast_navigator_default));
      }
    }

    AccessibilitySuitePreference typingPreference =
        (AccessibilitySuitePreference)
            findPreference(getString(R.string.pref_typing_confirmation_key));
    ListPreference longPressDuration =
        (ListPreference) findPreference(getString(R.string.pref_typing_long_press_duration_key));
    // Disable the "pref_typing_long_press_duration_key" when "pref_typing_confirmation_key" is set
    // as double-tap.
    if (typingPreference != null && longPressDuration != null) {
      if (reset) {
        longPressDuration.setValue(
            context.getResources().getString(R.string.pref_typing_long_press_duration_default));
      }
      @TypingMethod
      int typingMethod =
          SharedPreferencesUtils.getIntFromStringPref(
              prefs,
              getResources(),
              R.string.pref_typing_confirmation_key,
              R.string.pref_typing_confirmation_default);
      longPressDuration.setEnabled(typingMethod != DOUBLE_TAP);
    }
  }

  private void removeCacheKeyBoardPreferenceIfNeeded() {
    Preference pref = findPreferenceByResId(R.string.pref_cache_keyboard_keys);
    if (pref == null) {
      return;
    }
    if (!FeatureFlagReader.hasLocalCacheCapability(this.getContext())) {
      getPreferenceScreen().removePreference(pref);
    }
  }

  private void updateCacheKeyBoardKeyPreference() {
    Preference pref = findPreferenceByResId(R.string.pref_cache_keyboard_keys);
    if (pref == null) {
      return;
    }
    TwoStatePreference cacheKeyPreference = (TwoStatePreference) pref;

    TextToSpeech textToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {});
    boolean enabled =
        TextUtils.equals(
            SpeechCacheController.DEFAULT_CACHE_SUPPORT_TTS_ENGINE,
            textToSpeech.getDefaultEngine());
    textToSpeech.shutdown();
    cacheKeyPreference.setEnabled(enabled);
    cacheKeyPreference.setSummaryProvider(
        preference -> {
          return preference.isEnabled()
              ? getString(R.string.summary_cache_keyboard_keys)
              : getString(R.string.summary_cache_keyboard_keys_disabled);
        });

    boolean checked =
        SharedPreferencesUtils.getBooleanPref(
            prefs,
            getResources(),
            R.string.pref_cache_keyboard_keys,
            R.bool.pref_cache_keyboard_keys_default);
    cacheKeyPreference.setChecked(checked);
  }
}
