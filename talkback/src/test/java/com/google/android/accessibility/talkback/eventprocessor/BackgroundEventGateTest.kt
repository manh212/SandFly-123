package com.google.android.accessibility.talkback.eventprocessor

import android.os.Looper
import com.google.android.accessibility.utils.LatencyExperiments
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.utils.Performance
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundEventGateTest {
  private val delivered = mutableListOf<String>()
  private val events = mutableListOf<AccessibilityEvent>()
  private lateinit var gate: BackgroundEventGate

  @Before
  fun setUp() {
    Performance.getInstance().setComputeStatsEnabled(true)
    EventProcessingStats.reset()
    gate = BackgroundEventGate { event, _ -> delivered += event.packageName.toString() }
    gate.setProcessingLevel("very_low")
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "foreground", 1))
  }

  @After
  fun tearDown() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.INPUT_PRIORITY, false)
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, false)
    gate.clear()
    events.forEach { it.recycle() }
    EventProcessingStats.reset()
    Performance.getInstance().setComputeStatsEnabled(false)
  }

  private fun event(type: Int, source: String = "com.android.systemui", window: Int = 2): AccessibilityEvent =
    AccessibilityEvent.obtain(type).also {
      it.packageName = source
      shadowOf(it).setWindowId(window)
      events += it
    }

  private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

  @Test
  fun mixedBackgroundSourcesAndTypes_shareOneSlotAndOneRateLimit() {
    repeat(30) {
      assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)))
      assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SELECTED, "background", 3)))
    }
    advance(499)
    assertTrue(delivered.isEmpty())
    advance(1)
    assertEquals(listOf("background"), delivered)
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    advance(499)
    assertEquals(1, delivered.size)
    advance(1)
    assertEquals(listOf("background", "com.android.systemui"), delivered)
  }

  @Test
  fun touchingDefersBackgroundUntilInputGraceHasElapsed() {
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)))
    advance(2000)
    assertTrue(delivered.isEmpty())
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END))
    advance(149)
    assertTrue(delivered.isEmpty())
    advance(1)
    assertEquals(1, delivered.size)
  }

  @Test
  fun activeContentAndInputResponses_areNeverThrottled() {
    for (type in listOf(
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
      AccessibilityEvent.TYPE_VIEW_CLICKED,
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
      AccessibilityEvent.TYPE_ANNOUNCEMENT)) {
      assertTrue(gate.onAccessibilityEvent(event(type, "foreground", 1)))
    }
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)))
  }

  @Test
  fun switchingToSystemUi_keepsItsInteractiveUpdates() {
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SELECTED)))
  }

  @Test
  fun exploringSystemUiByTouch_doesNotWaitForItsFirstFocusEvent() {
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)))
  }

  @Test
  fun expectedScrollResponseFromAnotherWindow_isPreserved() {
    gate.clear()
    gate = BackgroundEventGate(
      { event, _ -> delivered += event.packageName.toString() },
      { event -> event.windowId == 2 })
    gate.setProcessingLevel("very_low")
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "foreground", 1))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SCROLLED)))
    assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)))
  }

  @Test
  fun windowTransitionsArePreservedWhileBackgroundGeometryIsCoalesced() {
    for (changes in listOf(AccessibilityEvent.WINDOWS_CHANGE_ADDED,
      AccessibilityEvent.WINDOWS_CHANGE_REMOVED, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE,
      AccessibilityEvent.WINDOWS_CHANGE_FOCUSED,
      AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED)) {
      val changed = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
      ReflectionHelpers.callInstanceMethod<Any>(changed, "setWindowChanges",
        ClassParameter.from(Int::class.javaPrimitiveType, changes))
      assertTrue(gate.onAccessibilityEvent(changed))
    }
    val changed = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
    ReflectionHelpers.callInstanceMethod<Any>(changed, "setWindowChanges",
      ClassParameter.from(Int::class.javaPrimitiveType, AccessibilityEvent.WINDOWS_CHANGE_BOUNDS))
    assertFalse(gate.onAccessibilityEvent(changed))
  }

  @Test
  fun modeChangeAndShutdown_cancelPendingCopies() {
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    gate.setProcessingLevel("medium")
    advance(1000)
    assertTrue(delivered.isEmpty())
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)))
    gate.setProcessingLevel("very_low")
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    gate.clear()
    advance(1000)
    assertTrue(delivered.isEmpty())
  }

  @Test
  fun pendingUpdateBecomingForeground_isDiscardedInsteadOfReplayed() {
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    advance(500)
    assertTrue(delivered.isEmpty())
    assertTrue(EventProcessingStats.snapshot().contains("coalesced dropped admitted"))
  }
  @Test
  fun priorityModeDefersActiveTextWithImmediateInvalidationAndKeepsFocus() {
    var invalidated = 0
    gate.clear()
    gate = BackgroundEventGate(
      { event, _ -> delivered += event.packageName.toString() },
      { false },
      { invalidated++ },
    )
    gate.setProcessingLevel("default")
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.INPUT_PRIORITY, true)
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "foreground", 1))
    gate.onUserInput()
    val update = event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "foreground", 1)
    update.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
    assertFalse(gate.onAccessibilityEvent(update))
    assertEquals(1, invalidated)
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "foreground", 1)))
    advance(500)
    assertEquals(listOf("foreground"), delivered)
  }

  @Test
  fun scopedModeDoesNotLetHelperWindowStateReplaceTheInteractionWindow() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, true)
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "miui.helper", 8)))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "foreground", 1)))
    assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "miui.helper", 8)))
  }
}
