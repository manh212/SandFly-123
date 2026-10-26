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
  fun fastBufferFrames_oneBurstAtTheStart() {
    assertEquals(256, LowLatencyAudio.fastBufferFrames(256, 0))
  }

  @Test
  fun fastBufferFrames_rememberedGrowthKept() {
    // The one-burst size is only the starting point: a track made after running short grew
    // the buffer starts at the grown size, not back at one burst.
    assertEquals(768, LowLatencyAudio.fastBufferFrames(256, 768))
  }

  @Test
  fun nextBufferFrames_noShortfallKeepsTheOneBurstStart() {
    assertEquals(
      256,
      LowLatencyAudio.nextBufferFrames(
        currentFrames = 256,
        capacityFrames = 4096,
        burstFrames = 256,
        fastPath = true,
        underrunGrew = false,
      ),
    )
  }

  @Test
  fun nextBufferFrames_shortfallFromTheSmallStartGrowsOneBurst() {
    assertEquals(
      512,
      LowLatencyAudio.nextBufferFrames(
        currentFrames = 256,
        capacityFrames = 4096,
        burstFrames = 256,
        fastPath = true,
        underrunGrew = true,
      ),
    )
  }

  @Test
  fun nextBufferFrames_aFurtherShortfallGrowsAnotherBurst() {
    assertEquals(
      768,
      LowLatencyAudio.nextBufferFrames(
        currentFrames = 512,
        capacityFrames = 4096,
        burstFrames = 256,
        fastPath = true,
        underrunGrew = true,
      ),
    )
  }

  @Test
  fun nextBufferFrames_growthStopsAtTheCapacity() {
    assertEquals(
      4096,
      LowLatencyAudio.nextBufferFrames(
        currentFrames = 4096,
        capacityFrames = 4096,
        burstFrames = 256,
        fastPath = true,
        underrunGrew = true,
      ),
    )
  }
}
