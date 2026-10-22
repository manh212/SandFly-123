/*
 * Copyright (C) 2026 The SandFly Authors
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
import android.os.Bundle;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.translation.OfflineTranslator;
import com.google.android.accessibility.talkback.translation.TranslationPreferences;
import com.google.mlkit.nl.translate.TranslateLanguage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Settings screen for offline translation: language pair and language pack download. */
public class OfflineTranslationSettingsFragment extends TalkbackBaseFragment {

  private OfflineTranslator translator;
  private boolean lastDownloadFailed;

  public OfflineTranslationSettingsFragment() {
    super(R.xml.offline_translation_preferences);
  }

  @Override
  protected CharSequence getTitle() {
    return getText(R.string.title_pref_offline_translation_settings);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    Context context = getContext();
    if (context == null) {
      return;
    }
    translator = new OfflineTranslator(context);
    setupLanguagePreference(
        R.string.pref_offline_translation_source_language_key,
        TranslationPreferences.getSourceLanguage(context),
        /* isSource= */ true);
    setupLanguagePreference(
        R.string.pref_offline_translation_target_language_key,
        TranslationPreferences.getTargetLanguage(context),
        /* isSource= */ false);
    setupDownloadPreference();
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshPackStatus();
  }

  private void setupLanguagePreference(int keyResId, String currentValue, boolean isSource) {
    if (!(findPreferenceByResId(keyResId) instanceof ListPreference languagePreference)) {
      return;
    }
    List<String> languageCodes = new ArrayList<>(TranslateLanguage.getAllLanguages());
    Locale displayLocale = Locale.getDefault();
    languageCodes.sort(
        Comparator.comparing(
            code -> languageDisplayName(code, displayLocale), String.CASE_INSENSITIVE_ORDER));
    CharSequence[] entries = new CharSequence[languageCodes.size()];
    CharSequence[] values = new CharSequence[languageCodes.size()];
    for (int i = 0; i < languageCodes.size(); i++) {
      entries[i] = languageDisplayName(languageCodes.get(i), displayLocale);
      values[i] = languageCodes.get(i);
    }
    languagePreference.setEntries(entries);
    languagePreference.setEntryValues(values);
    languagePreference.setValue(currentValue);
    languagePreference.setSummaryProvider(
        preference -> ((ListPreference) preference).getEntry());
    languagePreference.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          Context context = getContext();
          if (context == null || !(newValue instanceof String)) {
            return false;
          }
          if (isSource) {
            TranslationPreferences.setSourceLanguage(context, (String) newValue);
          } else {
            TranslationPreferences.setTargetLanguage(context, (String) newValue);
          }
          lastDownloadFailed = false;
          startDownload();
          return true;
        });
  }

  private void setupDownloadPreference() {
    Preference downloadPreference =
        findPreferenceByResId(R.string.pref_offline_translation_download_pack_key);
    if (downloadPreference == null) {
      return;
    }
    downloadPreference.setOnPreferenceClickListener(
        preference -> {
          lastDownloadFailed = false;
          startDownload();
          return true;
        });
  }

  private void startDownload() {
    setPackStatusSummary(R.string.offline_translation_pack_downloading);
    translator.downloadModelIfNeeded(
        new OfflineTranslator.DownloadCallback() {
          @Override
          public void onSuccess() {
            refreshPackStatus();
          }

          @Override
          public void onFailure() {
            lastDownloadFailed = true;
            if (getContext() != null) {
              setPackStatusSummary(R.string.offline_translation_pack_download_failed);
            }
          }
        });
  }

  private void refreshPackStatus() {
    if (translator == null || getContext() == null) {
      return;
    }
    if (translator.isDownloadInProgress()) {
      setPackStatusSummary(R.string.offline_translation_pack_downloading);
      return;
    }
    translator.isModelDownloaded(
        downloaded -> {
          if (getContext() == null) {
            return;
          }
          if (downloaded) {
            setPackStatusSummary(R.string.offline_translation_pack_ready);
          } else if (lastDownloadFailed) {
            setPackStatusSummary(R.string.offline_translation_pack_download_failed);
          } else {
            setPackStatusSummary(R.string.offline_translation_pack_not_downloaded);
          }
        });
  }

  private void setPackStatusSummary(int summaryResId) {
    Preference statusPreference =
        findPreferenceByResId(R.string.pref_offline_translation_pack_status_key);
    if (statusPreference != null) {
      statusPreference.setSummary(summaryResId);
    }
  }

  private static String languageDisplayName(String languageCode, Locale displayLocale) {
    String name = Locale.forLanguageTag(languageCode).getDisplayLanguage(displayLocale);
    if (name.isEmpty()) {
      return languageCode;
    }
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }
}
