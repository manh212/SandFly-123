/*
 * Copyright 2026 Backtalk contributors
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

import android.os.Build;
import android.os.Bundle;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.keyboard.BrowseModeApps;
import com.google.android.accessibility.talkback.selector.NavigationMode;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;

/** Settings for where focus goes as the user moves through the screen. */
public class NavigationPrefFragment extends VerbosityPrefFragment {

  public NavigationPrefFragment() {
    super(R.xml.navigation_preferences);
  }

  protected NavigationPrefFragment(int preferenceResId) {
    super(preferenceResId);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_navigation_movement);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    BrowseModeApps.mode(requireContext());
    super.onCreatePreferences(savedInstanceState, rootKey);
    ListPreference remember = findPreference(BrowseModeApps.PREF_MODE);
    if (remember != null) {
      remember.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      remember.setOnPreferenceChangeListener(
          (preference, value) -> {
            if (BrowseModeApps.ALWAYS.equals(value)) {
              TalkBackService service = TalkBackService.getInstance();
              if (service != null && service.getKeyComboManager() != null) {
                BrowseModeApps.rememberGlobal(
                    requireContext(), service.getKeyComboManager().isBrowseModeEnabled());
              }
            }
            return true;
          });
    }
    ListPreference mode = findPreference(NavigationMode.PREF_KEY);
    if (mode != null) {
      mode.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      mode.setOnPreferenceChangeListener(
          (preference, value) -> {
            NavigationMode.setMode(requireContext(), String.valueOf(value));
            return true;
          });
    }
    if (!FeatureFlagReader.enableSmartBrowseMode(getContext())) {
      PreferenceSettingsUtils.hidePreference(
          getContext(), getPreferenceScreen(), R.string.pref_smart_browse_mode_key);
    }
    if (!FeatureFlagReader.enableBrowseMode(getContext()) && remember != null) {
      remember.setVisible(false);
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      Preference rotation =
          findPreference(getString(R.string.pref_two_finger_rotation_key));
      if (rotation != null) {
        rotation.setEnabled(false);
        rotation.setSummary(R.string.extended_gestures_unsupported);
      }
    }
  }
}
