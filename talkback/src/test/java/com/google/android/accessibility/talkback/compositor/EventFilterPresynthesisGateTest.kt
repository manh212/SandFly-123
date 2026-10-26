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

/**
 * The gate that decides which prepared announcements get their sound made ahead of time.
 *
 * A plain JVM test on purpose: the gate is a pure predicate over constants, and loading
 * EventFilter through the Robolectric sandbox also drags in the speech-cache file machinery,
 * whose FileDescriptor interception in Robolectric fails on current JDK 25 patch builds.
 */
class EventFilterPresynthesisGateTest {

  @Test
  fun depthOne_qualifies() {
    assertTrue(EventFilter.shouldSynthesizePrepared(NavigationTarget.TARGET_DEFAULT, 1))
  }

  @Test
  fun depthTwoAndDeeper_doNotQualify() {
    // Sounds made for targets two swipes away were almost never the ones played, while each
    // held the speech engine as long as a sound one swipe away: their text stays prepared,
    // but their sound is no longer made ahead.
    assertFalse(EventFilter.shouldSynthesizePrepared(NavigationTarget.TARGET_DEFAULT, 2))
    assertFalse(EventFilter.shouldSynthesizePrepared(NavigationTarget.TARGET_DEFAULT, 3))
    assertFalse(EventFilter.shouldSynthesizePrepared(NavigationTarget.TARGET_DEFAULT, 0))
  }

  @Test
  fun readingControlTargets_doNotQualify() {
    assertFalse(EventFilter.shouldSynthesizePrepared(NavigationTarget.TARGET_HEADING, 1))
    assertFalse(EventFilter.shouldSynthesizePrepared(NavigationTarget.TARGET_CONTROL, 2))
    assertFalse(EventFilter.shouldSynthesizePrepared(NavigationTarget.TARGET_LINK, 1))
  }

}
