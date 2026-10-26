/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils.output

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for how the low-latency player's fast-path buffer is sized. */
class LowLatencyAudioBufferTest {

  @Test
  fun fastBufferFrames_twoBurstsByDefault() {
    assertEquals(512, LowLatencyAudio.fastBufferFrames(256, false, 0))
  }

  @Test
  fun fastBufferFrames_rememberedGrowthWinsOverTwoBursts() {
    assertEquals(768, LowLatencyAudio.fastBufferFrames(256, false, 768))
  }

  @Test
  fun fastBufferFrames_oneBurstWhenSmall() {
    assertEquals(256, LowLatencyAudio.fastBufferFrames(256, true, 0))
  }

  @Test
  fun fastBufferFrames_rememberedGrowthIgnoredWhenSmall() {
    // The trial is only honest if a size remembered from running short cannot creep back in.
    assertEquals(256, LowLatencyAudio.fastBufferFrames(256, true, 768))
  }

  @Test
  fun smallBufferTrialOver_noShortfallsKeepsTrial() {
    assertEquals(false, LowLatencyAudio.smallBufferTrialOver(0))
  }

  @Test
  fun smallBufferTrialOver_belowLimitKeepsTrial() {
    assertEquals(false, LowLatencyAudio.smallBufferTrialOver(LowLatencyAudio.SMALL_BUFFER_SHORTFALL_LIMIT - 1))
  }

  @Test
  fun smallBufferTrialOver_atLimitEndsTrial() {
    assertEquals(true, LowLatencyAudio.smallBufferTrialOver(LowLatencyAudio.SMALL_BUFFER_SHORTFALL_LIMIT))
  }

  @Test
  fun smallBufferTrialOver_farAboveLimitEndsTrial() {
    // The count the owner's device reached, which used to be kept small for ever.
    assertEquals(true, LowLatencyAudio.smallBufferTrialOver(18))
  }

  @Test
  fun grownBufferFrames_fastPathGrowsOneBurst() {
    assertEquals(512, LowLatencyAudio.grownBufferFrames(256, 256, true, 4100))
  }

  @Test
  fun grownBufferFrames_slowPathDoubles() {
    assertEquals(1920, LowLatencyAudio.grownBufferFrames(960, 256, false, 4100))
  }

  @Test
  fun grownBufferFrames_fastPathClampsAtCapacity() {
    assertEquals(4100, LowLatencyAudio.grownBufferFrames(3968, 256, true, 4100))
  }

  @Test
  fun grownBufferFrames_slowPathClampsAtCapacity() {
    assertEquals(4100, LowLatencyAudio.grownBufferFrames(3000, 256, false, 4100))
  }

  @Test
  fun grownBufferFrames_atCapacityStays() {
    // No trouble can be grown out of past the capacity: the size is left alone.
    assertEquals(4100, LowLatencyAudio.grownBufferFrames(4100, 256, true, 4100))
  }
}
