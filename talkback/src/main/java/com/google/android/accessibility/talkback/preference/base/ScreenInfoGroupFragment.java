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

import com.google.android.accessibility.talkback.R;

/** Main settings group holding descriptions, status readout and screen announcements. */
public class ScreenInfoGroupFragment extends TalkBackPreferenceFragment {

  public ScreenInfoGroupFragment() {
    super(R.xml.settings_group_screen_info);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_group_screen_info);
  }
}
