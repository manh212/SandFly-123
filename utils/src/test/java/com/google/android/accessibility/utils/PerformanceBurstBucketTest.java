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

package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests for the burst-vs-single bucketing in {@link Performance}: the split that lets an
 * export show whether the latency mechanisms earned their keep while the user was
 * flinging through items, instead of only at a reading pace.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PerformanceBurstBucketTest {

  private Performance performance;
  private final List<String> lines = new ArrayList<>();
  private final Logger logger = (format, args) -> lines.add(String.format(format, args));

  @Before
  public void setUp() {
    performance = new Performance();
    performance.setComputeStatsEnabled(true);
  }

  @Test
  public void bucket_noPredecessor_isSingle() {
    assertEquals(
        Performance.SWIPE_BUCKET_SINGLE, Performance.bucketSinceLastSwipe(0, 10_000));
  }

  @Test
  public void bucket_closeSwipes_areBurst() {
    assertEquals(Performance.SWIPE_BUCKET_BURST, Performance.bucketSinceLastSwipe(1_000, 1_100));
    assertEquals(Performance.SWIPE_BUCKET_BURST, Performance.bucketSinceLastSwipe(1_000, 1_299));
  }

  @Test
  public void bucket_readingPace_isSingle() {
    // Exactly at the window edge the swipe already counts as reading pace.
    assertEquals(
        Performance.SWIPE_BUCKET_SINGLE, Performance.bucketSinceLastSwipe(1_000, 1_300));
    assertEquals(
        Performance.SWIPE_BUCKET_SINGLE, Performance.bucketSinceLastSwipe(1_000, 6_000));
  }

  @Test
  public void bucket_outOfOrderTimestamps_isSingle() {
    assertEquals(
        Performance.SWIPE_BUCKET_SINGLE, Performance.bucketSinceLastSwipe(2_000, 1_000));
    assertEquals(
        Performance.SWIPE_BUCKET_SINGLE, Performance.bucketSinceLastSwipe(1_000, 1_000));
  }

  @Test
  public void counters_landInTheirBuckets() {
    performance.onPreparationRound(
        PreparedSpeechSources.FOCUS, Performance.SWIPE_BUCKET_BURST);
    performance.onPreparationRound(
        PreparedSpeechSources.FOCUS, Performance.SWIPE_BUCKET_SINGLE);
    performance.onPreparationRound(
        PreparedSpeechSources.FOCUS, Performance.SWIPE_BUCKET_BURST);
    performance.onPreparedSpeechHit(Performance.SWIPE_BUCKET_BURST);
    performance.onPreparedSpeechMiss(Performance.SWIPE_BUCKET_SINGLE);
    performance.onOrderBuildOnSwipePath(Performance.SWIPE_BUCKET_BURST);
    performance.onOrderBuildOnSwipePath(Performance.SWIPE_BUCKET_SINGLE);
    performance.onOrderBuildOnSwipePath(Performance.SWIPE_BUCKET_SINGLE);
    performance.onBurstLiveAnswer(Performance.SWIPE_BUCKET_BURST);

    assertEquals(2, performance.getPreparationRoundsByBucket(Performance.SWIPE_BUCKET_BURST));
    assertEquals(1, performance.getPreparationRoundsByBucket(Performance.SWIPE_BUCKET_SINGLE));
    assertEquals(1, performance.getPreparedSpeechHitsByBucket(Performance.SWIPE_BUCKET_BURST));
    assertEquals(0, performance.getPreparedSpeechHitsByBucket(Performance.SWIPE_BUCKET_SINGLE));
    assertEquals(1, performance.getOrderBuildsOnSwipePathByBucket(Performance.SWIPE_BUCKET_BURST));
    assertEquals(
        2, performance.getOrderBuildsOnSwipePathByBucket(Performance.SWIPE_BUCKET_SINGLE));
    assertEquals(1, performance.getBurstLiveAnswers());
  }

  @Test
  public void burstStats_areDisplayed() {
    performance.onPreparationRound(
        PreparedSpeechSources.FOCUS, Performance.SWIPE_BUCKET_BURST);
    performance.onPreparedSpeechHit(Performance.SWIPE_BUCKET_BURST);
    performance.onPreparedSpeechMiss(Performance.SWIPE_BUCKET_SINGLE);
    performance.onOrderBuildOnSwipePath(Performance.SWIPE_BUCKET_SINGLE);
    performance.onBurstLiveAnswer(Performance.SWIPE_BUCKET_BURST);
    performance.onPatternEngineAnswer(5, Performance.SWIPE_BUCKET_BURST);

    performance.displayBurstStats(logger);

    assertEquals(3, lines.size());
    assertEquals(
        "Burst vs single: preparation rounds burst 1, single 0; prepared hits burst 1,"
            + " single 0; prepared misses burst 0, single 1",
        lines.get(0));
    assertEquals(
        "Burst vs single: order builds on the swipe path burst 0, single 1; swipes"
            + " answered live in a burst 1",
        lines.get(1));
    assertEquals("Burst vs single: pattern engine answers burst 1, single 0", lines.get(2));
  }

  @Test
  public void statsDisabled_countersStop() {
    // The behaviour the performance-statistics switch relies on: once the service turns
    // counting off (which the developer switch now really does), nothing is counted.
    performance.setComputeStatsEnabled(false);
    performance.onPreparationRound(
        PreparedSpeechSources.FOCUS, Performance.SWIPE_BUCKET_BURST);
    performance.onPreparedSpeechHit(Performance.SWIPE_BUCKET_BURST);
    performance.onOrderBuildOnSwipePath(Performance.SWIPE_BUCKET_BURST);
    performance.setComputeStatsEnabled(true);

    assertEquals(0, performance.getPreparationRoundsByBucket(Performance.SWIPE_BUCKET_BURST));
    assertEquals(0, performance.getPreparedSpeechHitsByBucket(Performance.SWIPE_BUCKET_BURST));
    assertEquals(0, performance.getOrderBuildsOnSwipePathByBucket(Performance.SWIPE_BUCKET_BURST));
    performance.displayBurstStats(logger);
    assertTrue(lines.isEmpty());
  }

  @Test
  public void clearAllStats_resetsBuckets() {
    performance.onPreparationRound(
        PreparedSpeechSources.FOCUS, Performance.SWIPE_BUCKET_BURST);
    performance.onBurstLiveAnswer(Performance.SWIPE_BUCKET_BURST);

    performance.clearAllStats();

    assertEquals(0, performance.getPreparationRoundsByBucket(Performance.SWIPE_BUCKET_BURST));
    assertEquals(0, performance.getBurstLiveAnswers());
  }

  /** The preparation-round source constants live on PreparedFocusSpeech; 0 is focus. */
  private static final class PreparedSpeechSources {
    static final int FOCUS = 0;
  }
}
