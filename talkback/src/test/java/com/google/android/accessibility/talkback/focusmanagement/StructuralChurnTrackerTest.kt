package com.google.android.accessibility.talkback.focusmanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The adaptive path is window-scoped, bounded and short lived. */
class StructuralChurnTrackerTest {
  @Test fun fourQuickChangesActivate() {
    val tracker = StructuralChurnTracker()
    repeat(3) { tracker.record(42, 100L + it * 100) }
    assertFalse(tracker.isChurning(42, 350))
    tracker.record(42, 400)
    assertTrue(tracker.isChurning(42, 500))
  }

  @Test fun slowChangesDoNotActivate() {
    val tracker = StructuralChurnTracker()
    listOf(100L, 1200L, 2300L, 3400L).forEach { tracker.record(42, it) }
    assertFalse(tracker.isChurning(42, 3500))
  }

  @Test fun activityInOneWindowDoesNotAffectAnother() {
    val tracker = StructuralChurnTracker()
    repeat(4) { tracker.record(42, 100L + it * 50) }
    assertTrue(tracker.isChurning(42, 400))
    assertFalse(tracker.isChurning(43, 400))
    assertFalse(tracker.isChurning(-1, 400))
  }

  @Test fun orderedTraversalResumesAfterQuietPeriod() {
    val tracker = StructuralChurnTracker()
    repeat(4) { tracker.record(42, 100L + it * 50) }
    assertTrue(tracker.isChurning(42, 2200))
    assertFalse(tracker.isChurning(42, 2250))
  }

  @Test fun continuedChangesExtendLivePeriod() {
    val tracker = StructuralChurnTracker()
    repeat(4) { tracker.record(42, 100L + it * 100) }
    repeat(4) { tracker.record(42, 1600L + it * 100) }
    assertTrue(tracker.isChurning(42, 3700))
    assertFalse(tracker.isChurning(42, 3900))
  }

  @Test fun boundedMemoryEvictsOldWindow() {
    val tracker = StructuralChurnTracker()
    repeat(4) { tracker.record(42, 100L + it * 10) }
    (43..46).forEach { tracker.record(it, 300) }
    assertFalse(tracker.isChurning(42, 400))
  }

  @Test fun expensiveTreeBuildEnablesLocalNavigationWithoutAnEventBurst() {
    val tracker = StructuralChurnTracker()
    tracker.noteSlowBuild(42, 1000L, 733L)
    assertTrue(tracker.isChurning(42, 8900L))
    assertFalse(tracker.isChurning(42, 9000L))
    assertFalse(tracker.isChurning(43, 2000L))
  }

  @Test fun normalTreeBuildDoesNotChangeNavigationMode() {
    val tracker = StructuralChurnTracker()
    tracker.noteSlowBuild(42, 1000L, 80L)
    assertFalse(tracker.isChurning(42, 1100L))
  }

  @Test fun clockResetDiscardsOldRateWindow() {
    val tracker = StructuralChurnTracker()
    repeat(3) { tracker.record(42, 5000L + it * 10) }
    tracker.record(42, 100)
    assertFalse(tracker.isChurning(42, 150))
  }
}
