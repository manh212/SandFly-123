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

package com.google.android.accessibility.talkback.timer

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.notifications.NotificationCodeFragment
import com.google.android.accessibility.talkback.notifications.NotificationXmlFragment
import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks
import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks.Status

/**
 * Initial setup: the permissions SandFly needs, one at a time, in the order they are missed.
 * Each step says the state it finds — for the battery, the state the system itself reports,
 * never a guess — opens the system's own place to change it, and is checked again on the way
 * back. A step can be passed by, and the end lists every state as it stands.
 */
class TimerSetupFragment : NotificationCodeFragment(R.string.timer_setup_title) {
  private var step = 0

  /** One permission: what it is called, what its states say, and where it is given. */
  private class Step(
    @StringRes val title: Int,
    @StringRes val grantedText: Int,
    @StringRes val missingText: Int,
    @StringRes val openLabel: Int,
    val status: (Context) -> Status,
    val open: (Context) -> Unit,
  )

  override fun build(screen: PreferenceGroup) {
    val steps = steps()
    if (step >= steps.size) {
      buildSummary(screen, steps)
      return
    }
    val context = requireContext()
    val current = steps[step]
    val status = current.status(context)
    val category = screen.category(current.title)
    category.action(
      getString(statusText(status)),
      if (status == Status.UNKNOWN) null else getString(detailText(current, status)),
    ) {
      rebuild()
    }
    if (status != Status.GRANTED) {
      category.action(getString(current.openLabel)) { current.open(context) }
    }
    val last = step == steps.size - 1
    category.action(getString(if (last) R.string.timer_setup_finish else R.string.timer_setup_next)) {
      step++
      rebuild()
    }
  }

  /** Every permission with the state it stands in; tapping one goes back to its step. */
  private fun buildSummary(screen: PreferenceGroup, steps: List<Step>) {
    val context = requireContext()
    val category = screen.category(R.string.timer_setup_summary_title)
    steps.forEachIndexed { index, item ->
      category.action(getString(item.title), getString(statusText(item.status(context)))) {
        step = index
        rebuild()
      }
    }
    category.action(getString(R.string.timer_setup_done)) {
      step = 0
      runCatching { parentFragmentManager.popBackStack() }
    }
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
        R.string.timer_setup_battery_title,
        R.string.timer_setup_battery_granted,
        R.string.timer_setup_battery_missing,
        R.string.timer_setup_battery_open,
        { TimerPermissionChecks.batteryStatus(it) },
        { TimerPermissionChecks.promptForBatteryExemption(it) },
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
        R.string.timer_setup_service_title,
        R.string.timer_setup_service_granted,
        R.string.timer_setup_service_missing,
        R.string.timer_setup_service_open,
        { TimerPermissionChecks.serviceStatus(it) },
        { TimerPermissionChecks.openAccessibilitySettings(it) },
      ),
    )
}
