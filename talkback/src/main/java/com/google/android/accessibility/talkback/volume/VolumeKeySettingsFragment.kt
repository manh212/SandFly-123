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

package com.google.android.accessibility.talkback.volume

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity.TalkBackSubSettings
import com.google.android.accessibility.talkback.preference.base.GestureListPreference
import com.google.android.accessibility.talkback.preference.base.GesturePreferenceFragmentCompat
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_ARGS
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME

/**
 * Volume key settings: whether SandFly manages the volume keys itself, and the action of a
 * press and of a hold of each key, on the lock screen and off it.
 */
class VolumeKeySettingsFragment : TalkbackBaseFragment(R.xml.volume_key_preferences) {

  override fun getTitle(): CharSequence = getText(R.string.volume_key_settings_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    findPreference<GestureListPreference>(VolumeKeyManager.KEY_UP_HOLD_LOCKED_ACTION)
      ?.setLockScreenActionsOnly()
    findPreference<GestureListPreference>(VolumeKeyManager.KEY_DOWN_HOLD_LOCKED_ACTION)
      ?.setLockScreenActionsOnly()
  }

  override fun onDisplayPreferenceDialog(preference: Preference) {
    if (preference is GestureListPreference) {
      // In createDialogFragment, we create PreferenceDialogFragmentCompat for Handset & Auto
      // and null for Wear.
      val fragment: Fragment? = preference.createDialogFragment()
      if (fragment is PreferenceDialogFragmentCompat) {
        fragment.setTargetFragment(this, 0)
        fragment.show(parentFragmentManager, preference.key)
      } else {
        // For wear and phone devices, instead of dialog, we use a new fragment to show the
        // available action items.
        val intent = Intent(context, TalkBackSubSettings::class.java)
        intent.putExtra(FRAGMENT_NAME, GesturePreferenceFragmentCompat::class.java.canonicalName)
        intent.putExtra(
          FRAGMENT_ARGS,
          GesturePreferenceFragmentCompat.createBundleForFragmentArguments(preference),
        )
        startActivity(intent)
      }
    } else {
      super.onDisplayPreferenceDialog(preference)
    }
  }
}
