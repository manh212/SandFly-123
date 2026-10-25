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

package com.google.android.accessibility.talkback.focusmanagement

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.Region
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService

/**
 * Pins the one-shot keyboard-window-present callback: it is the trigger for the live re-apply
 * of the typing preferences the service owes after an update, so it must run exactly once,
 * the first time the passthrough region is armed for the keyboard window, and never while the
 * keyboard is absent or single-tap typing is disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [SingleTapTypingControllerTest.WindowsShadow::class])
class SingleTapTypingControllerTest {

  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
  }

  /** Serves the windows a test puts on screen and records the passthrough regions sent. */
  @Implements(AccessibilityService::class)
  class WindowsShadow : ShadowAccessibilityService() {
    var onScreen: List<AccessibilityWindowInfo> = emptyList()
    val regionsSent = mutableListOf<Region>()

    @Implementation
    override fun getWindows(): List<AccessibilityWindowInfo> = ArrayList(onScreen)

    @Implementation
    fun setGestureDetectionPassthroughRegion(displayId: Int, region: Region) {
      regionsSent += region
    }
  }

  private lateinit var service: TestService
  private lateinit var windows: WindowsShadow
  private lateinit var controller: SingleTapTypingController
  private val callbackRuns = AtomicInteger(0)

  @Before
  fun setUp() {
    service = Robolectric.setupService(TestService::class.java)
    windows = Shadow.extract(service)
    controller = SingleTapTypingController(service)
    callbackRuns.set(0)
  }

  @Test
  fun keyboardAppears_callbackRunsExactlyOnce() {
    windows.onScreen = listOf(appWindow())
    controller.setEnabled(true)
    controller.setKeyboardWindowPresentCallback { callbackRuns.incrementAndGet() }

    controller.onAccessibilityEvent(windowsChanged())
    assertEquals(0, callbackRuns.get())

    windows.onScreen = listOf(appWindow(), keyboardWindow())
    controller.onAccessibilityEvent(windowsChanged())
    assertEquals(1, callbackRuns.get())

    controller.onAccessibilityEvent(windowsChanged())
    assertEquals(1, callbackRuns.get())
  }

  @Test
  fun noKeyboard_callbackNeverRuns() {
    windows.onScreen = listOf(appWindow())
    controller.setEnabled(true)
    controller.setKeyboardWindowPresentCallback { callbackRuns.incrementAndGet() }

    controller.onAccessibilityEvent(windowsChanged())
    controller.onAccessibilityEvent(windowsChanged())

    assertEquals(0, callbackRuns.get())
  }

  @Test
  fun disabled_callbackDoesNotRunEvenWithKeyboardPresent() {
    windows.onScreen = listOf(keyboardWindow())
    controller.setKeyboardWindowPresentCallback { callbackRuns.incrementAndGet() }

    controller.onAccessibilityEvent(windowsChanged())

    assertEquals(0, callbackRuns.get())
  }

  @Test
  fun keyboardAlreadyPresent_callbackRunsWhenEnabled() {
    windows.onScreen = listOf(keyboardWindow())
    controller.setKeyboardWindowPresentCallback { callbackRuns.incrementAndGet() }

    controller.setEnabled(true)

    assertEquals(1, callbackRuns.get())
  }

  @Test
  fun keyboardPresent_regionIsArmedWithTheKeyboardBounds() {
    windows.onScreen = listOf(keyboardWindow())

    controller.setEnabled(true)

    assertEquals(1, windows.regionsSent.size)
    assertEquals(KEYBOARD_BOUNDS, windows.regionsSent[0].bounds)
  }

  private fun windowsChanged(): AccessibilityEvent =
    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)

  private fun appWindow() =
    window(AccessibilityWindowInfo.TYPE_APPLICATION, Rect(0, 0, 1080, 2340))

  private fun keyboardWindow() =
    window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, KEYBOARD_BOUNDS)

  private fun window(type: Int, bounds: Rect): AccessibilityWindowInfo {
    val window = AccessibilityWindowInfo()
    val shadow = shadowOf(window)
    shadow.setType(type)
    shadow.setBoundsInScreen(bounds)
    return window
  }

  private companion object {
    val KEYBOARD_BOUNDS = Rect(0, 1500, 1080, 2340)
  }
}
