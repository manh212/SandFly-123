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
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the prepared-speech hit/miss counting in {@link Performance}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PerformancePreparedSpeechTest {

  private Performance performance;
  private final List<String> lines = new ArrayList<>();
  private final Logger logger = (format, args) -> lines.add(String.format(format, args));

  @Before
  public void setUp() {
    performance = new Performance();
    performance.setComputeStatsEnabled(true);
  }

  @Test
  public void noCounts_noLineDisplayed() {
    performance.displayPreparedSpeechStats(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void countsAreDisplayed() {
    performance.onPreparedSpeechHit();
    performance.onPreparedSpeechHit();
    performance.onPreparedSpeechMiss();

    performance.displayPreparedSpeechStats(logger);

    assertEquals(
        Arrays.asList(
            "Prepared speech: 2 hits, 1 misses",
            "Speak-early paths: 2 prepared, 0 from the saved order, 0 re-read from the app"),
        lines);
  }

  @Test
  public void statsDisabled_eventsAreNotCounted() {
    performance.setComputeStatsEnabled(false);
    performance.onPreparedSpeechHit();
    performance.onPreparedSpeechMiss();
    performance.setComputeStatsEnabled(true);

    performance.displayPreparedSpeechStats(logger);

    assertTrue(lines.isEmpty());
  }

  @Test
  public void clearAllStats_resetsCounts() {
    performance.onPreparedSpeechHit();

    performance.clearAllStats();
    performance.displayPreparedSpeechStats(logger);

    assertTrue(lines.isEmpty());
  }
}
