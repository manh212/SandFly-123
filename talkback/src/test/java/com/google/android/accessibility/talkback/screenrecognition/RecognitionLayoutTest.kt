package com.google.android.accessibility.talkback.screenrecognition

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionLayoutTest {
  private fun word(text: String, left: Int, right: Int, top: Int = 10, bottom: Int = 30) =
    RecognizedItem(text, Rect(left, top, right, bottom), false)

  @Test fun keepsVietnameseLabelsTogetherAndSplitsDistantControls() {
    val words = listOf(word("Cài", 10, 35), word("đặt", 41, 65), word("Trợ", 150, 180), word("giúp", 185, 215))
    val items = RecognitionLayout.splitLine("Cài đặt Trợ giúp", Rect(10, 10, 215, 30), words, emptyList())
    assertEquals(listOf("Cài đặt", "Trợ giúp"), items.map { it.text })
    assertEquals(Rect(150, 10, 215, 30), items[1].bounds)
  }

  @Test fun separatesAdjacentButtonsEvenWhenTheGapIsSmall() {
    val items = RecognitionLayout.splitLine("Lưu Hủy", Rect(10, 10, 85, 30),
      listOf(word("Lưu", 10, 40), word("Hủy", 50, 85)),
      listOf(Rect(0, 0, 45, 40), Rect(46, 0, 100, 40)))
    assertEquals(listOf("Lưu", "Hủy"), items.map { it.text })
  }

  @Test fun doesNotSplitWordsInsideOneButton() {
    val items = RecognitionLayout.splitLine("Đăng nhập", Rect(10, 10, 90, 30),
      listOf(word("Đăng", 10, 45), word("nhập", 51, 90)), listOf(Rect(0, 0, 100, 40)))
    assertEquals(listOf("Đăng nhập"), items.map { it.text })
  }

  @Test fun usesLineFallbackAndDropsEmptyBoxes() {
    assertEquals("Tiếng Việt", RecognitionLayout.splitLine(" Tiếng Việt ", Rect(2, 3, 90, 25),
      emptyList(), emptyList()).single().text)
    assertTrue(RecognitionLayout.splitLine("", Rect(), emptyList(), emptyList()).isEmpty())
  }

  @Test fun readsRowsLeftToRightDespiteDifferentLabelHeights() {
    val items = mutableListOf(word("phải", 100, 160, 9, 31), word("dưới", 10, 70, 50, 70),
      word("trái", 10, 70, 12, 29))
    RecognitionLayout.sort(items)
    assertEquals(listOf("trái", "phải", "dưới"), items.map { it.text })
  }

  @Test fun overlappingBoxesPreferTheSpecificItem() {
    val items = listOf(word("rộng", 0, 200, 0, 100), word("nút", 10, 40, 10, 30))
    assertEquals(1, RecognitionLayout.itemAt(items, 20, 20))
    assertEquals(-1, RecognitionLayout.itemAt(items, 250, 20))
  }
}
