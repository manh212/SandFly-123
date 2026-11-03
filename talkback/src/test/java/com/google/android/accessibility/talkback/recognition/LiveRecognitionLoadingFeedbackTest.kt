package com.google.android.accessibility.talkback.recognition

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveRecognitionLoadingFeedbackTest {
  private val handler = Handler(Looper.getMainLooper())

  @Test
  fun soundRepeatsWithoutRepeatingVibration() {
    var soundCount = 0
    var vibrationCount = 0
    val feedback = LiveRecognitionLoadingFeedback(handler, { soundCount++ }, { vibrationCount++ })

    feedback.start()
    assertEquals(1, vibrationCount)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, soundCount)
    shadowOf(Looper.getMainLooper()).idleFor(
      LiveRecognitionLoadingFeedback.REPEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    assertEquals(2, soundCount)
    assertEquals(1, vibrationCount)
    feedback.stop()
  }

  @Test
  fun switchingModesDoesNotRestartSoundOrVibration() {
    var soundCount = 0
    var vibrationCount = 0
    val feedback = LiveRecognitionLoadingFeedback(handler, { soundCount++ }, { vibrationCount++ })

    feedback.start()
    feedback.start()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, soundCount)
    assertEquals(1, vibrationCount)
    feedback.stop()
  }

  @Test
  fun stoppingRecognitionCancelsPendingAudioAndRestartVibratesOnceAgain() {
    var soundCount = 0
    var vibrationCount = 0
    val feedback = LiveRecognitionLoadingFeedback(handler, { soundCount++ }, { vibrationCount++ })

    feedback.start()
    shadowOf(Looper.getMainLooper()).idle()
    feedback.stop()
    shadowOf(Looper.getMainLooper()).idleFor(
      LiveRecognitionLoadingFeedback.REPEAT_INTERVAL_MS * 3, TimeUnit.MILLISECONDS)
    assertEquals(1, soundCount)
    assertEquals(1, vibrationCount)

    feedback.start()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(2, soundCount)
    assertEquals(2, vibrationCount)
    feedback.stop()
  }

  @Test
  fun stoppingBeforeFirstTickPreventsAnySound() {
    var soundCount = 0
    val feedback = LiveRecognitionLoadingFeedback(handler, { soundCount++ }, {})
    feedback.start()
    feedback.stop()
    shadowOf(Looper.getMainLooper()).idleFor(
      LiveRecognitionLoadingFeedback.REPEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    assertEquals(0, soundCount)
  }
}
