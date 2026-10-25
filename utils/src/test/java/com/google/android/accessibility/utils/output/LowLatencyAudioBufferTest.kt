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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tests for how the low-latency player's fast-path buffer is sized. */
class LowLatencyAudioBufferTest {

  @Test
  fun fastBufferFrames_twoBurstsByDefault() {
    assertThat(LowLatencyAudio.fastBufferFrames(256, false, 0)).isEqualTo(512)
  }

  @Test
  fun fastBufferFrames_rememberedGrowthWinsOverTwoBursts() {
    assertThat(LowLatencyAudio.fastBufferFrames(256, false, 768)).isEqualTo(768)
  }

  @Test
  fun fastBufferFrames_oneBurstWhenSmall() {
    assertThat(LowLatencyAudio.fastBufferFrames(256, true, 0)).isEqualTo(256)
  }

  @Test
  fun fastBufferFrames_rememberedGrowthIgnoredWhenSmall() {
    // The trial is only honest if a size remembered from running short cannot creep back in.
    assertThat(LowLatencyAudio.fastBufferFrames(256, true, 768)).isEqualTo(256)
  }
}
