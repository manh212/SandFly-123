/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.report

import android.content.Context
import com.google.android.accessibility.utils.Performance
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsExportTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val performance = Performance.getInstance()

  @Before
  fun setUp() {
    performance.clearAllStats()
    performance.setComputeStatsEnabled(false)
  }

  @After
  fun tearDown() {
    performance.setComputeStatsEnabled(false)
    performance.clearAllStats()
  }

  @Test
  fun statsSection_collectionOff_carriesOnlyTheStatusLine() {
    val section = DiagnosticsExport.buildPerformanceStatsSection()

    assertTrue(section.startsWith("== Performance statistics ==\n"))
    assertTrue(section.contains("Performance statistics collection: off\n"))
    assertFalse(section.contains("displayLabelToStats()"))
    assertFalse(section.contains("displayAllEventStats()"))
  }

  @Test
  fun statsSection_collectionOn_carriesTheStatistics() {
    performance.setComputeStatsEnabled(true)
    performance.allEventStats.increment(42)

    val section = DiagnosticsExport.buildPerformanceStatsSection()

    assertTrue(section.contains("Performance statistics collection: on\n"))
    assertTrue(section.contains("displayLabelToStats()"))
    assertTrue(section.contains("displayStatToLabelCompare()"))
    assertTrue(section.contains("displayAllEventStats()"))
    assertTrue(section.contains("count=1"))
  }

  @Test
  fun build_statsSectionSitsAfterLastCrashAndBeforeRecentLog() {
    // Both diagnostics actions — export to Downloads and copy — build this one text, so the
    // section lands in both.
    val text = DiagnosticsExport.build(context)

    val crashIndex = text.indexOf("== Last crash ==")
    val statsIndex = text.indexOf("== Performance statistics ==")
    val logIndex = text.indexOf("== Recent log of the SandFly process ==")
    assertTrue(crashIndex >= 0)
    assertTrue(statsIndex > crashIndex)
    assertTrue(logIndex > statsIndex)
  }
}
