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

package com.google.android.accessibility.talkback

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.accessibility.talkback.report.DiagnosticsExport
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Rewrites the main preferences file once, on the first start after the app version changes.
 *
 * After an update, typing with direct key input lags until the owner changes any setting —
 * which rewrites the whole preferences file — and restarts the service, which reads it back.
 * No single stored value is wrong: a key-by-key digest of the sick and healed files differs
 * only in the setting the owner flipped and in the restart's own bookkeeping, so the fault
 * lives in the state the update leaves the file in and in the snapshot the process builds from
 * it. This performs that same rewrite automatically, early enough that the normal startup
 * then reads the healed file, so the cure no longer needs the manual ritual.
 *
 * The rewrite alone turned out not to be the whole cure: it runs before the service exists,
 * when re-applying the typing preferences cannot take effect — there is no keyboard window
 * yet, so the passthrough region sync is a no-op, and the drifted state sits on the framework
 * side of the service. A live preference change cures the lag because it re-applies those
 * preferences in the running service. So the rewrite also stamps [KEY_PENDING_LIVE_REAPPLY],
 * and the live service performs that re-apply itself, once, the first time the keyboard
 * window is present after the update.
 *
 * At VERBOSE the diagnostics snapshot is written to the log before the rewrite, so an export
 * after a still-sick update carries the pristine, pre-heal state of the file.
 *
 * Nothing here may break startup: any failure is logged and startup continues unhealed.
 */
object PrefsSelfHeal {
  private const val TAG = "PrefsSelfHeal"
  private const val SNAPSHOT_TAG = "VerboseDiagnostics"

  /** The main preferences file's own record of the version it was last rewritten for. */
  const val KEY_HEALED_VERSION = "prefs_self_heal_version_code"

  /** The version the live service still owes a one-time live re-apply of preferences for. */
  const val KEY_PENDING_LIVE_REAPPLY = "prefs_pending_live_reapply_version_code"

  /** How many times this process has actually rewritten the file. Test seam. */
  internal var rewriteCount = 0

  internal enum class Result {
    HEALED,
    NOT_NEEDED,
    FAILED,
  }

