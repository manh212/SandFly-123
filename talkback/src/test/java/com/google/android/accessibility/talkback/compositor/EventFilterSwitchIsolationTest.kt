/*
 * Copyright 2026 The SandFly Authors
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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch-isolation decisions of the preparation family: the master gate the family
 * never had, and the burst governor that keeps a round's slices out of a live swipe's
 * way. A plain JVM test on purpose, for the same reason as the phase-7 gate's: the
 * decisions are pure, and loading the machinery around them through the Robolectric
 * sandbox is what breaks.
 */
class EventFilterSwitchIsolationTest {

  @After
  fun tearDown() {
    EventFilter.setPrepareAheadEnabled(true)
  }

  @Test
  fun masterSwitch_defaultsOn_andSetterRoundTrips() {
    assertTrue(EventFilter.isPrepareAheadEnabled())

    EventFilter.setPrepareAheadEnabled(false)
    assertFalse(EventFilter.isPrepareAheadEnabled())

    EventFilter.setPrepareAheadEnabled(true)
    assertTrue(EventFilter.isPrepareAheadEnabled())
  }

  @Test
  fun governor_burstAndYielding_defers() {
    assertTrue(EventFilter.governorDefersRound(true, true))
  }

  @Test
  fun governor_calmInput_neverDefers() {
    assertFalse(EventFilter.governorDefersRound(true, false))
    assertFalse(EventFilter.governorDefersRound(false, false))
  }

  @Test
  fun governor_nonYieldingRounds_runAsTheyAlwaysDid() {
    // With yielding off a round runs in one piece, as it did before plans existed; the
    // governor is part of the yielding mechanism and must not leak into that mode.
    assertFalse(EventFilter.governorDefersRound(false, true))
  }

  @Test
  fun burstRetryDelay_doublesUpToTheCap() {
    assertEquals(300L, EventFilter.burstRetryDelayMs(1))
    assertEquals(600L, EventFilter.burstRetryDelayMs(2))
    assertEquals(1200L, EventFilter.burstRetryDelayMs(3))
    assertEquals(1200L, EventFilter.burstRetryDelayMs(4))
    assertEquals(1200L, EventFilter.burstRetryDelayMs(20))
  }

  @Test
  fun burstRetryDelay_neverShrinksAsDeferralsGrow() {
    var previous = 0L
    for (deferrals in 1..10) {
      val delay = EventFilter.burstRetryDelayMs(deferrals)
      assertTrue(delay >= previous)
      previous = delay
    }
  }
}
