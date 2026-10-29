package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.utils.LatencyExperiments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeferredTraversalRevisionTest {
  @Before
  fun setUp() {
    TraversalTreeCache.clear("test")
    TraversalTreeCache.setBuildingWindow(7)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, true)
  }

  @After
  fun reset() {
    TraversalTreeCache.setBuildingWindow(-1)
    TraversalTreeCache.clear("test")
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, false)
  }

  private fun textChange(window: Int): AccessibilityEvent =
    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).also {
      shadowOf(it).setWindowId(window)
      it.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
    }

  @Test
  fun deferredContentInvalidatesAnInFlightBuildEvenWithoutASavedTree() {
    val before = TraversalTreeCache.revision()
    val event = textChange(7)
    TraversalTreeCache.noteDeferredEvent(event)
    assertTrue(TraversalTreeCache.revision() > before)
    event.recycle()
  }

  @Test
  fun deferredForeignUpdatesPreserveTheInFlightWindowRevision() {
    val before = TraversalTreeCache.revision()
    val event = textChange(8)
    TraversalTreeCache.noteDeferredEvent(event)
    assertEquals(before, TraversalTreeCache.revision())
    event.recycle()
  }

  @Test
  fun admittedContentAlsoInvalidatesAnInFlightBuildBeforeTheTreeExists() {
    val before = TraversalTreeCache.revision()
    val event = textChange(7)
    TraversalTreeCache.onAccessibilityEvent(event)
    assertTrue(TraversalTreeCache.revision() > before)
    event.recycle()
  }

  @Test
  fun unknownWindowChangesInvalidateConservatively() {
    val before = TraversalTreeCache.revision()
    val event = textChange(-1)
    TraversalTreeCache.noteDeferredEvent(event)
    assertTrue(TraversalTreeCache.revision() > before)
    event.recycle()
  }
}
