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

import android.os.Bundle;
import com.google.android.accessibility.talkback.R;

/**
 * A screen with two actions: save Meridian's diagnostics to Downloads, or copy them, so the user
 * can send them to whoever is helping. The diagnostics are built off the main thread, since
 * building them reads the debug log.
 */
public class DiagnosticsPrefFragment extends TalkbackBaseFragment {

  public DiagnosticsPrefFragment() {
    super(R.xml.diagnostics_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_export_diagnostics);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    DiagnosticsPreferenceActions.bind(this);
  }
}
