package com.google.android.accessibility.talkback.screenrecognition

import android.graphics.Rect
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.android.accessibility.talkback.screenelements.ScreenElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRecognitionTargetsTest {
  @Test fun recognizedButtonsKeepTheirScreenBoundsAndCanBeActivated() {
    val bounds = Rect(10, 100, 200, 160)
    val element =
      ScreenRecognitionElements.from(
          listOf(RecognizedItem("Save", bounds, false, "button")),
        )
        .single()

    assertEquals("Save", element.label)
    assertEquals(ScreenElement.Role.BUTTON, element.role)
    assertEquals(bounds, element.bounds)
    assertTrue(element.isActionable)
  }

  @Test fun stoppingForPauseInvalidatesRecognitionWithoutAnnouncing() {
    val messages = mutableListOf<String>()
    val controller = ScreenRecognitionController(TalkBackService()) { messages.add(it) }
    val active = controller.javaClass.getDeclaredField("active").apply { isAccessible = true }
    active.setBoolean(controller, true)

    assertTrue(controller.stopSilently())
    assertFalse(active.getBoolean(controller))
    assertEquals(1, controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }.getInt(controller))
    assertTrue(messages.isEmpty())
  }

  @Test fun tesseractKeepsScreenPixelsAndUsesTheSharedWordSplitter() {
    val controller = ScreenRecognitionController(TalkBackService()) {}
    val words = listOf(TesseractOcr.TextLine("Lưu", Rect(100, 1800, 150, 1830)),
      TesseractOcr.TextLine("Hủy", Rect(300, 1800, 350, 1830)))
    val line = TesseractOcr.TextLine("Lưu Hủy", Rect(100, 1800, 350, 1830), words)
    val convert = controller.javaClass.getDeclaredMethod("toItems", List::class.java).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val items = convert.invoke(controller, listOf(line)) as List<RecognizedItem>
    assertEquals(listOf("Lưu", "Hủy"), items.map { it.text })
    assertEquals(Rect(100, 1800, 150, 1830), items.first().bounds)
  }
}
