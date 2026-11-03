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

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LauncherPositionDescriptionFilterTest {
  private static final String SAMSUNG = "com.sec.android.app.launcher";
  private Context context;
  private SharedPreferences prefs;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit()
        .putString(
            context.getString(R.string.pref_verbosity_preset_key),
            context.getString(R.string.pref_verbosity_preset_value_custom))
        .putBoolean(customKey(R.string.pref_table_speak_row_column_numbers_key), false)
        .putBoolean(customKey(R.string.pref_speak_container_element_positions_key), false)
        .commit();
  }

  @Test
  public void samsungIconCoordinatesAreRemovedWhenDisabled() {
    assertFiltered("File của bạn, Hàng 3, Cột 1", "File của bạn");
    assertFiltered("Gmail, Cột 2", "Gmail");
    assertFiltered("Gmail, Cột 2, 3 thông báo", "Gmail, 3 thông báo");
    assertFiltered("Gmail, Trong lưới", "Gmail");
    assertFiltered("Gmail, 4 hàng, 4 cột", "Gmail");
    assertFiltered("Cài đặt, Hàng 2", "Cài đặt");
    assertFiltered("Bộ sưu tập, Hàng 1, Cột 1, Trong lưới, 4 hàng, 4 cột",
        "Bộ sưu tập");
    assertFiltered("Bộ sưu tập, Hàng 1, Cột 1, Trong lưới, 4 hàng, 4 cột, 3 thông báo",
        "Bộ sưu tập, 3 thông báo");
    assertFiltered("Messages, Row 2, Column 3, In grid, 4 rows, 4 columns", "Messages");
  }

  @Test
  public void disabledContainerInfoRemovesGridSummaryButRetainsEnabledCoordinates() {
    prefs.edit()
        .putBoolean(customKey(R.string.pref_table_speak_row_column_numbers_key), true)
        .commit();
    assertFiltered("Bộ sưu tập, Hàng 1, Cột 1, Trong lưới, 4 hàng, 4 cột",
        "Bộ sưu tập, Hàng 1, Cột 1");
  }

  @Test
  public void disablingCoordinatesAlsoSuppressesGridDimensions() {
    prefs.edit()
        .putBoolean(customKey(R.string.pref_speak_container_element_positions_key), true)
        .commit();
    assertFiltered("Bộ sưu tập, Hàng 1, Cột 1, Trong lưới, 4 hàng, 4 cột",
        "Bộ sưu tập");
  }

  @Test
  public void enabledSettingsPreserveFullLauncherDescription() {
    prefs.edit()
        .putBoolean(customKey(R.string.pref_speak_container_element_positions_key), true)
        .putBoolean(customKey(R.string.pref_table_speak_row_column_numbers_key), true)
        .commit();
    assertFiltered("Bộ sưu tập, Hàng 1, Cột 1, Trong lưới, 4 hàng, 4 cột",
        "Bộ sưu tập, Hàng 1, Cột 1, Trong lưới, 4 hàng, 4 cột");
  }

  @Test
  public void legacyBooleanDoesNotOverrideDisabledCustomPreference() {
    prefs.edit().putBoolean(context.getString(R.string.pref_table_speak_row_column_numbers_key), true).commit();
    assertFiltered("Gmail, Cột 2", "Gmail");
  }

  @Test
  public void verbosityPresetsOverrideCustomPreference() {
    prefs.edit()
        .putString(
            context.getString(R.string.pref_verbosity_preset_key),
            context.getString(R.string.pref_verbosity_preset_value_high))
        .commit();
    assertFiltered("Gmail, Cột 2", "Gmail, Cột 2");
    prefs.edit()
        .putString(
            context.getString(R.string.pref_verbosity_preset_key),
            context.getString(R.string.pref_verbosity_preset_value_low))
        .commit();
    assertFiltered("Gmail, Cột 2", "Gmail");
  }

  @Test
  public void unrelatedAppsAndNaturalLanguageAreUnchanged() {
    String coordinate = "File của bạn, Hàng 3, Cột 1";
    assertEquals(coordinate,
        LauncherPositionDescriptionFilter.filter(coordinate, "com.example.reader", context));
    assertFiltered("Hướng dẫn di chuyển từ Hàng 3, Cột 1 đến Hàng 4",
        "Hướng dẫn di chuyển từ Hàng 3, Cột 1 đến Hàng 4");
    assertFiltered("Hàng 3, Cột 1", "Hàng 3, Cột 1");
    assertFiltered("Công việc, Hàng 2 để chuẩn bị", "Công việc, Hàng 2 để chuẩn bị");
  }

  @Test
  public void preservesTextSpansAndOriginalDescription() {
    SpannableString original = new SpannableString("File của bạn, Hàng 3, Cột 1");
    StyleSpan style = new StyleSpan(Typeface.BOLD);
    original.setSpan(style, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    CharSequence result = LauncherPositionDescriptionFilter.filter(original, SAMSUNG, context);

    assertEquals("File của bạn", result.toString());
    assertEquals("File của bạn, Hàng 3, Cột 1", original.toString());
    assertEquals(0, ((Spanned) result).getSpanStart(style));
    assertEquals(4, ((Spanned) result).getSpanEnd(style));
  }

  private void assertFiltered(String original, String expected) {
    assertEquals(expected,
        LauncherPositionDescriptionFilter.filter(original, SAMSUNG, context).toString());
  }

  private String customKey(int key) {
    return context.getString(R.string.pref_verbosity_preset_value_custom)
        + "_" + context.getString(key);
  }
}
