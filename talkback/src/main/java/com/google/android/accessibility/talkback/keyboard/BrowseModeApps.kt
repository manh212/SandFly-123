/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.keyboard

import android.content.Context
import androidx.core.content.edit
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils

object BrowseModeApps {
  const val PREF_MODE = "pref_browse_mode_remember_mode"
  const val ALWAYS = "always"
  const val PER_APP = "per_app"
  const val NONE = "none"

  private const val PREF_ON = "pref_browse_mode_on_apps"
  private const val PREF_OFF = "pref_browse_mode_off_apps"
  private const val PREF_GLOBAL_ENABLED = "pref_browse_mode_global_enabled"

  @JvmStatic
  fun mode(context: Context): String {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.getString(PREF_MODE, null)?.let {
      if (it in setOf(ALWAYS, PER_APP, NONE)) return it
    }
    val previous = prefs.getBoolean(
      context.getString(R.string.pref_browse_mode_per_app_key),
      context.resources.getBoolean(R.bool.pref_browse_mode_per_app_default),
    )
    val selected = if (previous) PER_APP else NONE
    prefs.edit { putString(PREF_MODE, selected) }
    return selected
  }

  @JvmStatic
  fun isOn(context: Context): Boolean = mode(context) == PER_APP

  @JvmStatic
  fun rememberedGlobal(context: Context): Boolean? {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    return if (prefs.contains(PREF_GLOBAL_ENABLED)) prefs.getBoolean(PREF_GLOBAL_ENABLED, false) else null
  }

  @JvmStatic
  fun rememberGlobal(context: Context, on: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(context).edit { putBoolean(PREF_GLOBAL_ENABLED, on) }
  }

  @JvmStatic
  fun remembered(context: Context, app: String): Boolean? {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    return when {
      prefs.getStringSet(PREF_ON, null)?.contains(app) == true -> true
      prefs.getStringSet(PREF_OFF, null)?.contains(app) == true -> false
      else -> null
    }
  }

  @JvmStatic
  fun remember(context: Context, app: String, on: Boolean) {
    if (!isOn(context)) return
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val onApps = HashSet(prefs.getStringSet(PREF_ON, null) ?: emptySet())
    val offApps = HashSet(prefs.getStringSet(PREF_OFF, null) ?: emptySet())
    if (on) {
      onApps += app
      offApps -= app
    } else {
      offApps += app
      onApps -= app
    }
    prefs.edit {
      putStringSet(PREF_ON, onApps)
      putStringSet(PREF_OFF, offApps)
    }
  }
}
