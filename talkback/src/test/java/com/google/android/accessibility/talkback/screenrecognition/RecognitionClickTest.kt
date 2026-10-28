package com.google.android.accessibility.talkback.screenrecognition

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionClickTest {
  @Test fun touchExplorationUsesTwoSeparateTapsAtTheRecognizedPosition() {
    val gesture = RecognitionClick.gesture(Rect(100, 200, 180, 240), 100, true)
    assertEquals(2, gesture.strokeCount)
    assertEquals(0L, gesture.getStroke(0).startTime)
    assertEquals(140L, gesture.getStroke(1).startTime)
    repeat(gesture.strokeCount) { index ->
      assertEquals(100L, gesture.getStroke(index).duration)
    }
  }

  @Test fun withoutTouchExplorationUsesOneTap() {
    val gesture = RecognitionClick.gesture(Rect(10, 20, 30, 40), 100, false)
    assertEquals(1, gesture.strokeCount)
  }
}
