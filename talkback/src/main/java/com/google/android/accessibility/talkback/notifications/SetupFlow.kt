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

package com.google.android.accessibility.talkback.notifications

import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks.Status

/**
 * Where the initial setup stands in its walk, kept apart from the screen so the walk itself
 * can be tested. There is no button between one permission and the next: dealing with a step
 * moves the walk on by itself when the screen comes back, steps the phone already grants are
 * passed over, and the end of the walk is the summary.
 */
class SetupFlow {
  /** The step showing, or the number of steps when the walk stands at its summary. */
  var step = 0
    private set

  /** Whether the step showing was opened from the summary, to be gone back to. */
  var reviewing = false
    private set

  private var openTapped = false
  private var wentAway = false

  /** Passes over the steps whose state is already granted. Never moves a step being reviewed. */
  fun settle(statuses: List<Status>) {
    if (reviewing) return
    while (step < statuses.size && statuses[step] == Status.GRANTED) {
      step++
    }
  }

  /** The step's own ask was tapped: a dialog or a system page is on its way. */
  fun tapOpen() {
    openTapped = true
  }

  /** The screen went away; only a screen that really went can bring the walk back moved on. */
  fun leaveScreen() {
    if (openTapped) wentAway = true
  }

  /**
   * The screen is showing again. If a step was opened and the screen really went away, the
   * walk moves on — to the next step, or back to the summary for a step opened out of it. A
   * tap that never took the screen anywhere (the phone has no page for it) moves nothing,
   * and the half-made move is dropped either way.
   */
  fun returnToScreen(stepCount: Int) {
    val advance = openTapped && wentAway
    openTapped = false
    wentAway = false
    if (!advance) return
    if (reviewing) {
      reviewing = false
      step = stepCount
    } else {
      step++
    }
  }

  /** Passes the step showing by, at once. */
  fun skip() {
    step++
  }

  /** Opens the step at [index] out of the summary. */
  fun review(index: Int) {
    step = index
    reviewing = true
  }

  /** Goes back to the summary from a step opened out of it. */
  fun backToSummary(stepCount: Int) {
    reviewing = false
    step = stepCount
  }

  /** Starts the walk over, as when the setup is left and opened again. */
  fun finish() {
    step = 0
    reviewing = false
    openTapped = false
    wentAway = false
  }

  /** Whether the walk stands at its summary. */
  fun atSummary(stepCount: Int): Boolean = !reviewing && step >= stepCount
}
