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

import android.os.Bundle;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr;

public class OcrSettingsFragment extends TalkbackBaseFragment {
  private boolean downloading;
  private boolean downloadFailed;

  public OcrSettingsFragment() {
    super(R.xml.ocr_settings_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_ocr_settings);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    ListPreference engine = findPreference(getString(R.string.pref_ocr_engine_key));
    if (engine != null) {
      engine.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
      engine.setOnPreferenceChangeListener(
          (preference, value) -> {
            preference.setSummary(value.toString());
            refreshDownload(value.toString());
            return true;
          });
    }
    Preference download = findPreference(getString(R.string.pref_ocr_data_download_key));
    if (download != null) {
      download.setOnPreferenceClickListener(
          preference -> {
            if (downloading || TesseractOcr.isDataDownloaded(requireContext())) return true;
            downloading = true;
            refreshDownload();
            TesseractOcr.downloadData(
                requireContext().getApplicationContext(),
                success -> {
                  downloading = false;
                  downloadFailed = !success;
                  if (isAdded()) refreshDownload();
                });
            return true;
          });
      refreshDownload();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshDownload();
  }

  private void refreshDownload() {
    refreshDownload(
        SharedPreferencesUtils.getSharedPreferences(requireContext())
            .getString(getString(R.string.pref_ocr_engine_key), "mlkit"));
  }

  private void refreshDownload(String engine) {
    Preference download = findPreference(getString(R.string.pref_ocr_data_download_key));
    if (download == null || !isAdded()) return;
    boolean ready = TesseractOcr.isDataDownloaded(requireContext());
    download.setVisible(engine.equals("tesseract"));
    download.setEnabled(!downloading && !ready);
    download.setSummary(
        downloading
            ? R.string.summary_ocr_data_downloading
            : ready
                ? R.string.summary_ocr_data_downloaded
                : downloadFailed
                    ? R.string.summary_ocr_data_download_failed
                    : R.string.summary_ocr_data_not_downloaded);
  }
}
