/*
 * Copyright (C) 2024 Google Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils.gestures;

public class GestureAnalyticsEvent {

  public static final int EVENT_DOUBLE_TAP_SLOP_OVER_RANGE = 0;
  public static final int EVENT_TAP_TO_TOUCH_EXPLORE = 1;

  public static final int EXTRA_DEBUG_DOUBLE_TAP_SLOP_OVER_IN_10_PERCENT = 0;
  public static final int EXTRA_DEBUG_DOUBLE_TAP_SLOP_OVER_IN_20_PERCENT = 1;
  public static final int EXTRA_DEBUG_DOUBLE_TAP_SLOP_OVER_IN_50_PERCENT = 2;
  public static final int EXTRA_DEBUG_DOUBLE_TAP_SLOP_OVER_IN_100_PERCENT = 3;
  public static final int EXTRA_DEBUG_DOUBLE_TAP_SLOP_OVER_MORE_THAN_100_PERCENT = 4;

  public static final int EXTRA_DEBUG_TAP_TO_TOUCH_EXPLORE_TOTAL_SAVED_TIME = 0;
  public static final int EXTRA_DEBUG_TAP_TO_TOUCH_EXPLORE_HIT_COUNT = 1;

  public final int event;
  public final int gestureId;

  GestureAnalyticsEvent(int event, int gestureId) {
    this.event = event;
    this.gestureId = gestureId;
  }
}
