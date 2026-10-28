package com.google.android.accessibility.talkback.gesture

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.gesture.CopyMoreCollector.Callback
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyMoreCollectorTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private fun textNode(text: String) = AccessibilityNodeInfo.obtain().apply { setText(text) }

  @Test
  fun copiesFollowingSiblingsAfterNestedFocusOnTheSamePage() {
    val root = textNode("root")
    val before = textNode("before")
    val group = textNode("group")
    val focused = textNode("focused")
    val followingInGroup = textNode("following in group")
    val followingInRoot = textNode("following in root")
    shadowOf(root).addChild(before)
    shadowOf(root).addChild(group)
    shadowOf(group).addChild(focused)
    shadowOf(group).addChild(followingInGroup)
    shadowOf(root).addChild(followingInRoot)
    val copied = intArrayOf(0)
    val collector =
      CopyMoreCollector(
        context,
        object : Callback {
          override fun onCopied(count: Int) {
            copied[0] = count
          }

          override fun onNothing() {
            copied[0] = -1
          }
        },
      )

    collector.start(AccessibilityNodeInfoCompat.wrap(focused))

    assertEquals(3, copied[0])
  }
}
