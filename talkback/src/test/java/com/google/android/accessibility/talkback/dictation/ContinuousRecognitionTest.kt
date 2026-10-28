package com.google.android.accessibility.talkback.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousRecognitionTest {
  @Test
  fun finalPartialTextFollowsRecognizedParts() {
    assertEquals("first part last phrase", combineRecognitionParts(listOf("first part"), "  last phrase  "))
  }

  @Test
  fun blankFinalPartialDoesNotAddAnEmptyPart() {
    assertEquals("first part", combineRecognitionParts(listOf("first part"), "  "))
  }
}
