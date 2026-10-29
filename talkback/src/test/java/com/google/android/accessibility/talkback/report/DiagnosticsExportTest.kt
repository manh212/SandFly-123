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
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.eventprocessor.ProcessorEventQueue
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
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
    SharedPreferencesUtils.getSharedPreferences(context)
      .edit()
      .remove(context.getString(R.string.pref_disable_latency_extras_key))
      .remove(context.getString(R.string.pref_disable_latency_pinned_key))
      .remove("pref_prepare_adjacent_speech")
      .remove("pref_prepare_second_ahead_speech")
      .remove("pref_event_processing_level")
      .remove("pref_order_cache")
      .remove("pref_split_control_speech")
      .commit()
    ProcessorEventQueue.setProcessingLevel("medium")
    LogUtils.setEnabled(false)
    LogUtils.flushLogFile()
    LogUtils.clearLogFiles()
    LogUtils.setLogFile(null)
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
    val logIndex = text.indexOf("== Recent log of the Meridian process ==")
    assertTrue(crashIndex >= 0)
    assertTrue(statsIndex > crashIndex)
    assertTrue(logIndex > statsIndex)
  }

  @Test
  fun snapshot_exposesLatencyControlsAsReadableValues() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs
      .edit()
      .putBoolean(context.getString(R.string.pref_disable_latency_extras_key), false)
      .putBoolean("pref_prepare_adjacent_speech", true)
      .putBoolean("pref_prepare_second_ahead_speech", true)
      .putString("pref_event_processing_level", "high")
      .commit()
    ProcessorEventQueue.setProcessingLevel("high")

    val snapshot = DiagnosticsExport.buildSnapshotSections(context)

    assertTrue(snapshot.contains("== Latency controls ==\n"))
    assertTrue(snapshot.contains("Speech preparation for adjacent item: true\n"))
    assertTrue(snapshot.contains("Speech preparation for second-ahead item: true\n"))
    assertTrue(snapshot.contains("Speech preparation enabled by settings: true\n"))
    assertTrue(snapshot.contains("Event processing level selected: high\n"))
    assertTrue(snapshot.contains("Event processing level effective: high\n"))
    ProcessorEventQueue.setProcessingLevel("medium")
  }

  @Test
  fun snapshot_defaultsToExtrasOffAndPinnedOptimizationsOn() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs
      .edit()
      .remove(context.getString(R.string.pref_disable_latency_extras_key))
      .remove(context.getString(R.string.pref_disable_latency_pinned_key))
      .commit()

    val snapshot = DiagnosticsExport.buildSnapshotSections(context)

    assertTrue(snapshot.contains("Latency extras disabled: true\n"))
    assertTrue(snapshot.contains("Pinned latency optimizations: true\n"))
    assertTrue(snapshot.contains("Order cache selected: true\n"))
    assertTrue(snapshot.contains("Split control speech selected: false\n"))
  }

  @Test
  fun buildForExport_includesTheLastQueuedDebugLogLine() {
    assertQueuedLogIsIncluded(export = true)
  }

  @Test
  fun buildForCopy_includesTheLastQueuedDebugLogLine() {
    assertQueuedLogIsIncluded(export = false)
  }

  private fun assertQueuedLogIsIncluded(export: Boolean) {
    LogUtils.setLogFile(LogUtils.defaultLogFile(context))
    LogUtils.clearLogFiles()
    LogUtils.setEnabled(true)
    repeat(300) { LogUtils.d("ExportFlush", "queued line %d", it) }

    val text =
      if (export) DiagnosticsExport.buildForExport(context) else DiagnosticsExport.build(context)

    assertTrue(text.contains("D/ExportFlush: queued line 0"))
    assertTrue(text.contains("D/ExportFlush: queued line 299"))
    assertTrue(
      text.indexOf("D/ExportFlush: queued line 0") < text.indexOf("D/ExportFlush: queued line 299")
    )
  }
}

