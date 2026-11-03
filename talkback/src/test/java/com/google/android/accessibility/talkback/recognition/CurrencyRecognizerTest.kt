package com.google.android.accessibility.talkback.recognition

import org.junit.Assert.*
import org.junit.Test

class CurrencyRecognizerTest {
  private val order = CurrencyModelOutput.denominations
  private var clock = 0L

  private fun observe(tracker: CurrencyTracker, note: CurrencyPrediction?): String? =
    tracker.observe(note, clock.also { clock += 450L })

  @Test fun acceptsChannelsFirstModelInput() {
    assertEquals(CurrencyInputLayout(640, 640, true),
      CurrencyInputLayout.from(intArrayOf(1, 3, 640, 640)))
  }

  @Test fun acceptsChannelsLastModelInput() {
    assertEquals(CurrencyInputLayout(320, 224, false),
      CurrencyInputLayout.from(intArrayOf(1, 224, 320, 3)))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsInvalidImageTensor() {
    CurrencyInputLayout.from(intArrayOf(1, 4, 640, 640))
  }

  @Test fun supportsAllNineRoboflowDenominations() {
    assertArrayEquals(intArrayOf(1000, 10000, 100000, 2000, 20000, 200000, 5000, 50000, 500000), order)
    assertEquals("Năm trăm nghìn đồng", CurrencyModelOutput.speak(500000))
    assertEquals("Một nghìn đồng", CurrencyModelOutput.speak(1000))
  }

  @Test fun channelsFirstOutputSelectsHighestConfidenceBanknote() {
    val scores = FloatArray(13 * 2)
    scores[2 * 2] = 60f
    scores[3 * 2] = 30f
    scores[2 * 2 + 1] = 90f
    scores[3 * 2 + 1] = 50f
    scores[(4 + 0) * 2] = 0.86f
    scores[(4 + 8) * 2 + 1] = 0.93f
    assertEquals(500000, CurrencyModelOutput.best(scores, intArrayOf(1, 13, 2))?.denomination)
  }

  @Test fun channelsLastOutputIsAlsoSupported() {
    val scores = FloatArray(2 * 13)
    scores[2] = 30f
    scores[3] = 20f
    scores[13 + 2] = 50f
    scores[13 + 3] = 30f
    scores[4 + 3] = 0.82f
    scores[13 + 4 + 5] = 0.95f
    assertEquals(200000, CurrencyModelOutput.best(scores, intArrayOf(1, 2, 13))?.denomination)
  }

  @Test fun ignoresWeakOrInvalidDetections() {
    val scores = FloatArray(13)
    scores[2] = 90f
    scores[3] = 40f
    scores[4] = 0.6f
    assertNull(CurrencyModelOutput.best(scores, intArrayOf(1, 13, 1)))
    scores[4] = 0.99f
    scores[2] = 0f
    assertNull(CurrencyModelOutput.best(scores, intArrayOf(1, 13, 1)))
  }

  @Test fun speaksOnlyAfterTwoMatchingFramesAndNotAgainForSameNote() {
    val tracker = CurrencyTracker()
    val note = CurrencyPrediction(100000, 0.94f)
    assertNull(observe(tracker, note))
    assertEquals("Một trăm nghìn đồng", observe(tracker, note))
    assertNull(observe(tracker, note))
    assertNull(observe(tracker, note))
    assertNull(observe(tracker, CurrencyPrediction(200000, 0.84f)))
    assertEquals("Hai trăm nghìn đồng", observe(tracker, CurrencyPrediction(200000, 0.9f)))
  }

  @Test fun repeatsUnchangedBanknoteAfterFiveSecondsOfStableRecognition() {
    val tracker = CurrencyTracker()
    val note = CurrencyPrediction(100000, 0.95f)
    assertNull(tracker.observe(note, 1_000L))
    assertEquals("Một trăm nghìn đồng", tracker.observe(note, 1_450L))
    assertNull(tracker.observe(note, 5_999L))
    assertEquals("Một trăm nghìn đồng", tracker.observe(note, 6_450L))
    assertNull(tracker.observe(note, 6_900L))
  }

  @Test fun doesNotRepeatWhilePredictionsAreUnstable() {
    val tracker = CurrencyTracker()
    val note = CurrencyPrediction(500000, 0.95f)
    tracker.observe(note, 1_000L)
    assertNotNull(tracker.observe(note, 1_450L))
    assertNull(tracker.observe(null, 6_400L))
    assertNull(tracker.observe(note, 6_450L))
    assertEquals("Năm trăm nghìn đồng", tracker.observe(note, 6_900L))
  }

  @Test fun reannouncesAfterBanknoteLeavesView() {
    val tracker = CurrencyTracker()
    val note = CurrencyPrediction(50000, 0.96f)
    observe(tracker, note)
    assertNotNull(observe(tracker, note))
    repeat(3) { assertNull(observe(tracker, null)) }
    assertNull(observe(tracker, note))
    assertEquals("Năm mươi nghìn đồng", observe(tracker, note))
  }

  @Test fun unstablePredictionsAreNeverSpoken() {
    val tracker = CurrencyTracker()
    repeat(6) {
      assertNull(observe(tracker, CurrencyPrediction(10000, 0.91f)))
      assertNull(observe(tracker, CurrencyPrediction(20000, 0.91f)))
    }
  }
}
