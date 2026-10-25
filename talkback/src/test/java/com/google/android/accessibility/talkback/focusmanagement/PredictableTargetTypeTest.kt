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

package com.google.android.accessibility.talkback.focusmanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only target types the saved reading order can resolve the same way as a real move may have
 * their speech prepared in advance; every other kind keeps the plain behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictableTargetTypeTest {

  @Test
  fun orderResolvableKinds_arePredictable() {
    for (targetType in
      listOf(
        NavigationTarget.TARGET_DEFAULT,
        NavigationTarget.TARGET_HEADING,
        NavigationTarget.TARGET_CONTROL,
        NavigationTarget.TARGET_LINK,
      )) {
      assertTrue(
        "targetType $targetType",
        FocusProcessorForLogicalNavigation.isPredictableTargetType(targetType),
      )
    }
  }

  @Test
  fun otherKinds_areNotPredictable() {
    for (targetType in
      listOf(
        NavigationTarget.TARGET_SEARCH,
        NavigationTarget.TARGET_ROW,
        NavigationTarget.TARGET_COLUMN,
        NavigationTarget.TARGET_HTML_ELEMENT_HEADING_1,
        NavigationTarget.TARGET_HTML_ELEMENT_GRAPHIC,
        NavigationTarget.TARGET_WINDOW,
        NavigationTarget.TARGET_CONTAINER,
      )) {
      assertFalse(
        "targetType $targetType",
        FocusProcessorForLogicalNavigation.isPredictableTargetType(targetType),
      )
    }
  }
}
