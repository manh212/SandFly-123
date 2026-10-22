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

package com.google.android.accessibility.talkback.translation;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

/** Stores the language pair chosen for offline translation. */
public final class TranslationPreferences {

  private TranslationPreferences() {}

  public static String getSourceLanguage(Context context) {
    return SharedPreferencesUtils.getStringPref(
        SharedPreferencesUtils.getSharedPreferences(context),
        context.getResources(),
        R.string.pref_offline_translation_source_language_key,
        R.string.offline_translation_default_source_language);
  }

  public static String getTargetLanguage(Context context) {
    return SharedPreferencesUtils.getStringPref(
        SharedPreferencesUtils.getSharedPreferences(context),
        context.getResources(),
        R.string.pref_offline_translation_target_language_key,
        R.string.offline_translation_default_target_language);
  }

  public static void setSourceLanguage(Context context, String languageCode) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    SharedPreferencesUtils.putStringPref(
        prefs, context.getResources(), R.string.pref_offline_translation_source_language_key,
        languageCode);
  }

  public static void setTargetLanguage(Context context, String languageCode) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    SharedPreferencesUtils.putStringPref(
        prefs, context.getResources(), R.string.pref_offline_translation_target_language_key,
        languageCode);
  }
}
