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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The initial setup's walk: no button between one permission and the next — dealing with a
 * step moves on by itself, granted steps are passed over, and the end is the summary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InitialSetupFlowTest {
  private val threeMissing = listOf(Status.MISSING, Status.MISSING, Status.MISSING)

  @Test
  fun grantedSteps_arePassedOver() {
    val flow = SetupFlow()

    flow.settle(listOf(Status.GRANTED, Status.GRANTED, Status.MISSING))

    assertEquals(2, flow.step)
    assertFalse(flow.atSummary(3))
  }

  @Test
  fun allGranted_endsAtTheSummary() {
    val flow = SetupFlow()

    flow.settle(listOf(Status.GRANTED, Status.GRANTED, Status.GRANTED))

    assertEquals(3, flow.step)
    assertTrue(flow.atSummary(3))
  }

  @Test
  fun dealingWithAStep_movesOnByItself() {
    val flow = SetupFlow()
    flow.settle(threeMissing)

    flow.tapOpen()
    flow.leaveScreen()
    flow.returnToScreen(3)

    assertEquals(1, flow.step)
  }

  @Test
  fun dealingWithTheLastStep_endsAtTheSummary() {
    val flow = SetupFlow()
    flow.settle(threeMissing)
    flow.skip()
    flow.skip()

    flow.tapOpen()
    flow.leaveScreen()
    flow.returnToScreen(3)

    assertTrue(flow.atSummary(3))
  }

  @Test
  fun aTapThatNeverLeaves_movesNothing_andIsDropped() {
    val flow = SetupFlow()
    flow.settle(threeMissing)

    // The phone has no page for the ask: the tap happened, the screen never went away.
    flow.tapOpen()
    flow.returnToScreen(3)
    assertEquals(0, flow.step)

    // The half-made move is gone: going away and coming back for another reason stays put.
    flow.leaveScreen()
    flow.returnToScreen(3)
    assertEquals(0, flow.step)
  }

  @Test
  fun comingBackWithoutAnAsk_movesNothing() {
    val flow = SetupFlow()
    flow.settle(threeMissing)

    flow.leaveScreen()
    flow.returnToScreen(3)

    assertEquals(0, flow.step)
  }

  @Test
  fun skip_movesOnAtOnce() {
    val flow = SetupFlow()
    flow.settle(threeMissing)

    flow.skip()

    assertEquals(1, flow.step)
  }

  @Test
  fun aStepOpenedFromTheSummary_goesBackToTheSummaryWhenDealtWith() {
    val flow = SetupFlow()
    flow.review(1)

    flow.tapOpen()
    flow.leaveScreen()
    flow.returnToScreen(3)

    assertFalse(flow.reviewing)
    assertTrue(flow.atSummary(3))
  }

  @Test
  fun settling_neverMovesAStepBeingReviewed() {
    val flow = SetupFlow()
    flow.review(0)

    flow.settle(listOf(Status.GRANTED, Status.MISSING, Status.MISSING))

    assertEquals(0, flow.step)
    assertTrue(flow.reviewing)
  }

  @Test
  fun backToSummary_fromAReviewedStep() {
    val flow = SetupFlow()
    flow.review(2)

    flow.backToSummary(3)

    assertFalse(flow.reviewing)
    assertTrue(flow.atSummary(3))
  }

  @Test
  fun finish_startsTheWalkOver() {
    val flow = SetupFlow()
    flow.settle(threeMissing)
    flow.skip()
    flow.review(1)

    flow.finish()

    assertEquals(0, flow.step)
    assertFalse(flow.reviewing)
  }
}
