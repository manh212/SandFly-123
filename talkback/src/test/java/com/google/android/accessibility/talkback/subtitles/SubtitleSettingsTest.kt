package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class SubtitleSettingsTest {
  private val context get() = RuntimeEnvironment.getApplication()
  private val prefs get() = SharedPreferencesUtils.getSharedPreferences(context)

  @Before fun clear() { prefs.edit().clear().commit() }

  @Test fun defaultsToTesseractAndFinishingCurrentSpeech() {
    assertEquals("tesseract", SubtitleSettings.engine(prefs))
    assertEquals("auto", SubtitleSettings.region(prefs))
    assertFalse(SubtitleSettings.interrupt(prefs))
    assertEquals(750L, SubtitleSettings.interval(prefs))
    prefs.edit().putString(SubtitleSettings.ENGINE, "mlkit").putString(SubtitleSettings.SPEECH, "interrupt").commit()
    assertEquals("mlkit", SubtitleSettings.engine(prefs))
    assertTrue(SubtitleSettings.interrupt(prefs))
  }

  @Test fun allSevenRegionChoicesHaveMatchingValues() {
    val labels = context.resources.getStringArray(R.array.subtitle_region_labels)
    val values = context.resources.getStringArray(R.array.subtitle_region_values)
    assertEquals(7, labels.size)
    assertEquals(listOf("auto", "top", "middle", "bottom", "focus", "custom", "full"), values.toList())
    assertTrue(labels.all { it.isNotBlank() })
  }

  @Test fun manualRegionsRespectTheAppWindowAndFocusedBounds() {
    val window = Rect(100, 200, 1100, 1200)
    prefs.edit().putString(SubtitleSettings.REGION, "bottom").commit()
    assertEquals(Rect(100, 800, 1100, 1200), SubtitleSettings.bounds(prefs, window, null))
    prefs.edit().putString(SubtitleSettings.REGION, "focus").commit()
    assertNull(SubtitleSettings.bounds(prefs, window, null))
    assertEquals(Rect(100, 300, 400, 500), SubtitleSettings.bounds(prefs, window, Rect(0, 300, 400, 500)))
  }

  @Test fun customPercentagesStayInsideTheWindowEvenWithAnInvalidBackup() {
    prefs.edit().putString(SubtitleSettings.REGION, "custom").putInt(SubtitleSettings.LEFT, 120)
      .putInt(SubtitleSettings.RIGHT, -20).putInt(SubtitleSettings.TOP, -10).commit()
    assertEquals(Rect(990, 0, 1000, 1000), SubtitleSettings.bounds(prefs, Rect(0, 0, 1000, 1000), null))
    prefs.edit().putString(SubtitleSettings.INTERVAL, "bad").commit()
    assertEquals(750L, SubtitleSettings.interval(prefs))
  }
}
