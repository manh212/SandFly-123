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

import com.google.android.accessibility.talkback.report.VerboseDiagnosticsController.Companion.STATS_INTERVAL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerboseDiagnosticsControllerTest {

  /** A scheduler that records tasks instead of running them, so tests drive time manually. */
  private class FakeScheduler : DiagnosticsTaskScheduler {
    val tasks = mutableListOf<FakeTask>()

    inner class FakeTask(val delayMillis: Long, private val task: () -> Unit) :
      DiagnosticsTaskScheduler.ScheduledTask {
      var cancelled = false
        private set

      override fun cancel() {
        cancelled = true
      }

      /** Runs the task the way a real scheduler would: not at all once cancelled. */
      fun fire() {
        if (!cancelled) {
          task()
        }
      }
    }

    override fun schedule(
      delayMillis: Long,
      task: () -> Unit,
    ): DiagnosticsTaskScheduler.ScheduledTask {
      val fakeTask = FakeTask(delayMillis, task)
      tasks += fakeTask
      return fakeTask
    }

    fun pendingTasks(): List<FakeTask> = tasks.filter { !it.cancelled }
  }

  private val scheduler = FakeScheduler()
  private var snapshotWrites = 0
  private var statsWrites = 0
  private val controller =
    VerboseDiagnosticsController(
      scheduler,
      writeSnapshot = { snapshotWrites++ },
      writeStats = { statsWrites++ },
    )

  @Test
  fun belowVerboseFromColdStart_writesNothing() {
    controller.syncWithLogLevel(verbose = false)

    assertFalse(controller.isActive)
    assertEquals(0, snapshotWrites)
    assertEquals(0, statsWrites)
    assertEquals(0, scheduler.tasks.size)
  }

  @Test
  fun verbose_writesSnapshotAndStatsOnce_andSchedulesTheCadence() {
    controller.syncWithLogLevel(verbose = true)

    assertTrue(controller.isActive)
    assertEquals(1, snapshotWrites)
    assertEquals(1, statsWrites)
    assertEquals(1, scheduler.pendingTasks().size)
    assertEquals(STATS_INTERVAL_MS, scheduler.pendingTasks()[0].delayMillis)
  }

  @Test
  fun verboseAgainWhileActive_writesNothingMore_andKeepsOneSchedule() {
    controller.syncWithLogLevel(verbose = true)
    controller.syncWithLogLevel(verbose = true)
    controller.syncWithLogLevel(verbose = true)

    assertEquals(1, snapshotWrites)
    assertEquals(1, statsWrites)
    assertEquals(1, scheduler.tasks.size)
    assertEquals(1, scheduler.pendingTasks().size)
  }

  @Test
  fun cadenceTick_writesStatsAgain_andReschedulesExactlyOnce() {
    controller.syncWithLogLevel(verbose = true)

    scheduler.pendingTasks()[0].fire()

    assertEquals(2, statsWrites)
    assertEquals(1, snapshotWrites)
    assertEquals(1, scheduler.pendingTasks().size)
    assertEquals(STATS_INTERVAL_MS, scheduler.pendingTasks()[0].delayMillis)
  }

  @Test
  fun leavingVerbose_cancelsThePendingWrite() {
    controller.syncWithLogLevel(verbose = true)
    val pending = scheduler.pendingTasks()[0]

    controller.syncWithLogLevel(verbose = false)

    assertFalse(controller.isActive)
    assertTrue(pending.cancelled)
    assertEquals(0, scheduler.pendingTasks().size)
    // Even if a stale task were fired late, it must not write after stop.
    pending.fire()
    assertEquals(1, statsWrites)
  }

  @Test
  fun togglingLevels_reactivatesCleanly_withASingleCadence() {
    controller.syncWithLogLevel(verbose = true)
    controller.syncWithLogLevel(verbose = false)
    controller.syncWithLogLevel(verbose = true)
    controller.syncWithLogLevel(verbose = false)
    controller.syncWithLogLevel(verbose = true)

    // One snapshot per activation, one immediate stats write per activation, one live schedule.
    assertEquals(3, snapshotWrites)
    assertEquals(3, statsWrites)
    assertEquals(1, scheduler.pendingTasks().size)

    scheduler.pendingTasks()[0].fire()
    assertEquals(4, statsWrites)
    assertEquals(1, scheduler.pendingTasks().size)
  }
}
