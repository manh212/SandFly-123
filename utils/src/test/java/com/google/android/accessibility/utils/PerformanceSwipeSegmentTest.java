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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the swipe segment timing and speak-early path counting in {@link Performance}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PerformanceSwipeSegmentTest {

  private Performance performance;
  private final List<String> lines = new ArrayList<>();
  private final Logger logger = (format, args) -> lines.add(String.format(format, args));

  @Before
  public void setUp() {
    performance = new Performance();
    performance.setComputeStatsEnabled(true);
  }

  @Test
  public void noSegments_noLineDisplayed() {
    performance.displaySwipeSegmentStats(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void segmentsAreDisplayed() {
    performance.recordSwipeSegment("navigation", 10);
    performance.recordSwipeSegment("navigation", 20);
    performance.recordSwipeSegment("navigation", 30);

    performance.displaySwipeSegmentStats(logger);

    assertEquals(
        Collections.singletonList(
            "Swipe segment navigation: count=3, mean=20ms, median=20ms, 90th"
                + " percentile=30ms"),
        lines);
  }

  @Test
  public void speakEarlyPathsAreDisplayed() {
    performance.onSpeakEarlyFromSavedOrder();
    performance.onSpeakEarlyFromSavedOrder();
    performance.onSpeakEarlyReRead();

    performance.displayPreparedSpeechStats(logger);

    assertEquals(
        Collections.singletonList(
            "Speak-early paths: 0 prepared, 2 from the saved order, 1 re-read from the app"),
        lines);
  }

  @Test
  public void statsDisabled_nothingIsRecorded() {
    performance.setComputeStatsEnabled(false);
    performance.recordSwipeSegment("navigation", 10);
    performance.onSpeakEarlyFromSavedOrder();
    performance.onSpeakEarlyReRead();
    performance.setComputeStatsEnabled(true);

    performance.displaySwipeSegmentStats(logger);
    performance.displayPreparedSpeechStats(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void clearAllStats_resetsSegmentsAndSpeakEarlyCounts() {
    performance.recordSwipeSegment("navigation", 10);
    performance.onSpeakEarlyFromSavedOrder();
    performance.onSpeakEarlyReRead();

    performance.clearAllStats();
    performance.displaySwipeSegmentStats(logger);
    performance.displayPreparedSpeechStats(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void segmentsAreDisplayedPerName() {
    performance.recordSwipeSegment("navigation", 8);
    performance.recordSwipeSegment("compose", 4);

    performance.displaySwipeSegmentStats(logger);

    assertEquals(
        Arrays.asList(
            "Swipe segment compose: count=1, mean=4ms, median=4ms, 90th percentile=4ms",
            "Swipe segment navigation: count=1, mean=8ms, median=8ms, 90th percentile=8ms"),
        lines);
  }
}
