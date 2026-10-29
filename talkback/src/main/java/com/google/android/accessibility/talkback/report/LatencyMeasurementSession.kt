package com.google.android.accessibility.talkback.report

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.eventprocessor.EventProcessingStats
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache
import com.google.android.accessibility.utils.Logger
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.output.LowLatencyAudio
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

object LatencyMeasurementSession {
  private const val MEASUREMENT_DURATION_MS = 10_000L
  private const val MAX_CAPTURED_GESTURES = 1024
  private val mainHandler = Handler(Looper.getMainLooper())
  private val lock = Any()
  private val measurementScheduler =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "NavigationLatencyMeasurement").apply { isDaemon = true }
    }
  private var completionListener: Consumer<String>? = null
  private var pendingReport: String? = null
  private var running = false
  private var finishFuture: ScheduledFuture<*>? = null
  private var reportContext: Context? = null
  private val finishRunnable = Runnable { finishTimedMeasurement() }

  @JvmStatic
  fun reset() {
    Performance.getInstance().clearAllStatsAndRecords(Logger { _, _ -> })
    TraversalTreeCache.resetOrderCacheStats()
    EventProcessingStats.reset()
    LowLatencyAudio.players().forEach { it.resetGapStatistics() }
    val speechController = try {
      TalkBackService.getInstance()?.speechController
    } catch (notReady: IllegalStateException) {
      null
    }
    speechController?.failoverTts?.resetNavigationCacheStatistics()
    LogUtils.i("LatencyTrace", "Measurement counters reset")
  }

  @JvmStatic
  fun isRunning(): Boolean = synchronized(lock) { running }

  @JvmStatic
  fun setCompletionListener(listener: Consumer<String>?) {
    val report = synchronized(lock) {
      completionListener = listener
      if (listener != null) pendingReport.also { pendingReport = null } else null
    }
    if (report != null) listener?.accept(report)
  }

  @JvmStatic
  fun startTimedMeasurement(context: Context) {
    synchronized(lock) {
      finishFuture?.cancel(false)
      finishFuture = null
      running = false
      pendingReport = null
    }
    reset()
    reportContext = context.applicationContext
    Performance.getInstance().startNavigationMeasurement()
    synchronized(lock) {
      running = true
      finishFuture =
        measurementScheduler.schedule(
          finishRunnable, MEASUREMENT_DURATION_MS, TimeUnit.MILLISECONDS)
    }
    LogUtils.i(
      "LatencyTrace",
      "Timed navigation measurement started for 10 seconds",
    )
  }

  private fun finishTimedMeasurement() {
    val shouldFinish = synchronized(lock) {
      if (!running) {
        false
      } else {
        finishFuture = null
        true
      }
    }
    if (!shouldFinish) return
    val samples =
      Performance.getInstance().finishNavigationMeasurement(MAX_CAPTURED_GESTURES)
    val report = buildReport(reportContext, samples)
    reportContext = null
    LogUtils.i(
      "LatencyTrace",
      "Timed navigation measurement finished with ${samples.size} gestures",
    )
    mainHandler.post {
      val listener = synchronized(lock) {
        running = false
        if (completionListener == null) {
          pendingReport = report
          null
        } else {
          completionListener
        }
      }
      listener?.accept(report)
    }
  }

  private fun buildReport(
    context: Context?,
    samples: List<Performance.NavigationGestureSample>,
  ): String {
    if (context == null) {
      return ""
    }
    if (samples.isEmpty()) {
      return context.getString(R.string.message_latency_report_no_gestures)
    }

    val actionResults = samples.mapNotNull { it.actionResultMs.takeIf { value -> value >= 0 } }
    val sounded = samples.mapNotNull { it.firstSoundPcmMs.takeIf { value -> value >= 0 } }
    val submitted = samples.mapNotNull { it.soundSubmittedMs.takeIf { value -> value >= 0 } }
    val recentSamples = samples.takeLast(10)
    val report = StringBuilder()
    report.append(context.getString(R.string.message_latency_report_count, samples.size))
    report.append("\n\n")
    if (actionResults.isEmpty()) {
      report.append(context.getString(R.string.message_latency_report_no_action_result))
    } else {
      report.append(
        context.getString(
          R.string.message_latency_report_action_summary,
          actionResults.size,
          samples.size,
          average(actionResults),
          actionResults.maxOrNull() ?: 0,
          actionResults.count { it <= 50 },
        )
      )
    }
    report.append("\n")
    if (sounded.isEmpty()) {
      report.append(context.getString(R.string.message_latency_report_no_sound_pcm))
    } else {
      report.append(
        context.getString(
          R.string.message_latency_report_first_sound_summary,
          sounded.size,
          samples.size,
          average(sounded),
          sounded.maxOrNull() ?: 0,
        )
      )
    }
    report.append("\n")
    if (submitted.isEmpty()) {
      report.append(context.getString(R.string.message_latency_report_no_submitted_sound))
    } else {
      report.append(
        context.getString(
          R.string.message_latency_report_submitted_summary,
          submitted.size,
          samples.size,
          average(submitted),
          submitted.maxOrNull() ?: 0,
          submitted.count { it <= 50 },
          submitted.size,
        )
      )
    }
    report.append("\n\n")
    report.append(context.getString(R.string.message_latency_report_recent))
    recentSamples.forEachIndexed { index, sample ->
      val details = mutableListOf<String>()
      if (sample.actionResultMs >= 0) {
        details.add(
          context.getString(R.string.message_latency_report_row_action, sample.actionResultMs))
      }
      if (sample.firstSoundPcmMs >= 0) {
        details.add(
          context.getString(
            R.string.message_latency_report_row_pcm_ready, sample.firstSoundPcmMs))
      }
      if (sample.soundSubmittedMs >= 0) {
        details.add(
          context.getString(
            R.string.message_latency_report_row_submitted, sample.soundSubmittedMs))
      } else if (sample.audioSubmittedMs >= 0) {
        details.add(
          context.getString(
            R.string.message_latency_report_row_audio_submitted, sample.audioSubmittedMs))
      } else if (sample.firstPcmMs >= 0 && sample.firstSoundPcmMs < 0) {
        details.add(
          context.getString(R.string.message_latency_report_row_pcm, sample.firstPcmMs))
      }
      val hasAudioMeasurement =
        sample.firstPcmMs >= 0 ||
          sample.firstSoundPcmMs >= 0 ||
          sample.audioSubmittedMs >= 0 ||
          sample.soundSubmittedMs >= 0
      if (!hasAudioMeasurement) {
        details.add(outcomeText(context, sample.outcome))
      } else if (sample.outcome.startsWith("interrupted")) {
        details.add(context.getString(R.string.message_latency_report_row_interrupted))
      }
      report.append(
        context.getString(
          R.string.message_latency_report_row,
          index + 1,
          sample.gesture,
          details.joinToString("; "),
        )
      )
      report.append("\n")
    }
    report.append("\n")
    report.append(context.getString(R.string.message_latency_report_caveat))
    return report.toString().trimEnd()
  }

  private fun outcomeText(context: Context, outcome: String): String =
    when (outcome) {
      "pending" -> context.getString(R.string.message_latency_report_row_pending)
      "interrupted_before_audio", "interrupted_after_audio" ->
        context.getString(R.string.message_latency_report_row_interrupted)
      else -> context.getString(R.string.message_latency_report_row_no_sound)
    }

  private fun average(values: List<Long>): Long = values.sum() / values.size
}
