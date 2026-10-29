package com.google.android.accessibility.talkback.compositor

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.utils.LatencyExperiments
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedSpeechExperimentsTest {
  @Before
  fun setUp() { reset() }

  @After
  fun reset() {
    PreparedFocusSpeech.clear()
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FRESH_PREPARED, false)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, false)
  }

  @Test
  fun freshModeUsesCurrentContentWithoutTheAgeFloorButNeverTheWrongTargetType() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FRESH_PREPARED, true)
    val node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
    PreparedFocusSpeech.put(node, EventFeedback.builder().build(), "", NavigationTarget.TARGET_HEADING)
    assertNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_DEFAULT))
    assertNotNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_HEADING))
    assertNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_HEADING))
  }

  @Test
  fun currentContentIsRejectedImmediatelyAfterInvalidation() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.FRESH_PREPARED, true)
    val node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain())
    PreparedFocusSpeech.put(node, EventFeedback.builder().build(), "")
    assertNotNull(PreparedFocusSpeech.peek(node, NavigationTarget.TARGET_DEFAULT))
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    PreparedFocusSpeech.onAccessibilityEvent(event)
    assertNull(PreparedFocusSpeech.take(node, NavigationTarget.TARGET_DEFAULT))
    event.recycle()
  }

  @Test
  fun scopedInvalidationPreservesForeignWindowsAndRejectsUnknownWindows() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, true)
    assertFalse(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, 8, 7))
    assertTrue(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, -1, 7))
    assertTrue(PreparedFocusSpeech.canChange(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, 7, 7))
  }
}
