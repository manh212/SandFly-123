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

package com.google.android.accessibility.talkback.contextmenu.radial

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.Region
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.ActorState
import com.google.android.accessibility.talkback.ActorStateWritable
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.PassThroughModeActor
import com.google.android.accessibility.talkback.contextmenu.ContextMenu
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class RadialMenuControllerTest {
  class RecordingService : AccessibilityService() {
    val regions = mutableListOf<Rect>()

    override fun setTouchExplorationPassthroughRegion(displayId: Int, region: Region) {
      regions.add(Rect(region.bounds))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
  }

  private lateinit var service: RecordingService
  private lateinit var passThroughActor: PassThroughModeActor
  private lateinit var controller: RadialMenuController
  private var cancellations = 0

  @Before
  fun setUp() {
    service = Robolectric.buildService(RecordingService::class.java).create().get()
    setEnabled(true)
    passThroughActor = PassThroughModeActor(service)
    controller = newController()
  }

  private fun setEnabled(enabled: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(service)
      .edit()
      .putBoolean(service.getString(R.string.pref_radial_menu_key), enabled)
      .commit()
  }

  private fun newController(): RadialMenuController {
    val state =
      ActorState(
        ActorStateWritable(
          null, null, null, null, null, null, null, null, null,
          passThroughActor.state, null, null, null, null,
        )
      )
    return RadialMenuController(
      service,
      Pipeline.FeedbackReturner { feedback ->
        feedback.failovers().forEach { part ->
          val mode = part.passThroughMode()
          if (mode?.action() == Feedback.PassThroughMode.Action.LOCK_PASS_THROUGH) {
            passThroughActor.lockTouchExplorePassThrough(mode.region())
          }
        }
        true
      },
      state,
      object : RadialMenuController.Listener {
        override fun onItemSelected(item: ContextMenuItem) {}
        override fun onCancelled() {
          cancellations++
        }
      },
    )
  }

  private fun showMenu() {
    val menu = ContextMenu(service)
    menu.add(0, 1, 0, "Test action")
    controller.show("Test menu", menu)
    assertTrue(controller.isShowing)
  }

  @Test
  fun dismissReleasesTouchRegionAndCanRunTwice() {
    showMenu()
    assertTrue(passThroughActor.state.isPassThroughModeActive)
    assertFalse(service.regions.last().isEmpty)

    controller.dismiss()
    controller.dismiss()

    assertFalse(controller.isShowing)
    assertFalse(passThroughActor.state.isPassThroughModeActive)
    assertEquals(Rect(), service.regions.last())
    assertEquals(2, service.regions.size)
  }

  @Test
  fun dismissClearsRegionEvenAfterOverlayWasDetachedExternally() {
    showMenu()
    // Simulate a window disappearing without notifying the controller.
    val field = RadialMenuController::class.java.getDeclaredField("view")
    field.isAccessible = true
    val menuView = field.get(controller) as View
    service.getSystemService(WindowManager::class.java).removeViewImmediate(menuView)
    field.set(controller, null)

    controller.dismiss()

    assertFalse(controller.isShowing)
    assertFalse(passThroughActor.state.isPassThroughModeActive)
    assertEquals(Rect(), service.regions.last())
  }

  @Test
  fun watchdogClosesStuckMenuEvenWithoutTouchEvents() {
    showMenu()
    shadowOf(Looper.getMainLooper())
      .idleFor(Duration.ofMillis(RadialMenuController.MAX_OPEN_TIME_MS - 1))
    assertTrue(controller.isShowing)

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

    assertFalse(controller.isShowing)
    assertFalse(passThroughActor.state.isPassThroughModeActive)
    assertEquals(Rect(), service.regions.last())
    assertEquals(1, cancellations)
  }

  @Test
  fun disablingSettingPreventsOverlayFromReopening() {
    setEnabled(false)
    val menu = ContextMenu(service)
    menu.add(0, 1, 0, "Test action")

    controller.show("Test menu", menu)

    assertFalse(controller.isShowing)
    assertTrue(service.regions.isEmpty())
  }

  @Test
  fun teardownThenRestartUsesAFreshWindowAndTouchRegion() {
    showMenu()
    // Service unbind and destruction call dismiss before the feedback pipeline shuts down.
    controller.dismiss()
    assertFalse(passThroughActor.state.isPassThroughModeActive)

    controller = newController()
    showMenu()
    controller.dismiss()

    assertFalse(controller.isShowing)
    assertFalse(passThroughActor.state.isPassThroughModeActive)
    assertEquals(Rect(), service.regions.last())
    assertEquals(4, service.regions.size)
  }
}
