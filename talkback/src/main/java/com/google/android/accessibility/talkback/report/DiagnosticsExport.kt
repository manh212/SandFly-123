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

package com.google.android.accessibility.talkback.report

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Environment
import android.os.Process
import android.preference.PreferenceManager
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration
import com.google.android.accessibility.talkback.migration.MigrationFiles
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils
import com.google.android.accessibility.utils.Logger
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech
import com.google.android.accessibility.utils.output.LowLatencyAudio
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Builds one diagnostics text out of everything SandFly itself can know: the phone and the app,
 * SandFly's state and audio path, the last crash, a digest of the stored preferences, the
 * performance statistics, and SandFly's recent log. The user saves it to Downloads or copies it,
 * and sends it to whoever is helping. SandFly sends it nowhere itself.
 */
object DiagnosticsExport {
  private const val TAG = "DiagnosticsExport"

  /** The other named preference files read on the cold start, besides the default file. */
  private val OTHER_NAMED_PREFS =
    listOf("braille_keyboard", "braille_display", MigrationFiles.STATE_PREFS)
  private const val LOG_LINES = 10_000
  private const val LOG_MAX_CHARS = 2_000_000
  private const val LOG_TIMEOUT_SECONDS = 15L

  /** The whole diagnostics text. Slow: it runs logcat. */
  @WorkerThread
  fun build(context: Context): String {
    val appContext = context.applicationContext
    val out = StringBuilder()
    out.append("SandFly diagnostics\n")
    out.append(
      "Privacy: this file can contain text SandFly has spoken, content from your screen, and" +
        " other personal information. Share it only with people you trust.\n"
    )
    out.append(
      "Generated: " +
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date()) +
        "\n\n"
    )
    out.append(buildSnapshotSections(appContext))
    out.append(buildPerformanceStatsSection())
    val log = readLog()
    out.append("== Recent log of the SandFly process ==\n")
    if (log == null) {
      out.append("The log could not be read.\n")
    } else {
      out.append(
        "Lines captured: ${log.count { it == '\n' }} (at most $LOG_LINES lines," +
          " $LOG_MAX_CHARS characters)\n"
      )
      out.append(log)
      if (!log.endsWith("\n")) out.append("\n")
    }
    return out.toString()
  }

  /**
   * The snapshot sections of [build] — device and app, SandFly state, audio, last crash,
   * preferences digest — without the recent log. This is the single source of truth for those
   * sections: [build] assembles the export file from it, and [VerboseDiagnosticsLogger] writes
   * it into the log itself at the VERBOSE log level.
   */
  @WorkerThread
  fun buildSnapshotSections(context: Context): String {
    val appContext = context.applicationContext
    val out = StringBuilder()
    deviceAndApp(appContext, out)
    sandFlyState(appContext, out)
    audioSnapshot(appContext, out)
    out.append("== Last crash ==\n")
    out.append(CrashLog.read(appContext) ?: "None recorded in the last 7 days.")
    out.append("\n\n")
    preferencesDigest(appContext, out)
    return out.toString()
  }

  /**
   * The preferences digest section: the default preferences file's facts and a per-key
   * fingerprint of its contents, both from [PrefsDigest], then file facts only for the other
   * named preference files. The stored values themselves are never written out — only their
   * types, lengths and shortened hashes — so two exports can be diffed key by key to find which
   * stored settings differ (for example a file left stale by an app update versus the same file
   * after a settings change rewrote it) without exposing any of them.
   */
  private fun preferencesDigest(context: Context, out: StringBuilder) {
    // Built into a scratch buffer first: this section is a measuring instrument and must never
    // break the diagnostics text it rides in, so any failure lands as one explicit line instead.
    val section = StringBuilder()
    try {
      section.append("== Preferences digest ==\n")
      // The same device-protected context the preferences themselves are read through, so the
      // directory named here is the one holding the file the service loads at the cold start.
      val deContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
      val prefsDir = File(deContext.dataDir, "shared_prefs")
      val defaultName = PreferenceManager.getDefaultSharedPreferencesName(deContext)
      val file = File(prefsDir, "$defaultName.xml")
      section.append("File: ${PrefsDigest.fileDescription(file)}\n")
      section.append(
        "Backup file: ${PrefsDigest.fileDescription(File(prefsDir, "$defaultName.xml.bak"))}\n"
      )
      val all = SharedPreferencesUtils.getSharedPreferences(context).all
      section.append("Keys: ${all.size}\n")
      for (line in PrefsDigest.digestLines(all)) {
        section.append(line).append("\n")
      }
      section.append("Other preference files:\n")
      for (name in OTHER_NAMED_PREFS) {
        section.append("${PrefsDigest.fileDescription(File(prefsDir, "$name.xml"))}\n")
      }
    } catch (e: Throwable) {
      out.append("== Preferences digest ==\n")
      out.append("Preferences digest: unavailable (${e.javaClass.simpleName})\n\n")
      return
    }
    section.append("\n")
    out.append(section)
  }

  /**
   * The performance statistics section of [build], captured at the moment the text is built: the
   * collection status line — the same line [VerboseDiagnosticsLogger] writes at the head of its
   * log snapshot — and, when statistics collection is enabled in developer settings, the
   * statistics themselves. Capturing them here, rather than relying on the copies written into
   * the log at VERBOSE, keeps them in the exported text even after those log lines have scrolled
   * out of the log buffer.
   */
  @WorkerThread
  fun buildPerformanceStatsSection(): String {
    val out = StringBuilder()
    out.append("== Performance statistics ==\n")
    out.append(performanceStatsStatusLine()).append("\n")
    if (Performance.getInstance().computeStatsEnabled) {
      displayPerformanceStats(
        Logger { format, args ->
          val line =
            if (args.isNullOrEmpty()) format else String.format(Locale.ROOT, format, *args)
          out.append(line).append("\n")
        }
      )
    }
    out.append("\n")
    return out.toString()
  }

  /** The one-line statistics collection status, shared by the export text and the log snapshot. */
  fun performanceStatsStatusLine(): String =
    "Performance statistics collection: " +
      if (Performance.getInstance().computeStatsEnabled) "on" else "off"

  /**
   * Sends the current performance statistics to [logger] — the same displays, in the same order,
   * for the export text above and for the log at VERBOSE through [VerboseDiagnosticsLogger], so
   * the two can never drift apart.
   */
  fun displayPerformanceStats(logger: Logger) {
    val performance = Performance.getInstance()
    performance.displayLabelToStats(logger)
    performance.displayStatToLabelCompare(logger)
    performance.displayAllEventStats(logger)
    performance.displayPreparedSpeechStats(logger)
    performance.displayOrderBuildStats(logger)
    performance.displaySwipeSegmentStats(logger)
    performance.displaySpeechWaitStats(logger)
    performance.displaySlowSwipeProfiles(logger)
    val tts = failoverTts()
    if (tts != null && tts.speechCacheCount >= 0) {
      val format = tts.speechCacheFormatDescription
      logger.log(
        "Speech cache contents: %d sounds, %d KB%s",
        tts.speechCacheCount,
        tts.speechCacheSizeBytes / 1024,
        if (format.isEmpty()) "" else ", $format",
      )
    }
  }

  /**
   * Saves [text] as a file in the public Downloads folder, and returns whether it was saved.
   * Downloads through MediaStore needs Android 10 or later; before that there is no way to write
   * there without a storage permission SandFly does not hold.
   */
  @WorkerThread
  fun saveToDownloads(context: Context, text: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val resolver = context.contentResolver
    val name =
      "sandfly-diagnostics-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) +
        ".txt"
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    val uri =
      try {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Cannot create the diagnostics file: %s", e)
        null
      } ?: return false
    return try {
      val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
      stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
      true
    } catch (e: IOException) {
      LogUtils.w(TAG, "Cannot write the diagnostics file: %s", e)
      try {
        resolver.delete(uri, null, null)
      } catch (ignored: RuntimeException) {}
      false
    }
  }

  private fun deviceAndApp(context: Context, out: StringBuilder) {
    out.append("== Device and app ==\n")
    out.append("Manufacturer: ${Build.MANUFACTURER}\n")
    out.append("Model: ${Build.MODEL}\n")
    out.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
    out.append("Build fingerprint: ${Build.FINGERPRINT.take(120)}\n")
    try {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      val code =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
      out.append("SandFly: ${info.versionName} (version code $code)\n")
    } catch (e: PackageManager.NameNotFoundException) {
      out.append("SandFly: version unknown\n")
    }
    out.append("\n")
  }

  private fun sandFlyState(context: Context, out: StringBuilder) {
    val res = context.resources
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    out.append("== SandFly state ==\n")
    out.append("Log level: ${LogUtils.getLogLevelName()}\n")
    val diagnosis = PreferencesActivityUtils.isDiagnosisModeOn(prefs, res)
    out.append("Diagnosis mode: ${if (diagnosis) "on" else "off"}\n")
    val typingMethod =
      SharedPreferencesUtils.getIntFromStringPref(
        prefs,
        res,
        R.string.pref_typing_confirmation_key,
        R.string.pref_typing_confirmation_default,
      )
    out.append("Typing method: $typingMethod (${typingMethodName(typingMethod)})\n")
    val tts = failoverTts()
    if (tts == null) {
      val setting =
        prefs.getBoolean(
          FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY,
          FailoverTextToSpeech.lowLatencyAudioDefault(context),
        )
      out.append("Live speech state: not available; the values below are from settings.\n")
      out.append("Low-latency audio setting: ${if (setting) "on" else "off"}\n")
      out.append("TTS engine: not available while the speech service is not running\n")
    } else {
      out.append("Low-latency audio setting: ${if (tts.isLowLatencyAudioOn) "on" else "off"}\n")
      val engine = tts.currentTtsEnginePackage
      if (engine == null) {
        out.append("TTS engine: none running yet\n")
      } else {
        out.append("TTS engine: ${engineLabel(context, engine)} ($engine)\n")
        if (tts.isLowLatencyOffForCurrentEngine) {
          out.append(
            "Low-latency playback for this engine: off" +
              " (reason: ${tts.lowLatencyOffReason ?: "unknown"})\n"
          )
        } else {
          out.append("Low-latency playback for this engine: on\n")
        }
      }
    }
    out.append("\n")
  }

  private fun audioSnapshot(context: Context, out: StringBuilder) {
    out.append("== Audio ==\n")
    val audioManager = context.getSystemService(AudioManager::class.java)
    out.append(
      "System output sample rate: " +
        "${audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "unknown"}\n"
    )
    out.append(
      "System output frames per buffer: " +
        "${audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "unknown"}\n"
    )
    val players = LowLatencyAudio.players()
    if (players.isEmpty()) {
      out.append("Low-latency player: inactive\n")
      out.append("Why: ${inactiveReason(context)}\n")
    } else {
      players.forEachIndexed { index, player ->
        val snapshot = player.snapshot()
        out.append("Low-latency player ${index + 1} (usage: ${usageName(snapshot.usage)}):\n")
        out.append("  Sample rate: ${snapshot.sampleRate} Hz\n")
        out.append("  Burst: ${snapshot.burstFrames} frames\n")
        out.append("  Fast path: ${if (snapshot.fastPath) "yes" else "no"}\n")
        if (snapshot.bufferSizeInFrames >= 0) {
          val ms = snapshot.bufferSizeInFrames * 1000.0 / snapshot.sampleRate
          out.append(
            "  Buffer: ${snapshot.bufferSizeInFrames} frames" +
              " (${String.format(Locale.ROOT, "%.1f", ms)} ms)\n"
          )
          out.append("  Buffer capacity: ${snapshot.bufferCapacityInFrames} frames\n")
          out.append("  Underruns: ${snapshot.underrunCount}\n")
          out.append("  Play state: ${playStateName(snapshot.playState)}\n")
        } else {
          out.append("  Track: none (the player has no track now)\n")
        }
        out.append(
          "  Audio thread running: ${if (snapshot.audioThreadRunning) "yes" else "no"}\n"
        )
        out.append("  Gave up: ${if (snapshot.broken) "yes" else "no"}\n")
        out.append("  Shut down: ${if (snapshot.released) "yes" else "no"}\n")
        val deviceType = snapshot.routedDeviceType
        out.append(
          "  Output device: " +
            (if (deviceType == null) "unknown"
            else "${deviceTypeName(deviceType)}${snapshot.routedDeviceName?.let { " ($it)" } ?: ""}") +
            "\n"
        )
      }
      if (LowLatencyAudio.smallBufferFittings > 0) {
        out.append(
          "Small buffer: ${LowLatencyAudio.smallBufferFittings} fittings," +
            " ${LowLatencyAudio.smallBufferShortfalls} shortfalls kept small\n"
        )
      }
    }
    out.append("\n")
  }

  /** Why there is no low-latency player, as far as can be told without making one. */
  private fun inactiveReason(context: Context): String {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val setting =
      prefs.getBoolean(
        FailoverTextToSpeech.PREF_LOW_LATENCY_AUDIO_KEY,
        FailoverTextToSpeech.lowLatencyAudioDefault(context),
      )
    if (!setting) return "the low-latency audio setting is off"
    if (TalkBackService.getInstance() == null) return "SandFly is not running"
    return "no player has been made yet; a player is made the first time speech or a sound" +
      " uses the low-latency path"
  }

  private fun failoverTts(): FailoverTextToSpeech? {
    val service = TalkBackService.getInstance() ?: return null
    return try {
      service.speechController.failoverTts
    } catch (e: IllegalStateException) {
      null
    }
  }

  private fun engineLabel(context: Context, enginePackage: String): String =
    try {
      val manager = context.packageManager
      manager.getApplicationLabel(manager.getApplicationInfo(enginePackage, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
      enginePackage
    }

  private fun typingMethodName(method: Int): String =
    when (method) {
      FocusProcessorForTapAndTouchExploration.DOUBLE_TAP -> "double tap"
      FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE -> "lift to type"
      FocusProcessorForTapAndTouchExploration.FORCE_LIFT_TO_TYPE_ON_IME ->
        "lift to type on any key"
      FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE_EXCEPT_ACTION_KEY ->
        "lift to type except action key"
      FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE -> "direct key input"
      else -> "unknown"
    }

  private fun usageName(usage: Int): String =
    when (usage) {
      AudioAttributes.USAGE_MEDIA -> "media"
      AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "accessibility"
      AudioAttributes.USAGE_NOTIFICATION -> "notification"
      AudioAttributes.USAGE_ALARM -> "alarm"
      else -> "usage $usage"
    }

  private fun playStateName(state: Int): String =
    when (state) {
      AudioTrack.PLAYSTATE_STOPPED -> "stopped"
      AudioTrack.PLAYSTATE_PAUSED -> "paused"
      AudioTrack.PLAYSTATE_PLAYING -> "playing"
      else -> "unknown ($state)"
    }

  private fun deviceTypeName(type: Int): String =
    when (type) {
      AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
      AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
      AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
      AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
      AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
      else -> "type $type"
    }

  /** The last lines of this process's log, or null if logcat cannot be run. */
  @WorkerThread
  private fun readLog(): String? {
    val process =
      try {
        ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-t",
            LOG_LINES.toString(),
            "--pid",
            Process.myPid().toString(),
          )
          .redirectErrorStream(true)
          .start()
      } catch (e: IOException) {
        LogUtils.w(TAG, "Cannot run logcat: %s", e)
        return null
      }
    return try {
      val log = process.inputStream.bufferedReader().use { it.readText() }
      process.waitFor(LOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (log.length <= LOG_MAX_CHARS) log
      else log.takeLast(LOG_MAX_CHARS).substringAfter('\n')
    } catch (e: IOException) {
      LogUtils.w(TAG, "Cannot read the log: %s", e)
      null
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      null
    } finally {
      process.destroy()
    }
  }
}
