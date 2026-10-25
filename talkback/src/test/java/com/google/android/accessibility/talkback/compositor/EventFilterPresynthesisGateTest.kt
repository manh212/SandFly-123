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

package com.google.android.accessibility.talkback.compositor

import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The gate that decides which prepared announcements get their sound made ahead of time. */
@RunWith(RobolectricTestRunner::class)
class EventFilterPresynthesisGateTest {

  @Test
  fun depthsOneAndTwo_qualify() {
    assertTrue(EventFilter.shouldSynthesizePrepared(true, NavigationTarget.TARGET_DEFAULT, 1))
    assertTrue(EventFilter.shouldSynthesizePrepared(true, NavigationTarget.TARGET_DEFAULT, 2))
  }

  @Test
  fun deeperThanTwo_doesNotQualify() {
    assertFalse(EventFilter.shouldSynthesizePrepared(true, NavigationTarget.TARGET_DEFAULT, 3))
    assertFalse(EventFilter.shouldSynthesizePrepared(true, NavigationTarget.TARGET_DEFAULT, 0))
  }

  @Test
  fun readingControlTargets_doNotQualify() {
    assertFalse(EventFilter.shouldSynthesizePrepared(true, NavigationTarget.TARGET_HEADING, 1))
    assertFalse(EventFilter.shouldSynthesizePrepared(true, NavigationTarget.TARGET_CONTROL, 2))
    assertFalse(EventFilter.shouldSynthesizePrepared(true, NavigationTarget.TARGET_LINK, 1))
  }

  @Test
  fun switchOff_nothingQualifies() {
    assertFalse(EventFilter.shouldSynthesizePrepared(false, NavigationTarget.TARGET_DEFAULT, 1))
    assertFalse(EventFilter.shouldSynthesizePrepared(false, NavigationTarget.TARGET_DEFAULT, 2))
  }
}
