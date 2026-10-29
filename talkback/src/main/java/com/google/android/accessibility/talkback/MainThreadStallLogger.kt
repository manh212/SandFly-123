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

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Printer
import com.google.android.accessibility.utils.LatencyExperiments
import com.google.android.accessibility.utils.Performance
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Debug tool that logs the main thread stack while one message blocks it for too long. Filter
 * logcat by the "BacktalkStall" tag to find what TalkBack was waiting on during lag.
 */
internal class MainThreadStallLogger private constructor(private val watchdog: Handler) : Printer {
  private val mainThread = Looper.getMainLooper().thread
  private val logStack = Runnable { logMainThreadStack() }
  @Volatile private var dispatchStartMs = 0L
  private val thresholdMs: Long
    get() = if (LatencyExperiments.Feature.STALL_CAPTURE.enabled()) 50L else STALL_THRESHOLD_MS

  override fun println(message: String) {
    if (message.startsWith(">>>>>")) {
      dispatchStartMs = SystemClock.uptimeMillis()
      watchdog.postDelayed(logStack, thresholdMs)
    } else if (message.startsWith("<<<<<")) {
      watchdog.removeCallbacks(logStack)
      val blockedMs = SystemClock.uptimeMillis() - dispatchStartMs
      if (blockedMs >= thresholdMs) {
        Performance.getInstance().onMainThreadStall(blockedMs, message)
        Log.w(TAG, "Main thread was blocked for $blockedMs ms by $message")
      }
    }
  }

  private fun logMainThreadStack() {
    val stack = mainThread.stackTrace.joinToString(separator = "") { "\n  at $it" }
    val blockedMs = SystemClock.uptimeMillis() - dispatchStartMs
    Performance.getInstance().onMainThreadStall(blockedMs, stack)
    LogUtils.w(TAG, "Main thread blocked for %d ms so far:%s", blockedMs, stack)
    watchdog.postDelayed(logStack, thresholdMs)
  }

  companion object {
    private const val TAG = "BacktalkStall"
    private const val STALL_THRESHOLD_MS = 100L

    private var installed: MainThreadStallLogger? = null

    /**
     * Logs stalls only while debug logging is on, since Android builds two strings for every
     * message the main thread runs while a logger is set.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
      if (enabled == (installed != null)) {
        return
      }
      installed?.watchdog?.looper?.quit()
      installed =
        if (enabled) MainThreadStallLogger(Handler(HandlerThread(TAG).apply { start() }.looper))
        else null
      Looper.getMainLooper().setMessageLogging(installed)
    }
  }
}
