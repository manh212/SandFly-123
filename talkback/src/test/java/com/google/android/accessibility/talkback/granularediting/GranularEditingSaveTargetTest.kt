package com.google.android.accessibility.talkback.granularediting

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GranularEditingSaveTargetTest {
  @Test
  fun rebindsToSameEditorAfterItsNodeChanges() {
    val root = node(null, false, null, 0)
    val original = node(null, true, "message", 100)
    node(root, true, "subject", 100)
    val replacement = node(root, true, "message", 150)

    assertEquals(replacement, GranularEditing.findMatchingField(root, original))
  }

  @Test
  fun matchesVirtualEditorByBoundsWhenItHasNoId() {
    val root = node(null, false, null, 0)
    val original = node(null, true, null, 100)
    val replacement = node(root, true, null, 100)
    node(root, true, null, 300)

    assertEquals(replacement, GranularEditing.findMatchingField(root, original))
  }

  @Test
  fun findsOriginalEditorAfterItsWindowIdChanges() {
    val unrelatedRoot = node(null, false, null, 0)
    node(unrelatedRoot, true, "unrelated", 100)
    val original = node(null, true, "message", 100)
    val newWindow = node(null, false, null, 0)
    val replacement = node(newWindow, true, "message", 175)

    assertEquals(
      replacement,
      GranularEditing.findMatchingFieldAcrossRoots(listOf(unrelatedRoot, newWindow), original),
    )
  }

  @Test
  fun rejectsDuplicateEditorsAcrossWindows() {
    val original = node(null, true, "message", 100)
    val windowOne = node(null, false, null, 0)
    val windowTwo = node(null, false, null, 0)
    node(windowOne, true, "message", 105)
    node(windowTwo, true, "message", 110)

    assertNull(
      GranularEditing.findMatchingFieldAcrossRoots(listOf(windowOne, windowTwo), original),
    )
  }

  @Test
  fun refusesAnAmbiguousOrDifferentEditor() {
    val root = node(null, false, null, 0)
    val original = node(null, true, "message", 100)
    node(root, true, "other", 100)
    assertNull(GranularEditing.findMatchingField(root, original))

    node(root, true, "message", 100)
    node(root, true, "message", 200)
    assertNull(GranularEditing.findMatchingField(root, original))
  }

  private fun node(
    parent: AccessibilityNodeInfoCompat?,
    editable: Boolean,
    id: String?,
    top: Int,
  ): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo.obtain()
    info.className = if (editable) "android.widget.EditText" else "android.widget.LinearLayout"
    info.packageName = "com.example.chat"
    info.isEditable = editable
    info.isEnabled = true
    info.isVisibleToUser = true
    info.viewIdResourceName = id
    info.setBoundsInScreen(Rect(0, top, 100, top + 50))
    if (parent != null) shadowOf(parent.unwrap()).addChild(info)
    return AccessibilityNodeInfoCompat.wrap(info)
  }
}
