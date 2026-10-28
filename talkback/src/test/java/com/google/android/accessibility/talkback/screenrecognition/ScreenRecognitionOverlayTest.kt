package com.google.android.accessibility.talkback.screenrecognition

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRecognitionOverlayTest {
  @Test fun eachRecognizedItemHasBoundsAndAnIndependentClickAction() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val clicked = mutableListOf<String>()
    val overlay = ScreenRecognitionOverlay(activity, { clicked.add(it.text) }, {})
    activity.setContentView(overlay)
    overlay.layout(0, 0, 400, 600)
    val location = IntArray(2)
    overlay.getLocationOnScreen(location)
    val bounds = Rect(20 + location[0], 50 + location[1], 120 + location[0], 80 + location[1])
    overlay.setItems(listOf(RecognizedItem("Mục một", bounds, false),
      RecognizedItem("Mục hai", Rect(150, 100, 250, 130), false)))
    val provider = overlay.accessibilityNodeProvider!!
    val node = provider.createAccessibilityNodeInfo(0)!!
    val local = Rect()
    node.getBoundsInParent(local)
    assertEquals(Rect(20, 50, 120, 80), local)
    assertEquals("Mục một", node.text.toString())
    assertTrue(node.isClickable)
    assertTrue(node.isFocusable)
    assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
    assertEquals(listOf("Mục hai"), clicked)
    assertFalse(provider.performAction(9, AccessibilityNodeInfo.ACTION_CLICK, null))
    val host = provider.createAccessibilityNodeInfo(View.NO_ID)!!
    assertEquals(2, host.childCount)
    activity.finish()
  }
}
