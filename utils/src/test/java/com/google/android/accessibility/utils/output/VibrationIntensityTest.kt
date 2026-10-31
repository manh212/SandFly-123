package com.google.android.accessibility.utils.output

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VibrationIntensityTest {
  @Test
  fun eachLevelKeepsSilentGapsAndChangesOnTimes() {
    val original = longArrayOf(0, 40, 30, 40)
    assertArrayEquals(
      longArrayOf(0, 16, 30, 16),
      VibrationIntensity.scaleOnDurations(original, 0.15f),
    )
    assertArrayEquals(
      longArrayOf(0, 28, 30, 28),
      VibrationIntensity.scaleOnDurations(original, 0.35f),
    )
    assertArrayEquals(
      longArrayOf(0, 40, 30, 40),
      VibrationIntensity.scaleOnDurations(original, 0.65f),
    )
    assertArrayEquals(
      longArrayOf(0, 56, 30, 56),
      VibrationIntensity.scaleOnDurations(original, 1f),
    )
    assertArrayEquals(longArrayOf(0, 40, 30, 40), original)
  }

  @Test
  fun existingPercentageValuesMapToOneOfFourLevels() {
    assertEquals(0, VibrationIntensity.levelForPercentage(5))
    assertEquals(1, VibrationIntensity.levelForPercentage(40))
    assertEquals(2, VibrationIntensity.levelForPercentage(80))
    assertEquals(3, VibrationIntensity.levelForPercentage(100))
    for (level in 0..3) {
      assertEquals(
        level,
        VibrationIntensity.levelForPercentage(VibrationIntensity.percentageForLevel(level)),
      )
    }
  }
}
