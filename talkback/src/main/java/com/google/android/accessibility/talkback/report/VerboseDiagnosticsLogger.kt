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
import android.os.Handler
import android.os.HandlerThread
import com.google.android.accessibility.utils.Logger
import com.google.android.accessibility.utils.Performance
import com.google.android.libraries.accessibility.utils.log.LogUtils

/** Schedules a task to run once after a delay. Abstracted so the controller is unit-testable. */
internal interface DiagnosticsTaskScheduler {
  fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask

  fun interface ScheduledTask {
    fun cancel()
  }
}

/**
 * The start/stop state machine behind [VerboseDiagnosticsLogger]. Starting writes the snapshot
 * once and the statistics once, then keeps writing statistics on a fixed cadence; stopping
 * cancels the pending write. Repeated starts while already active change nothing, so toggling
 * the log level back and forth can never double the cadence.
 */
internal class VerboseDiagnosticsController(
  private val scheduler: DiagnosticsTaskScheduler,
  private val writeSnapshot: () -> Unit,
  private val writeStats: () -> Unit,
  private val onStop: () -> Unit = {},
) {
  private var pendingStats: DiagnosticsTaskScheduler.ScheduledTask? = null

  var isActive: Boolean = false
    private set

  /** Starts when [enabled] and stops otherwise; a no-op when the state already matches. */
  @Synchronized
  fun syncEnabled(enabled: Boolean) {
    if (enabled) {
      if (isActive) return
      isActive = true
      writeSnapshot()
      writeStatsThenSchedule()
    } else {
      if (!isActive) return
      isActive = false
      pendingStats?.cancel()
      pendingStats = null
      onStop()
    }
  }

  private fun writeStatsThenSchedule() {
    writeStats()
    pendingStats =
      scheduler.schedule(STATS_INTERVAL_MS) {
        synchronized(this) {
          if (isActive) writeStatsThenSchedule()
        }
      }
  }

  companion object {
    /**
     * How often performance statistics are written to the log while the debug log is on. The statistics are
     * cumulative, so a fresh copy every two minutes tracks how they evolve without flooding the
     * log: one copy is a few hundred lines at most, and only while statistics collection is
     * enabled in developer settings.
     */
    const val STATS_INTERVAL_MS: Long = 120_000
  }
}

/**
 * While the debug log is on, writes diagnostics into the log itself, so an exported log
 * carries them without anyone having to capture them at exactly the right moment: the snapshot
 * sections of the diagnostics export (the very text [DiagnosticsExport.buildSnapshotSections]
 * builds) are written when the debug log takes effect — the switch is turned on, or the service
 * starts with it already on — and the performance statistics are written every
 * [VerboseDiagnosticsController.STATS_INTERVAL_MS]. While it is off this does nothing at all.
 */
object VerboseDiagnosticsLogger {
  private const val TAG = "VerboseDiagnostics"
  private const val PERFORMANCE_TAG = "Performance"

  @Volatile private var appContext: Context? = null

  private var controller: VerboseDiagnosticsController? = null

  /**
   * Starts or stops the debug-log enrichment to match the debug-log switch now in effect. Called
   * whenever the service (re)loads the debug-log preference, including at startup.
   */
  @JvmStatic
  fun syncWithDebugLog(context: Context) {
    appContext = context.applicationContext
    val enabled = LogUtils.isEnabled()
    if (!enabled && controller == null) {
      // Never activated in this process: do not even create the worker thread.
      return
    }
    controller().syncEnabled(enabled)
  }

  private val snapshotRunnable = Runnable { logSnapshotSections() }
  private val statsRunnable = Runnable { logPerformanceStats() }

  @Synchronized
  private fun controller(): VerboseDiagnosticsController {
    controller?.let {
      return it
    }
    val thread = HandlerThread(TAG).apply { start() }
    val handler = Handler(thread.looper)
    return VerboseDiagnosticsController(
        HandlerTaskScheduler(handler),
        writeSnapshot = { handler.post(snapshotRunnable) },
        writeStats = { handler.post(statsRunnable) },
        // A write posted but not yet run when the debug log ends is dropped, not run late.
        onStop = {
          handler.removeCallbacks(snapshotRunnable)
          handler.removeCallbacks(statsRunnable)
        },
      )
      .also { controller = it }
  }

  private fun logSnapshotSections() {
    val context = appContext ?: return
    LogUtils.v(TAG, "== Diagnostics snapshot in the log ==")
    LogUtils.v(TAG, "%s", DiagnosticsExport.performanceStatsStatusLine())
    // Line by line: a single log call would be truncated by logcat for a block this size, and
    // each snapshot line is short. The section headers delimit the block.
    for (line in DiagnosticsExport.buildSnapshotSections(context).lines()) {
      if (line.isNotEmpty()) {
        LogUtils.v(TAG, "%s", line)
      }
    }
    LogUtils.v(TAG, "== End of diagnostics snapshot ==")
  }

  private fun logPerformanceStats() {
    if (!Performance.getInstance().computeStatsEnabled) {
      return
    }
    // Forwards to the log under the Performance tag, exactly as Performance's own displays do.
    DiagnosticsExport.displayPerformanceStats(
      Logger { format, args -> LogUtils.v(PERFORMANCE_TAG, format, *(args ?: emptyArray())) }
    )
  }

  private class HandlerTaskScheduler(private val handler: Handler) : DiagnosticsTaskScheduler {
    override fun schedule(
      delayMillis: Long,
      task: () -> Unit,
    ): DiagnosticsTaskScheduler.ScheduledTask {
      val runnable = Runnable { task() }
      handler.postDelayed(runnable, delayMillis)
      return DiagnosticsTaskScheduler.ScheduledTask { handler.removeCallbacks(runnable) }
    }
  }
}
