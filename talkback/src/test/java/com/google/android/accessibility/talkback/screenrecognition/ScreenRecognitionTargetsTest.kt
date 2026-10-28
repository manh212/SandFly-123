package com.google.android.accessibility.talkback.screenrecognition

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRecognitionTargetsTest {
  private fun node(bounds: Rect, clickable: Boolean) = AccessibilityNodeInfo.obtain().apply {
    setBoundsInScreen(bounds)
    isVisibleToUser = true
    isEnabled = true
    isClickable = clickable
  }

  @Test fun selectsTheClickableParentOfRecognizedText() {
    val controller = ScreenRecognitionController(TalkBackService()) {}
    val active = controller.javaClass.getDeclaredField("active").apply { isAccessible = true }
    active.setBoolean(controller, true)
    val generation = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }.getInt(controller)
    val root = node(Rect(0, 0, 500, 800), false)
    val button = node(Rect(10, 100, 200, 160), true)
    val label = node(Rect(30, 115, 150, 140), false)
    shadowOf(root).addChild(button)
    shadowOf(button).addChild(label)
    val find = controller.javaClass.getDeclaredMethod("findClickTarget", AccessibilityNodeInfo::class.java,
      Rect::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, IntArray::class.java,
      Int::class.javaPrimitiveType).apply { isAccessible = true }
    val target = find.invoke(controller, root, Rect(30, 115, 150, 140), 0, Long.MAX_VALUE, intArrayOf(0), generation) as AccessibilityNodeInfo
    assertEquals(button, target)
    button.isEnabled = false
    assertNull(find.invoke(controller, root, Rect(30, 115, 150, 140), 0, Long.MAX_VALUE, intArrayOf(0), generation))
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
