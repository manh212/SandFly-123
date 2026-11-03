/*
 * Copyright 2026 Meridian contributors
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
package com.google.android.accessibility.talkback.compositor;

import android.content.Context;
import android.content.res.Resources;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.utils.VerbosityPreferences;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes Samsung launcher coordinates that are embedded in app-provided content descriptions.
 *
 * <p>Collection feedback has its own verbosity checks, but One UI Home also places "Row 1,
 * Column 2" and grid dimensions directly in an icon's contentDescription. Those phrases otherwise
 * bypass collection feedback and get spoken as the icon's name. Do not change descriptions from
 * other apps: they may contain meaningful text that looks like coordinates.
 */
public final class LauncherPositionDescriptionFilter {
  private static final String SAMSUNG_LAUNCHER_PACKAGE = "com.sec.android.app.launcher";

  private static final Pattern GRID_SEGMENT =
      Pattern.compile(
          "(?iu),\\s*(?:(?:trong lưới|in (?:a )?grid)"
              + "(?:,\\s*\\d+\\s*(?:hàng|rows?),\\s*\\d+\\s*(?:cột|columns?))?"
              + "|\\d+\\s*(?:hàng|rows?),\\s*\\d+\\s*(?:cột|columns?))"
              + "(?=\\s*(?:,|$))");

  private static final Pattern POSITION_SEGMENT =
      Pattern.compile(
          "(?iu),\\s*(?:(?:hàng|row)\\s*\\d+"
              + "(?:,\\s*(?:cột|column)\\s*\\d+)?"
              + "|(?:cột|column)\\s*\\d+)(?=\\s*(?:,|$))");

  // A description consisting entirely of coordinates is content, not a suffix of an icon name.
  private static final Pattern COORDINATE_ONLY =
      Pattern.compile(
          "(?iu)^\\s*(?:hàng|row)\\s*\\d+(?:,\\s*(?:cột|column)\\s*\\d+)?\\s*$");

  private LauncherPositionDescriptionFilter() {}

  /**
   * Applies both relevant verbosity settings to Samsung's launcher-provided icon descriptions.
   * Returns the original CharSequence, including spans, when no removal is needed.
   */
  public static CharSequence filter(
      CharSequence description, CharSequence sourcePackage, Context context) {
    if (TextUtils.isEmpty(description)
        || !TextUtils.equals(SAMSUNG_LAUNCHER_PACKAGE, sourcePackage)
        || COORDINATE_ONLY.matcher(description).matches()) {
      return description;
    }

    boolean speakCoordinates = CollectionStateFeedbackUtils.shouldSpeakRowColumnNumbers(context);
    Resources resources = context.getResources();
    boolean speakContainerInfo =
        VerbosityPreferences.getPreferenceValueBool(
            SharedPreferencesUtils.getSharedPreferences(context),
            resources,
            context.getString(R.string.pref_speak_container_element_positions_key),
            resources.getBoolean(R.bool.pref_speak_container_element_positions_default));
    if (speakCoordinates && speakContainerInfo) {
      return description;
    }

    // Samsung can insert coordinates before notification counts, not just at the end.
    // Delete only complete, comma-delimited metadata segments, preserving other text/spans.
    List<int[]> ranges = new ArrayList<>();
    if (!speakContainerInfo || !speakCoordinates) {
      Matcher grid = GRID_SEGMENT.matcher(description);
      while (grid.find()) {
        ranges.add(new int[] {grid.start(), grid.end()});
      }
    }
    if (!speakCoordinates) {
      Matcher position = POSITION_SEGMENT.matcher(description);
      while (position.find()) {
        ranges.add(new int[] {position.start(), position.end()});
      }
    }
    if (ranges.isEmpty()) {
      return description;
    }
    ranges.sort(Comparator.comparingInt((int[] range) -> range[0]).reversed());
    SpannableStringBuilder filtered = new SpannableStringBuilder(description);
    for (int[] range : ranges) {
      filtered.delete(range[0], range[1]);
    }
    return filtered;
  }
}
