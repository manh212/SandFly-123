/*
 * Copyright 2021 Google Inc.
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

package com.google.android.accessibility.talkback.utils;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.actor.DimScreenActor;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

/** Utils function for updating focus indicator stroke width and color. */
public class FocusIndicatorUtils {

  /**
   * Hides the focus indicator, so that a screenshot doesn't show it. Returns false when the focus
   * indicator can't be changed. {@link #restoreAfterScreenshot} shows it again.
   */
  public static boolean hideForScreenshot(AccessibilityService service) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return false;
    }
    service.setAccessibilityFocusAppearance(0, Color.TRANSPARENT);
    return true;
  }

  /** Shows the focus indicator that {@link #hideForScreenshot} hid, with the default appearance. */
  public static void restoreAfterScreenshot(AccessibilityService service) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    Resources res = service.getResources();
    if (DimScreenActor.isDimScreenEnabled(service, prefs)) {
      // As DimScreenActor sets it while the screen is hidden.
      setAccessibilityFocusAppearance(
          service,
          res.getDimensionPixelSize(R.dimen.accessibility_focus_highlight_stroke_width),
          Color.BLACK);
    } else {
      setAccessibilityFocusAppearance(
          service,
          res.getDimensionPixelSize(R.dimen.accessibility_focus_highlight_stroke_width),
          res.getColor(R.color.accessibility_focus_highlight_color, null));
    }
  }

  /**
   * Updates the focus indicator stroke width and color.
   *
   * @param service The parent service.
   * @param borderWidth the border width
   * @param borderColor the color value
   */
  public static void setAccessibilityFocusAppearance(
      AccessibilityService service, int borderWidth, int borderColor) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      service.setAccessibilityFocusAppearance(borderWidth, borderColor);
    }
  }

}
