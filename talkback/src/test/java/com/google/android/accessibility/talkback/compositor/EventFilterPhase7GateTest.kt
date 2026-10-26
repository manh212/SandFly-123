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
 * The pure decisions of the phase-7 preparation and speculation changes: when a round defers,
 * how a round is sliced, and when a focus may be spoken on prediction. A plain JVM test on
 * purpose, for the same reason as the presynthesis gate's: the predicates are pure, and
 * loading the machinery around them through the Robolectric sandbox is what breaks.
 */
class EventFilterPhase7GateTest {

  @Test
  fun deferRound_focusRoundBeforeItsOwnDelay_defers() {
    // A focus round is posted 30 ms after the focus lands; one attempted sooner than that
    // is still inside the landing's own handling.
    assertTrue(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_010))
    assertTrue(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_029))
  }

  @Test
  fun deferRound_focusRoundAtOrAfterItsDelay_runs() {
    // At or past its posting delay the round runs: it goes in slices that yield the event
    // loop between items, so it is never in the next swipe's way. This is the case that
    // matters — a wider deferral window put every focus round off to settle time, and the
    // rounds by source counted zero focus rounds in a whole session of swiping.
    assertFalse(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_030))
    assertFalse(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_100))
    assertFalse(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_300))
    assertFalse(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 5_000))
  }

  @Test
  fun retryEmptyRound_completeEmptyPlanNotYetRetried_retries() {
    assertTrue(EventFilter.shouldRetryEmptyRound(true, true, false))
  }

  @Test
  fun retryEmptyRound_anyReasonNotTo_doesNotRetry() {
    // Already retried once for this focus: a second empty plan means the order is not
    // coming, and later changes arm their own rounds.
    assertFalse(EventFilter.shouldRetryEmptyRound(true, true, true))
    // The round planned something after all.
    assertFalse(EventFilter.shouldRetryEmptyRound(true, false, false))
    // Planning failed partway: that is not an order that has not landed yet.
    assertFalse(EventFilter.shouldRetryEmptyRound(false, true, false))
  }

  @Test
  fun deferRound_settleAndGranularityRounds_neverDefer() {
    // A settle round is where deferred rounds land; deferring it again would starve it.
    assertFalse(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_SETTLE, 1_000, 1_100))
    assertFalse(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_GRANULARITY, 1_000, 1_100))
  }

  @Test
  fun deferRound_switchOff_orNoFocusYet_neverDefers() {
    assertFalse(
      EventFilter.shouldDeferRound(
        false, PreparedFocusSpeech.SOURCE_FOCUS, 1_000, 1_100))
    assertFalse(
      EventFilter.shouldDeferRound(
        true, PreparedFocusSpeech.SOURCE_FOCUS, 0, 1_100))
  }

  @Test
  fun sliceEnd_yielding_takesOneItemAtATime_inOrder() {
    // Walking the slices of a four-item plan visits every item exactly once, in plan order:
    // the chunked round prepares the same announcements as the round in one piece.
    val visited = mutableListOf<Int>()
    var from = 0
    while (from < 4) {
      val end = EventFilter.sliceEnd(true, from, 4)
      assertEquals(from + 1, end)
      for (i in from until end) {
        visited.add(i)
      }
      from = end
    }
    assertEquals(listOf(0, 1, 2, 3), visited)
  }

  @Test
  fun sliceEnd_notYielding_takesTheWholeRestAtOnce() {
    assertEquals(4, EventFilter.sliceEnd(false, 0, 4))
    assertEquals(4, EventFilter.sliceEnd(false, 2, 4))
    assertEquals(0, EventFilter.sliceEnd(false, 0, 0))
    assertEquals(0, EventFilter.sliceEnd(true, 0, 0))
  }

  @Test
  fun speculation_allGatesOpen_isAllowed() {
    assertTrue(
      EventFilter.speculationAllowed(
        true, true, 0, true))
  }

  @Test
  fun speculation_anyGateClosed_isRefused() {
    assertFalse(
      EventFilter.speculationAllowed(
        false, true, 0, true))
    assertFalse(
      EventFilter.speculationAllowed(
        true, false, 0, true))
    // A swipe the machinery had to scroll for lands where the app chooses, not where the
    // order says: never speculated.
    assertFalse(
      EventFilter.speculationAllowed(
        true, true, 1, true))
    assertFalse(
      EventFilter.speculationAllowed(
        true, true, 0, false))
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
