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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions of the preparation and speculation machinery: when a round defers, how
 * a round is sliced, and when a focus may be spoken on prediction. A plain JVM test on
 * purpose, for the same reason as the presynthesis gate's: the predicates are pure, and
 * loading the machinery around them through the Robolectric sandbox is what breaks.
 */
class EventFilterPhase7GateTest {

  @Test
  fun deferRound_focusRoundRightAfterAFocus_defers() {
    assertTrue(
      EventFilter.shouldDeferRound(
        PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_100))
  }

  @Test
  fun deferRound_focusRoundAfterTheBurstWindow_runs() {
    assertFalse(
      EventFilter.shouldDeferRound(
        PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_300))
    assertFalse(
      EventFilter.shouldDeferRound(
        PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 5_000))
  }

  @Test
  fun deferRound_settleAndGranularityRounds_neverDefer() {
    // A settle round is where deferred rounds land; deferring it again would starve it.
    assertFalse(
      EventFilter.shouldDeferRound(
        PreparedFocusSpeech.SOURCE_SETTLE, 1_000, 1_100))
    assertFalse(
      EventFilter.shouldDeferRound(
        PreparedFocusSpeech.SOURCE_GRANULARITY, 1_000, 1_100))
  }

  @Test
  fun deferRound_noFocusYet_neverDefers() {
    assertFalse(
      EventFilter.shouldDeferRound(
        PreparedFocusSpeech.SOURCE_FOCUS, 0, 1_100))
  }

  @Test
  fun sliceEnd_takesOneItemAtATime_inOrder() {
    // Walking the slices of a four-item plan visits every item exactly once, in plan order:
    // the chunked round prepares the same announcements as the round in one piece.
    val visited = mutableListOf<Int>()
    var from = 0
    while (from < 4) {
      val end = EventFilter.sliceEnd(from, 4)
      assertEquals(from + 1, end)
      for (i in from until end) {
        visited.add(i)
      }
      from = end
    }
    assertEquals(listOf(0, 1, 2, 3), visited)
  }

  @Test
  fun sliceEnd_emptyPlan_endsWhereItStarts() {
    assertEquals(0, EventFilter.sliceEnd(0, 0))
  }

  @Test
  fun speculation_allGatesOpen_isAllowed() {
    assertTrue(
      EventFilter.speculationAllowed(
        true, 0, true))
  }

  @Test
  fun speculation_anyGateClosed_isRefused() {
    assertFalse(
      EventFilter.speculationAllowed(
        false, 0, true))
    // A swipe the machinery had to scroll for lands where the app chooses, not where the
    // order says: never speculated.
    assertFalse(
      EventFilter.speculationAllowed(
        true, 1, true))
    assertFalse(
      EventFilter.speculationAllowed(
        true, 0, false))
  }

  @Test
  fun speculationVerdict_eventOnTheSpokenNode_confirms() {
    assertEquals(
      EventFilter.SPECULATION_CONFIRMED,
      EventFilter.speculationVerdict(true, true))
  }

  @Test
  fun speculationVerdict_eventElsewhere_corrects() {
    assertEquals(
      EventFilter.SPECULATION_CORRECTED,
      EventFilter.speculationVerdict(true, false))
  }

  @Test
  fun speculationVerdict_noSpeechSubmitted_noVerdict() {
    // A speculation that only updated the focus state submitted nothing that could be wrong:
    // the focus event's own speaking is not a correction of anything.
    assertEquals(
      EventFilter.SPECULATION_NO_VERDICT,
      EventFilter.speculationVerdict(false, true))
    assertEquals(
      EventFilter.SPECULATION_NO_VERDICT,
      EventFilter.speculationVerdict(false, false))
  }
}
