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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.google.android.accessibility.talkback.notificationlist.NotificationListService

/**
 * The permissions the timer features and SandFly itself stand on, checked and asked for in the
 * open: enabling a timer feature checks them right then, and the initial setup walks through
 * them one by one. A spoken time is a system alarm clock, which needs no permission of its own;
 * what can still lose it is the phone restricting SandFly while it rests, so the battery
 * exemption is the one checked here.
 */
object TimerPermissionChecks {
  /** A permission's state as it could be checked: held, not held, or not checkable here. */
  enum class Status {
    GRANTED,
    MISSING,
    UNKNOWN,
  }

  /** Whether the phone leaves SandFly alone while it rests, as the system itself reports it. */
  fun batteryStatus(context: Context): Status {
    val power = context.getSystemService(PowerManager::class.java) ?: return Status.UNKNOWN
    return if (power.isIgnoringBatteryOptimizations(context.packageName)) Status.GRANTED
    else Status.MISSING
  }

  /** Whether SandFly may read notifications, which the daily summary is read from. */
  fun notificationAccessStatus(context: Context): Status =
    if (NotificationListService.isAccessGranted(context)) Status.GRANTED else Status.MISSING

  /** Whether SandFly's own notifications are allowed. */
  fun appNotificationsStatus(context: Context): Status =
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) Status.GRANTED
    else Status.MISSING

  /** Whether SandFly's screen reader service is switched on. */
  fun serviceStatus(context: Context): Status {
    val enabled =
      Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ) ?: return Status.MISSING
    // The setting lists the switched-on services as package/class pairs; SandFly has the one.
    return if (enabled.split(':').any { it.startsWith(context.packageName + "/") }) {
      Status.GRANTED
    } else {
      Status.MISSING
    }
  }

  /**
   * Asks the system to exempt SandFly from battery optimization, at once when it can, and by
   * the list of apps when the direct ask is not there. Does nothing while SandFly is exempt.
   */
  fun promptForBatteryExemption(context: Context) {
    if (batteryStatus(context) != Status.MISSING) return
    val direct =
      Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:" + context.packageName),
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(direct)
      return
    } catch (e: ActivityNotFoundException) {
      // Fall through to the list.
    }
    try {
      context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // Nowhere to ask; the initial setup will say the state it found.
    }
  }

  /** Opens the system's notification settings for SandFly itself. */
  fun openAppNotificationSettings(context: Context) {
    try {
      context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }

  /** Opens the system's accessibility settings, where the service is switched on. */
  fun openAccessibilitySettings(context: Context) {
    try {
      context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }
}
