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
  fun inputPriorityExperimentSharesOneBackgroundSlotAndRateLimit() {
    gate.setProcessingLevel("medium")
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.INPUT_PRIORITY, true)
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
  fun inputPriorityExperimentWaitsUntilInputGraceHasElapsed() {
    gate.setProcessingLevel("medium")
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.INPUT_PRIORITY, true)
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
  fun focusInputAndAutomaticNotificationsAreAdmittedImmediately() {
    for (type in listOf(
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
      AccessibilityEvent.TYPE_VIEW_CLICKED,
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE,
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)) {
      assertTrue(gate.onAccessibilityEvent(event(type, "foreground", 1)))
    }
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)))
  }

  @Test
  fun switchingToSystemUiKeepsItsInteractiveUpdates() {
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SCROLLED)))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SELECTED)))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_ANNOUNCEMENT)))
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
  fun windowTransitionsAreAdmittedAndGeometryOnlyInvalidatesCaches() {
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
    advance(1000)
    assertTrue(delivered.isEmpty())
  }

  @Test
  fun enteringStrictModeCancelsPendingCopiesAndLeavingRestoresSubscriptions() {
    assertTrue(gate.isStrict)
    val mask = gate.subscribedEventTypes()
    assertTrue(mask and AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED != 0)
    assertTrue(mask and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0)
    assertEquals(0, mask and AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT)
    gate.setProcessingLevel("medium")
    assertFalse(gate.isStrict)
    assertEquals(AccessibilityEvent.TYPES_ALL_MASK, gate.subscribedEventTypes())
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.INPUT_PRIORITY, true)
    assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)))
    gate.setProcessingLevel("very_low")
    advance(1000)
    assertTrue(delivered.isEmpty())
  }

  @Test
  fun idleSelectionAndAnnouncementsAreDroppedWithoutCacheInvalidation() {
    var invalidated = 0
    gate.clear()
    gate = BackgroundEventGate({ _, _ -> }, { false }, { invalidated++ })
    gate.setProcessingLevel("very_low")
    assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SELECTED)))
    assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_ANNOUNCEMENT)))
    assertEquals(0, invalidated)
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

  private fun structural(window: Int = 1, pkg: String = "foreground"): AccessibilityEvent =
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg, window).also {
      it.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
    }

  @Test
  fun repeatedSubtreeUpdatesOnlyInvalidateCachesFromTheFirstEvent() {
    var invalidated = 0
    gate.clear()
    gate = BackgroundEventGate(
      { event, _ -> delivered += event.packageName.toString() },
      { false },
      { invalidated++ },
    )
    gate.setProcessingLevel("very_low")
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "foreground", 1))
    repeat(100) { assertFalse(gate.onAccessibilityEvent(structural())) }
    assertEquals(100, invalidated)
    advance(1500)
    assertTrue(delivered.isEmpty())
    assertTrue(EventProcessingStats.snapshot().contains("dropped cache_only admitted"))
  }

  @Test
  fun mediumPriorityCoalescesActiveSubtreeChangesDuringTouchButKeepsScrollAndInput() {
    gate.setProcessingLevel("medium")
    gate.setInteractionPriorityEnabled(true)
    advance(1000)
    repeat(4) { assertTrue(gate.onAccessibilityEvent(structural())) }
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, "foreground", 1)))
    repeat(5) { assertFalse(gate.onAccessibilityEvent(structural())) }

    val scrollContent = structural().also {
      it.fromIndex = 3
      it.toIndex = 6
      it.itemCount = 20
    }
    assertTrue(gate.onAccessibilityEvent(scrollContent))
    val scrollDelta = structural().also {
      it.scrollDeltaX = 0
      it.scrollDeltaY = 32
    }
    assertTrue(gate.onAccessibilityEvent(scrollDelta))
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "foreground", 1)))
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "foreground", 1)))
    advance(200)
    assertTrue(delivered.isEmpty())
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, "foreground", 1))
    advance(149)
    assertTrue(delivered.isEmpty())
    advance(1)
    assertEquals(listOf("foreground"), delivered)
  }

  @Test
  fun activeFloodLeavesFocusAndClicksImmediate() {
    repeat(4) { gate.onAccessibilityEvent(structural()) }
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "foreground", 1)))
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_VIEW_CLICKED, "foreground", 1)))
    advance(500)
    assertTrue(delivered.isEmpty())
  }

  @Test
  fun passiveTextUpdatesOnlyInvalidateCachesAndTypingIsAdmitted() {
    repeat(4) { gate.onAccessibilityEvent(structural()) }
    val text = event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "foreground", 1)
    text.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
    assertFalse(gate.onAccessibilityEvent(text))
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      "foreground", 1)))
  }

  @Test
  fun unsolicitedScrollOnlyInvalidatesCachesButExpectedScrollIsImmediate() {
    gate.clear()
    gate = BackgroundEventGate(
      { event, _ -> delivered += event.packageName.toString() },
      { event -> event.contentDescription == "expected" },
      { },
    )
    gate.setProcessingLevel("very_low")
    gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
      "foreground", 1))
    repeat(4) { gate.onAccessibilityEvent(structural()) }
    advance(600)
    delivered.clear()
    val unsolicited = event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "foreground", 1)
    assertFalse(gate.onAccessibilityEvent(unsolicited))
    val expected = event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "foreground", 1)
    expected.contentDescription = "expected"
    assertTrue(gate.onAccessibilityEvent(expected))
    advance(1000)
    assertTrue(delivered.isEmpty())
  }

  @Test
  fun touchInteractionKeepsHoverAndScrollPositionButSkipsPlainSubtreeUpdates() {
    repeat(4) { gate.onAccessibilityEvent(structural()) }
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, "foreground", 1)))
    assertFalse(gate.onAccessibilityEvent(structural()))
    val position = structural().also {
      it.fromIndex = 3
      it.toIndex = 6
      it.itemCount = 20
    }
    assertTrue(gate.onAccessibilityEvent(position))
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, "foreground", 1)))
    advance(1000)
    assertTrue(delivered.isEmpty())
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_END, "foreground", 1)))
    advance(500)
    assertTrue(delivered.isEmpty())
  }

  private fun idleTicker(from: Int = 7, to: Int = 10): AccessibilityEvent =
    event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "foreground", 1).also {
      it.itemCount = Int.MAX_VALUE
      it.fromIndex = from
      it.toIndex = to
      it.scrollDeltaX = 12
      it.scrollDeltaY = 0
    }

  @Test
  @Config(sdk = [26])
  fun oldAndroidDoesNotAccessUnavailableScrollDeltaMethods() {
    advance(600)
    val oldScroll = event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "foreground", 1)
    oldScroll.itemCount = Int.MAX_VALUE
    assertFalse(gate.onAccessibilityEvent(oldScroll))
    gate.onUserInput()
    assertFalse(gate.onAccessibilityEvent(structural()))
  }

  @Test
  fun passiveCarouselDoesNotEnterFeedbackPipelineEvenWhenIndicesChange() {
    advance(600)
    repeat(10) { assertFalse(gate.onAccessibilityEvent(idleTicker())) }
    assertFalse(gate.onAccessibilityEvent(idleTicker(8, 11)))
    advance(1000)
    assertTrue(delivered.isEmpty())
  }

  @Test
  fun passiveTickerIsNeverDroppedDuringTouchExploration() {
    advance(600)
    assertFalse(gate.onAccessibilityEvent(idleTicker()))
    assertTrue(gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, "foreground", 1)))
    assertTrue(gate.onAccessibilityEvent(idleTicker()))
  }

  @Test
  fun expectedTickerScrollResponseIsPreserved() {
    gate.clear()
    gate = BackgroundEventGate(
      { event, _ -> delivered += event.packageName.toString() },
      { event -> event.contentDescription == "expected" },
      { },
    )
    gate.setProcessingLevel("very_low")
    gate.onAccessibilityEvent(
      event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "foreground", 1))
    advance(600)
    assertFalse(gate.onAccessibilityEvent(idleTicker()))
    val expected = idleTicker()
    expected.contentDescription = "expected"
    assertTrue(gate.onAccessibilityEvent(expected))
  }

  @Test
  fun scopedModeDoesNotLetHelperWindowStateReplaceTheInteractionWindow() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.SCOPED_CACHE, true)
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "miui.helper", 8)))
    gate.onUserInput()
    assertTrue(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "foreground", 1)))
    assertFalse(gate.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "miui.helper", 8)))
  }
}
