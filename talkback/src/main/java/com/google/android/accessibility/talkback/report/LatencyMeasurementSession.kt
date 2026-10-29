package com.google.android.accessibility.talkback.report

import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.eventprocessor.EventProcessingStats
import com.google.android.accessibility.talkback.focusmanagement.TraversalTreeCache
import com.google.android.accessibility.utils.Logger
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.output.LowLatencyAudio
import com.google.android.libraries.accessibility.utils.log.LogUtils

object LatencyMeasurementSession {
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
    LogUtils.i("LatencyTrace", "Measurement session started")
  }
}