  /**
   * The startup entry point, called from [TalkBackApplication] before anything builds state
   * on the preferences. Only the main process heals, as with the settings import that runs
   * just before it: the other processes never own the service's state, and the version marker
   * turns their later starts into no-ops anyway.
   */
  @JvmStatic
  fun healIfVersionChanged(context: Context) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
          context.packageName != Application.getProcessName()) {
        return
      }
      val current = currentVersionCode(context) ?: return
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      heal(prefs, current) { logPreHealSnapshot(context, prefs) }
    } catch (t: Throwable) {
      LogUtils.w(TAG, "Preferences self-heal failed; startup continues unhealed: %s", t)
    }
  }

  internal fun heal(
    prefs: SharedPreferences,
    currentVersionCode: Long,
    logPreHealSnapshot: () -> Unit,
  ): Result {
    return try {
      val healedFor = prefs.all[KEY_HEALED_VERSION] as? Long
      if (healedFor == currentVersionCode) {
        return Result.NOT_NEEDED
      }
      LogUtils.i(
        TAG,
        "Rewriting the main preferences file on the first start for version %d" +
          " (last rewritten for %s)",
        currentVersionCode,
        healedFor?.toString() ?: "none recorded",
      )
      logPreHealSnapshot()
      if (rewrite(prefs, currentVersionCode)) Result.HEALED else Result.FAILED
    } catch (t: Throwable) {
      LogUtils.w(TAG, "Preferences self-heal failed; startup continues unhealed: %s", t)
      Result.FAILED
    }
  }

  /** The version the live service still owes a live re-apply for, or 0 when none is owed. */
  @JvmStatic
  fun pendingLiveReapplyVersion(prefs: SharedPreferences): Long {
    return try {
      prefs.all[KEY_PENDING_LIVE_REAPPLY] as? Long ?: 0L
    } catch (t: Throwable) {
      LogUtils.w(TAG, "Could not read the pending live re-apply marker: %s", t)
      0L
    }
  }

  /** Clears the pending live re-apply marker once the service has performed the re-apply. */
  @JvmStatic
  fun clearPendingLiveReapply(prefs: SharedPreferences) {
    try {
      if (prefs.contains(KEY_PENDING_LIVE_REAPPLY)) {
        prefs.edit().remove(KEY_PENDING_LIVE_REAPPLY).apply()
      }
    } catch (t: Throwable) {
      LogUtils.w(TAG, "Could not clear the pending live re-apply marker: %s", t)
    }
  }

  /**
   * Clears the file and writes every value back with its own type, in one synchronous commit.
   * The same commit stamps the healed-version marker and the live re-apply the service still
   * owes for this version, so the two can never disagree.
   */
  private fun rewrite(prefs: SharedPreferences, currentVersionCode: Long): Boolean {
    val editor = prefs.edit()
    editor.clear()
    for ((key, value) in prefs.all) {
      if (key != KEY_HEALED_VERSION && key != KEY_PENDING_LIVE_REAPPLY) {
        putValue(editor, key, value)
      }
    }
    editor.putLong(KEY_HEALED_VERSION, currentVersionCode)
    editor.putLong(KEY_PENDING_LIVE_REAPPLY, currentVersionCode)
    if (!editor.commit()) {
      LogUtils.w(TAG, "Preferences self-heal could not rewrite the preferences file")
      return false
    }
    rewriteCount++
    return true
  }

  /** Writes one value back with its stored type; an unexpected value is skipped, never fatal. */
  private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
    try {
      when (value) {
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is String -> editor.putString(key, value)
        is Set<*> ->
          if (value.all { it is String }) {
            @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
          } else {
            LogUtils.w(TAG, "Skipping preference %s: a set that is not a string set", key)
          }
        else -> LogUtils.w(TAG, "Skipping preference %s: unexpected value type", key)
      }
    } catch (t: Throwable) {
      LogUtils.w(TAG, "Skipping preference %s: %s", key, t)
    }
  }

  /**
   * Writes the diagnostics snapshot into the log, exactly as [VerboseDiagnosticsLogger] does
   * when the service starts with the debug log on — same sections from the same builder, same
   * tag, one log call per line — but synchronously, so the preferences digest inside it still
   * shows the file as the update left it.
   *
   * The gate is the one the service applies moments later in `reloadDebugLog`:
   * LogUtils' own flag is not loaded yet at this point in startup, so the switch is read from
   * the very preferences being healed.
   */
  private fun logPreHealSnapshot(context: Context, prefs: SharedPreferences) {
    val debugLog =
      try {
        SharedPreferencesUtils.getBooleanPref(
          prefs,
          context.resources,
          R.string.pref_debug_log_key,
          R.bool.pref_debug_log_default,
        )
      } catch (t: Throwable) {
        false
      }
    if (!debugLog) return
    try {
      LogUtils.v(SNAPSHOT_TAG, "== Diagnostics snapshot in the log ==")
      LogUtils.v(SNAPSHOT_TAG, "%s", DiagnosticsExport.performanceStatsStatusLine())
      for (line in DiagnosticsExport.buildSnapshotSections(context).lines()) {
        if (line.isNotEmpty()) {
          LogUtils.v(SNAPSHOT_TAG, "%s", line)
        }
      }
      LogUtils.v(SNAPSHOT_TAG, "== End of diagnostics snapshot ==")
    } catch (t: Throwable) {
      LogUtils.w(TAG, "Could not write the pre-heal diagnostics snapshot: %s", t)
    }
  }

  private fun currentVersionCode(context: Context): Long? {
    return try {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
      else info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
      LogUtils.w(TAG, "Cannot read the app version; skipping the preferences self-heal")
      null
    }
  }
}
