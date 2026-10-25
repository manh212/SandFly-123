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

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.google.android.accessibility.talkback.notificationlist.NotificationListService
import com.google.android.accessibility.talkback.notifications.TimerPermissionChecks.Status
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The initial setup's readings: each permission's state is the system's own answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerPermissionChecksTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    Shadows.shadowOf(context.getSystemService(PowerManager::class.java))
      .setIgnoringBatteryOptimizations(context.packageName, false)
    Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
      .setNotificationListenerAccessGranted(
        ComponentName(context, NotificationListService::class.java),
        false,
      )
    Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
      .setNotificationsEnabled(true)
    Settings.Secure.putString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      "",
    )
  }

  @Test
  fun battery_followsTheSystemsOwnAnswer() {
    assertEquals(Status.MISSING, TimerPermissionChecks.batteryStatus(context))

    Shadows.shadowOf(context.getSystemService(PowerManager::class.java))
      .setIgnoringBatteryOptimizations(context.packageName, true)

    assertEquals(Status.GRANTED, TimerPermissionChecks.batteryStatus(context))
  }

  @Test
  fun notificationAccess_followsTheGrant() {
    assertEquals(Status.MISSING, TimerPermissionChecks.notificationAccessStatus(context))

    Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
      .setNotificationListenerAccessGranted(
        ComponentName(context, NotificationListService::class.java),
        true,
      )

    assertEquals(Status.GRANTED, TimerPermissionChecks.notificationAccessStatus(context))
  }

  @Test
  fun appNotifications_followWhetherTheyAreAllowed() {
    assertEquals(Status.GRANTED, TimerPermissionChecks.appNotificationsStatus(context))

    Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
      .setNotificationsEnabled(false)

    assertEquals(Status.MISSING, TimerPermissionChecks.appNotificationsStatus(context))
  }

  @Test
  fun service_followsTheEnabledServicesSetting() {
    assertEquals(Status.MISSING, TimerPermissionChecks.serviceStatus(context))

    Settings.Secure.putString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      "${context.packageName}/.TalkBackService",
    )

    assertEquals(Status.GRANTED, TimerPermissionChecks.serviceStatus(context))
  }
}
