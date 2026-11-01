package com.google.android.accessibility.talkback.review

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.keyboard.TalkBackPhysicalKeyboardShortcut as Key
import com.google.android.accessibility.utils.FocusFinder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewNavigationTest {
  class Service : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) { }
    override fun onInterrupt() { }
  }

  private class Node(label: String?, top: Int, val container: Node? = null) :
      AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
    val children = mutableListOf<Node>()
    val actions = mutableListOf<Int>()
    init {
      text = label
      isVisibleToUser = true
      isEnabled = true
      isClickable = true
      isLongClickable = true
      setBoundsInScreen(if (container == null) Rect(0, 0, 1000, 1000) else Rect(0, top, 500, top + 50))
      container?.children?.add(this)
    }
    override fun getParent(): AccessibilityNodeInfoCompat? = container
    override fun getChildCount(): Int = children.size
    override fun getChild(index: Int): AccessibilityNodeInfoCompat? = children.getOrNull(index)
    override fun refresh(): Boolean = true
    override fun performAction(action: Int): Boolean { actions += action; return true }
  }

  private lateinit var root: Node
  private lateinit var first: Node
  private lateinit var second: Node
  private lateinit var focus: Node
  private lateinit var review: ScreenReview
  private lateinit var objects: ObjectNavigation
  private val parts = mutableListOf<Feedback.Part>()

  @Before
  fun setup() {
    val service = Robolectric.buildService(Service::class.java).create().get()
    root = Node(null, 0)
    first = Node("first", 100, root)
    second = Node("second", 200, root)
    focus = first
    val monitor = object : AccessibilityFocusMonitor(service, FocusFinder(service), null) {
      override fun getAccessibilityFocus(useInputFocusIfEmpty: Boolean): AccessibilityNodeInfoCompat = focus
      override fun getInputFocus(): AccessibilityNodeInfoCompat = focus
    }
    val pipeline = Pipeline.FeedbackReturner { parts.addAll(it.failovers()); true }
    review = ScreenReview(service, pipeline, monitor)
    objects = ObjectNavigation(service, pipeline, monitor, review)
  }

  @Test
  fun hierarchyNavigationReadsWithoutMovingFocusUntilExplicitlyRequested() {
    objects.perform(Key.OBJECT_PARENT, null)
    assertSame(root, review.currentObject())
    objects.perform(Key.OBJECT_FIRST_CHILD, null)
    objects.perform(Key.OBJECT_NEXT, null)
    assertSame(second, review.currentObject())
    assertTrue(parts.none { it.focus() != null })
    assertTrue(first.actions.isEmpty())
    review.perform(Key.FOCUS_REVIEW_CURSOR)
    assertSame(second, parts.last { it.focus() != null }.focus()!!.target())
    review.perform(Key.REVIEW_LONG_CLICK)
    assertEquals(listOf(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK), second.actions)
  }

  @Test
  fun reviewFollowsNormalFocusWhileTheCursorIsOn() {
    review.selectObject(first)
    review.perform(Key.REVIEW_CURSOR_ON)
    focus = second
    review.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    assertNull(review.currentObject())
    review.perform(Key.REVIEW_CURRENT_WORD)
    assertEquals("second", parts.last { it.speech() != null }.speech()!!.text().toString())
    assertTrue(parts.none { it.focus() != null })
  }

  @Test
  fun movingReviewAfterObjectNavigationActivatesTheNewReviewedItem() {
    review.selectObject(first)
    review.perform(Key.REVIEW_NEXT_LINE)
    review.perform(Key.REVIEW_CLICK)
    assertTrue(first.actions.isEmpty())
    assertEquals(listOf(AccessibilityNodeInfoCompat.ACTION_CLICK), second.actions)
    assertTrue(parts.none { it.focus() != null })
  }
}
