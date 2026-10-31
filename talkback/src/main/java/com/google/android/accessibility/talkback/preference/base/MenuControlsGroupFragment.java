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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

/** Main settings group holding the choices for the SandFly menu and the reading controls. */
public class MenuControlsGroupFragment extends TalkBackPreferenceFragment {

  public MenuControlsGroupFragment() {
    super(R.xml.settings_group_menu_controls);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    Preference reset = findPreference("pref_menu_controls_reset");
    if (reset != null) {
      reset.setOnPreferenceClickListener(
          preference -> {
            new AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_controls_reset_title)
                .setMessage(R.string.menu_controls_reset_message)
                .setPositiveButton(
                    R.string.menu_controls_reset_confirm,
                    (dialog, which) -> {
                      Context context = requireContext();
                      MenuControlsReset.reset(context);
                      PreferencesActivityUtils.announceText(
                          getString(R.string.menu_controls_reset_finished), context);
                    })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
          });
    }
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_group_menu_controls);
  }
}
