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
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the slow-swipe profiles and speech-wait timing in {@link Performance}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PerformanceSlowSwipeProfileTest {

  private Performance performance;
  private final List<String> lines = new ArrayList<>();
  private final Logger logger = (format, args) -> lines.add(String.format(format, args));

  @Before
  public void setUp() {
    performance = new Performance();
    performance.setComputeStatsEnabled(true);
  }

  @Test
  public void fastSwipe_noProfileKept() {
    performance.beginSwipeProfile();
    performance.recordSwipeSegment("order fetch", 5);
    performance.endSwipeProfile(20);

    performance.displaySlowSwipeProfiles(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void slowSwipe_profileShowsSegmentsOrderAndSpeechPaths() {
    performance.beginSwipeProfile();
    performance.recordSwipeSegment("order fetch", 52);
    performance.setSwipeProfileOrderPath("built on the spot");
    performance.onSpeakEarlyReRead();
    performance.endSwipeProfile(87);

    performance.displaySlowSwipeProfiles(logger);

    assertEquals(
        Collections.singletonList(
            "Slow swipe 87ms: order fetch=52 | order: built on the spot | speech: re-read"),
        lines);
  }

  @Test
  public void slowSwipes_keptSlowestFirst_andCapped() {
    for (int i = 0; i < 12; i++) {
      performance.beginSwipeProfile();
      performance.endSwipeProfile(40 + i);
    }

    performance.displaySlowSwipeProfiles(logger);

    assertEquals(10, lines.size());
    assertTrue(lines.get(0).startsWith("Slow swipe 51ms"));
    assertTrue(lines.get(9).startsWith("Slow swipe 42ms"));
  }

  @Test
  public void speechPath_preparedWinsOverSavedOrder() {
    performance.beginSwipeProfile();
    performance.onSpeakEarlyFromSavedOrder();
    performance.onPreparedSpeechHit();
    performance.endSwipeProfile(60);

    performance.displaySlowSwipeProfiles(logger);

    assertEquals(1, lines.size());
    assertTrue(lines.get(0).contains("| speech: prepared"));
  }

  @Test
  public void profileWithoutBegin_isIgnored() {
    performance.endSwipeProfile(500);
    performance.setSwipeProfileOrderPath("saved order");

    performance.displaySlowSwipeProfiles(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void speechWaits_areDisplayed() {
    performance.recordSpeechWaitDuration(30);
    performance.recordSpeechWaitDuration(90);

    performance.displaySpeechWaitStats(logger);

    assertEquals(
        Collections.singletonList("Speech waits: count=2, median=30ms, 90th percentile=90ms"),
        lines);
  }

  @Test
  public void clearAllStats_resetsProfilesAndWaits() {
    performance.beginSwipeProfile();
    performance.endSwipeProfile(90);
    performance.recordSpeechWaitDuration(50);
    performance.onOrderCacheMiss(Performance.ORDER_MISS_NO_SAVED_ORDER);
    performance.onOrderBuildDied();

    performance.clearAllStats();
    performance.displaySlowSwipeProfiles(logger);
    performance.displaySpeechWaitStats(logger);
    performance.displayOrderBuildStats(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void orderMissCausesAndDiedBuilds_areDisplayed() {
    performance.onOrderCacheMiss(Performance.ORDER_MISS_NO_SAVED_ORDER);
    performance.onOrderCacheMiss(Performance.ORDER_MISS_NO_SAVED_ORDER);
    performance.onOrderCacheMiss(Performance.ORDER_MISS_DIFFERENT_WINDOW);
    performance.onOrderBuildDied();

    performance.displayOrderBuildStats(logger);

    assertEquals(
        java.util.Arrays.asList(
            "Reading order misses: different window=1, no saved order=2",
            "Background builds that died unpublished: 1"),
        lines);
  }
}
