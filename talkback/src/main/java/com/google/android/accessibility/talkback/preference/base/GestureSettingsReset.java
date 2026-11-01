/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping;
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;

public final class GestureSettingsReset {
  private GestureSettingsReset() {}

  public static void bind(PreferenceFragmentCompat fragment) {
    Preference reset = fragment.findPreference(fragment.getString(R.string.pref_reset_gesture_settings_key));
    if (reset == null) return;
    reset.setOnPreferenceClickListener(
        preference -> {
          Context context = fragment.requireContext();
          SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
          boolean gestureSetEnabled =
              GestureShortcutMapping.isGestureSetEnabled(
                  context,
                  prefs,
                  R.string.pref_multiple_gesture_set_key,
                  R.bool.pref_multiple_gesture_set_default);
          A11yAlertDialogWrapper.materialDialogBuilder(context, fragment.getParentFragmentManager())
              .setTitle(
                  gestureSetEnabled
                      ? R.string.Reset_gesture_settings_set_dialog_title
                      : R.string.Reset_gesture_settings_dialog_title)
              .setMessage(R.string.message_reset_gesture_settings_confirm_dialog)
              .setPositiveButton(
                  R.string.reset_button_in_reset_gesture_settings_confirm_dialog,
                  (dialog, which) -> {
                    reset(context, prefs);
                    PreferencesActivityUtils.announceText(
                        fragment.getString(R.string.gestures_announce_reset_gesture_settings),
                        context);
                  })
              .setNegativeButton(android.R.string.cancel, null)
              .create()
              .show();
          return true;
        });
  }

  private static void reset(Context context, SharedPreferences prefs) {
    int gestureSet =
        SharedPreferencesUtils.getIntFromStringPref(
            prefs,
            context.getResources(),
            R.string.pref_gesture_set_key,
            R.string.pref_gesture_set_value_default);
    SharedPreferences.Editor editor = prefs.edit();
    for (String defaultKey : context.getResources().getStringArray(R.array.pref_shortcut_keys)) {
      editor.remove(GestureShortcutMapping.getPrefKeyWithGestureSet(defaultKey, gestureSet));
    }
    editor.apply();
  }
}
