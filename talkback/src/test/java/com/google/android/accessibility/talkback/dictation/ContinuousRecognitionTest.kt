package com.google.android.accessibility.talkback.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousRecognitionTest {
  @Test
  fun finalPartialTextFollowsRecognizedParts() {
    assertEquals(
        "first part last phrase", combineRecognitionParts(listOf("first part"), "  last phrase  "))
  }

  @Test
  fun blankFinalPartialDoesNotAddAnEmptyPart() {
    assertEquals("first part", combineRecognitionParts(listOf("first part"), "  "))
  }

  @Test
  fun speechLevelTrackerDistinguishesSpeechFromSilence() {
    val detector = SpeechActivityDetector()

    assertFalse(detector.isSpeechLevel(-35f))
    assertTrue(detector.isSpeechLevel(-20f))
    assertTrue(detector.isSpeechLevel(-20f))
    assertFalse(detector.isSpeechLevel(-40f))
  }

  @Test
  fun speechStartCanCalibrateTheFirstRmsReading() {
    val detector = SpeechActivityDetector()
    detector.beginningOfSpeech()

    assertTrue(detector.isSpeechLevel(-12f))
    assertTrue(detector.isSpeechLevel(-12f))
  }
}
