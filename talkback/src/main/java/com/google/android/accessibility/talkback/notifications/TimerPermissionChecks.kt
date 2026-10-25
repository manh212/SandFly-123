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

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.notificationlist.NotificationListService
import com.google.android.accessibility.talkback.permission.PermissionUtils
import com.google.android.accessibility.talkback.selector.ScreenBrightness
import com.google.android.accessibility.talkback.shizuku.PlayInstaller
import com.google.android.accessibility.talkback.shizuku.ShizukuShell

/**
 * The permissions SandFly stands on, checked and asked for in the open: enabling a feature
 * checks what it needs right then, and the initial setup walks through all of them one by one.
 * A spoken time is a system alarm clock, which needs no permission of its own; what can still
 * lose it is the phone restricting SandFly while it rests, so the battery exemption is here.
 * The rest are what the features stand on: reading notifications, posting SandFly's own, the
 * microphone dictation listens with, the phone state that following calls reads, installing
 * the updates the updater downloads, and writing the system settings the brightness control
 * changes.
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

  /** Whether the phone has a microphone at all; without one there is nothing to ask for. */
  fun hasMicrophone(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

  /** Whether SandFly may listen, which dictation stands on. */
  fun microphoneStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  /** Asks for the microphone in place, with the system's own dialog. */
  fun requestMicrophone(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.RECORD_AUDIO)
  }

  /** Whether the phone has telephony at all; without it there is no call state to read. */
  fun hasTelephony(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

  /** Whether SandFly may read the phone state, which following calls stands on. */
  fun phoneStateStatus(context: Context): Status =
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  /** Asks for the phone state in place, with the system's own dialog. */
  fun requestPhoneState(context: Context) {
    PermissionUtils.requestPermissions(context, Manifest.permission.READ_PHONE_STATE)
  }

  /**
   * Whether what the updater downloads can be installed, by the updater's own test: SandFly
   * may install packages itself, or the install goes through Shizuku and needs no such leave.
   */
  fun installStatus(context: Context): Status =
    if (
      context.packageManager.canRequestPackageInstalls() ||
        (PlayInstaller.isEnabled(context) && ShizukuShell.isAllowed())
    ) {
      Status.GRANTED
    } else {
      Status.MISSING
    }

  /** Opens the system's page for letting SandFly install the apps it downloads. */
  fun openInstallSettings(context: Context) {
    try {
      context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
    }
  }

  /** Whether SandFly may change system settings, which the brightness control writes with. */
  fun brightnessStatus(context: Context): Status =
    if (ScreenBrightness.canWrite(context)) Status.GRANTED else Status.MISSING

  /** Opens the system's page for letting SandFly change system settings. */
  fun openBrightnessSettings(context: Context) {
    try {
      ScreenBrightness.requestWritePermission(context)
    } catch (e: ActivityNotFoundException) {
      // No page to open on this phone.
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
