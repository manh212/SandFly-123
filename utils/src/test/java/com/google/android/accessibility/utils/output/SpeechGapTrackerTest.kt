package com.google.android.accessibility.utils.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechGapTrackerTest {
  @Test
  fun delayedEngineCompletion_isTailWait() {
    val tracker = SpeechGapTracker()
    assertNull(tracker.mix(0, 256, true, 100))
    assertNull(tracker.mix(0, 256, true, 130))
    assertEquals(SpeechGapTracker.Gap(SpeechGapTracker.Kind.TAIL, 50),
      tracker.close(SpeechGapTracker.Kind.TAIL, 150))
    assertFalse(tracker.waiting)
  }

  @Test
  fun audioResumingAfterShortage_isRecordedOnceWithDuration() {
    val tracker = SpeechGapTracker()
    tracker.mix(128, 256, true, 100)
    tracker.mix(0, 256, true, 120)
    assertEquals(SpeechGapTracker.Gap(SpeechGapTracker.Kind.RESUMED, 35),
      tracker.mix(256, 256, true, 135))
    assertNull(tracker.mix(256, 256, true, 140))
    assertNull(tracker.close(SpeechGapTracker.Kind.TAIL, 150))
  }

  @Test
  fun interruptedShortage_isNeitherTailNorResumedGap() {
    val tracker = SpeechGapTracker()
    tracker.mix(0, 256, true, 100)
    assertEquals(SpeechGapTracker.Gap(SpeechGapTracker.Kind.INTERRUPTED, 10),
      tracker.close(SpeechGapTracker.Kind.INTERRUPTED, 110))
    assertNull(tracker.close(SpeechGapTracker.Kind.TAIL, 140))
  }

  @Test
  fun alreadyEndedStream_doesNotOpenAShortage() {
    val tracker = SpeechGapTracker()
    tracker.mix(50, 256, false, 100)
    assertFalse(tracker.waiting)
  }
}
