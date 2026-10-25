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

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks.Status

/**
 * Initial setup: every permission SandFly stands on, one at a time. A step the phone already
 * grants is passed over; dealing with a step — in the system's own dialog or settings page —
 * moves the walk on by itself when this screen comes back, so there is no button to press
 * between one permission and the next. Each step says where the walk stands and the state it
 * finds — for the battery, the state the system itself reports, never a guess. A step can be
 * passed by, the setup can be left at any time, and the end lists every state as it stands.
 */
class InitialSetupFragment : NotificationCodeFragment(R.string.timer_setup_title) {
  private val flow = SetupFlow()

  /** One permission: what it is called, what its states say, and where it is given. */
  private class Step(
    @StringRes val title: Int,
    @StringRes val grantedText: Int,
    @StringRes val missingText: Int,
    @StringRes val openLabel: Int,
    val status: (Context) -> Status,
    val open: (Context) -> Unit,
    val applicable: (Context) -> Boolean = { true },
  )

  override fun onPause() {
    super.onPause()
    flow.leaveScreen()
  }

  override fun onResume() {
    // Before the screen is built again, so that it is built where the walk has moved to.
    val context = requireContext()
    flow.returnToScreen(steps().count { it.applicable(context) })
    super.onResume()
  }

  override fun build(screen: PreferenceGroup) {
    val context = requireContext()
    val steps = steps().filter { it.applicable(context) }
    flow.settle(steps.map { it.status(context) })
    if (flow.atSummary(steps.size)) {
      buildSummary(screen, steps)
      return
    }
    buildStep(screen, steps, steps[flow.step])
  }

  private fun buildStep(screen: PreferenceGroup, steps: List<Step>, current: Step) {
    val context = requireContext()
    val status = current.status(context)
    val category = screen.category(current.title)
    category.action(
      getString(
        R.string.timer_setup_progress,
        flow.step + 1,
        steps.size,
        getString(statusText(status)),
      ),
      if (status == Status.UNKNOWN) null else getString(detailText(current, status)),
    ) {
      rebuild()
    }
    if (status != Status.GRANTED) {
      category.action(getString(current.openLabel)) {
        flow.tapOpen()
        current.open(context)
      }
    }
    if (flow.reviewing) {
      category.action(getString(R.string.timer_setup_back)) {
        flow.backToSummary(steps.size)
        rebuild()
      }
    } else {
      category.action(getString(R.string.timer_setup_skip)) {
        flow.skip()
        rebuild()
      }
      category.action(getString(R.string.timer_setup_exit)) { leave() }
    }
  }

  /** Every permission with the state it stands in; tapping one goes back to its step. */
  private fun buildSummary(screen: PreferenceGroup, steps: List<Step>) {
    val context = requireContext()
    val category = screen.category(R.string.timer_setup_summary_title)
    steps.forEachIndexed { index, item ->
      category.action(getString(item.title), getString(statusText(item.status(context)))) {
        flow.review(index)
        rebuild()
      }
    }
    category.action(getString(R.string.timer_setup_done)) { leave() }
  }

  private fun leave() {
    flow.finish()
    runCatching { parentFragmentManager.popBackStack() }
  }

  private fun statusText(status: Status): Int =
    when (status) {
      Status.GRANTED -> R.string.timer_setup_status_granted
      Status.MISSING -> R.string.timer_setup_status_missing
      Status.UNKNOWN -> R.string.timer_setup_status_unknown
    }

  private fun detailText(step: Step, status: Status): Int =
    if (status == Status.GRANTED) step.grantedText else step.missingText

  private fun steps(): List<Step> =
    listOf(
      Step(
        R.string.timer_setup_service_title,
        R.string.timer_setup_service_granted,
        R.string.timer_setup_service_missing,
        R.string.timer_setup_service_open,
        { TimerPermissionChecks.serviceStatus(it) },
        { TimerPermissionChecks.openAccessibilitySettings(it) },
      ),
      Step(
        R.string.timer_setup_battery_title,
        R.string.timer_setup_battery_granted,
        R.string.timer_setup_battery_missing,
        R.string.timer_setup_battery_open,
        { TimerPermissionChecks.batteryStatus(it) },
        { TimerPermissionChecks.promptForBatteryExemption(it) },
      ),
      Step(
        R.string.timer_setup_microphone_title,
        R.string.timer_setup_microphone_granted,
        R.string.timer_setup_microphone_missing,
        R.string.timer_setup_microphone_open,
        { TimerPermissionChecks.microphoneStatus(it) },
        { TimerPermissionChecks.requestMicrophone(it) },
        { TimerPermissionChecks.hasMicrophone(it) },
      ),
      Step(
        R.string.timer_setup_phone_title,
        R.string.timer_setup_phone_granted,
        R.string.timer_setup_phone_missing,
        R.string.timer_setup_phone_open,
        { TimerPermissionChecks.phoneStateStatus(it) },
        { TimerPermissionChecks.requestPhoneState(it) },
        { TimerPermissionChecks.hasTelephony(it) },
      ),
      Step(
        R.string.timer_setup_access_title,
        R.string.timer_setup_access_granted,
        R.string.timer_setup_access_missing,
        R.string.timer_setup_access_open,
        { TimerPermissionChecks.notificationAccessStatus(it) },
        { NotificationXmlFragment.openAccessSettings(it) },
      ),
      Step(
        R.string.timer_setup_notifications_title,
        R.string.timer_setup_notifications_granted,
        R.string.timer_setup_notifications_missing,
        R.string.timer_setup_notifications_open,
        { TimerPermissionChecks.appNotificationsStatus(it) },
        { TimerPermissionChecks.openAppNotificationSettings(it) },
      ),
      Step(
        R.string.timer_setup_install_title,
        R.string.timer_setup_install_granted,
        R.string.timer_setup_install_missing,
        R.string.timer_setup_install_open,
        { TimerPermissionChecks.installStatus(it) },
        { TimerPermissionChecks.openInstallSettings(it) },
      ),
      Step(
        R.string.timer_setup_brightness_title,
        R.string.timer_setup_brightness_granted,
        R.string.timer_setup_brightness_missing,
        R.string.timer_setup_brightness_open,
        { TimerPermissionChecks.brightnessStatus(it) },
        { TimerPermissionChecks.openBrightnessSettings(it) },
      ),
    )
}
