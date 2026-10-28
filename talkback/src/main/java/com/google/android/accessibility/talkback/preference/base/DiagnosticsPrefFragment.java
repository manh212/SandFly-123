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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.report.DiagnosticsExport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A screen with two actions: save Meridian's diagnostics to Downloads, or copy them, so the user
 * can send them to whoever is helping. The diagnostics are built off the main thread, since
 * building them reads the debug log.
 */
public class DiagnosticsPrefFragment extends TalkbackBaseFragment {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
    Preference export = findPreference(getString(R.string.pref_export_diagnostics_to_downloads_key));
    if (export != null) {
      export.setOnPreferenceClickListener(
          preference -> {
            exportToDownloads();
            return true;
          });
    }
    Preference copy = findPreference(getString(R.string.pref_copy_diagnostics_key));
    if (copy != null) {
      copy.setOnPreferenceClickListener(
          preference -> {
            copyToClipboard();
            return true;
          });
    }
  }

  @Override
  public void onDestroy() {
    executor.shutdownNow();
    super.onDestroy();
  }

  private void exportToDownloads() {
    Context context = requireContext().getApplicationContext();
    executor.execute(
        () -> {
          String text = DiagnosticsExport.INSTANCE.build(context);
          boolean saved = DiagnosticsExport.INSTANCE.saveToDownloads(context, text);
          mainHandler.post(
              () ->
                  Toast.makeText(
                          context,
                          saved
                              ? R.string.message_diagnostics_saved_to_downloads
                              : R.string.message_diagnostics_save_failed,
                          Toast.LENGTH_LONG)
                      .show());
        });
  }

  private void copyToClipboard() {
    Context context = requireContext().getApplicationContext();
    executor.execute(
        () -> {
          String text = DiagnosticsExport.INSTANCE.build(context);
          mainHandler.post(
              () -> {
                ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                if (clipboard != null) {
                  clipboard.setPrimaryClip(
                      ClipData.newPlainText(
                          context.getString(R.string.title_pref_export_diagnostics), text));
                }
                Toast.makeText(context, R.string.message_diagnostics_copied, Toast.LENGTH_LONG)
                    .show();
              });
        });
  }
}
