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

package com.google.android.accessibility.talkback.migration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Turns SandFly off when Meridian says it is on, so two screen readers never speak at once.
 *
 * Any app can send the broadcast, so it only acts when Meridian, signed with its own key, is
 * installed and is an enabled accessibility service.
 */
class HandOverReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != AppIdMove.ACTION_HAND_OVER) {
      return
    }
    // Only the old app hands over.
    if (context.packageName != AppIdMove.OLD_PACKAGE || !isSandFlyOn(context)) {
      return
    }
    val service = TalkBackService.getInstance()
    if (service == null) {
      LogUtils.i(TAG, "Meridian is on, and SandFly is already off")
      return
    }
    LogUtils.i(TAG, "Meridian is on, so SandFly turns itself off")
    service.disableSelf()
  }

  private fun isSandFlyOn(context: Context): Boolean {
    if (
      !AppIdMove.isSignedBy(context.packageManager, AppIdMove.NEW_PACKAGE, AppIdMove.NEW_CERT_SHA256)
    ) {
      LogUtils.w(TAG, "Ignored a hand-over: Meridian is not installed with its own key")
      return false
    }
    val enabled =
      Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return ComponentLists.contains(enabled, AppIdMove.serviceName(AppIdMove.NEW_PACKAGE))
  }

  private companion object {
    const val TAG = "HandOverReceiver"
  }
}
