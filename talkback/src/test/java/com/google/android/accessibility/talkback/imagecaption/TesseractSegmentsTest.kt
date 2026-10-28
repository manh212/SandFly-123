package com.google.android.accessibility.talkback.imagecaption

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TesseractSegmentsTest {
  @Test fun idleReleaseDoesNotWaitForOcrOnTheMainLooper() {
    val handler =
      TesseractOcr::class.java.getDeclaredField("IDLE_HANDLER").apply { isAccessible = true }
        .get(null) as Handler
    assertNotSame(Looper.getMainLooper(), handler.looper)
  }

  @Test fun preservesWordBoxesAndVietnameseLabelsForFocusSegmentation() {
    val hocr = """<span class='ocr_line' title='bbox 10 20 280 50'>
      <span class='ocrx_word' title='bbox 10 20 70 50'><strong>Đăng</strong></span>
      <span class='ocrx_word' title='bbox 75 20 140 50'>nhập</span>
      <span class='ocrx_word' title='bbox 220 20 280 50'>Hủy</span></span>"""
    val parse = TesseractOcr::class.java.getDeclaredMethod("parseHocrSegments", String::class.java)
      .apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val lines = parse.invoke(null, hocr) as List<TesseractOcr.TextLine>
    assertEquals("Đăng nhập Hủy", lines.single().text)
    assertEquals(listOf("Đăng", "nhập", "Hủy"), lines.single().words.map { it.text })
    assertEquals(Rect(220, 20, 280, 50), lines.single().words.last().bounds)
  }

  @Test fun readsBoundingBoxesWithMetadataAndRejectsInvalidRegions() {
    val parse = TesseractOcr::class.java.getDeclaredMethod("parseHocrBbox", String::class.java)
      .apply { isAccessible = true }
    assertEquals(Rect(10, 20, 70, 50), parse.invoke(null, "title='bbox 10 20 70 50; x_wconf 95'"))
    assertEquals(Rect(10, 20, 70, 50), parse.invoke(null, "title='bbox 10 20 70 50'"))
    assertNull(parse.invoke(null, "title='bbox 70 20 10 50'"))
    assertNull(parse.invoke(null, "title='bbox invalid'"))
  }
}
