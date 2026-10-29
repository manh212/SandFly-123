package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleTrackerTest {
  private val screen = Rect(0, 0, 1000, 1000)
  private fun line(text: String, top: Int = 800) = SubtitleLine(text, Rect(200, top, 800, top + 35))

  @Test fun requiresStableTextAndDoesNotRepeatPunctuationChanges() {
    val tracker = SubtitleTracker(false)
    assertNull(tracker.observe(listOf(line("Xin chào bạn!")), screen))
    assertEquals("Xin chào bạn!", tracker.observe(listOf(line("Xin chào bạn!")), screen))
    repeat(4) { assertNull(tracker.observe(listOf(line("Xin chào bạn.")), screen)) }
  }

  @Test fun readsOnlyTheNewLineWhenTwoLinesRollUp() {
    val tracker = SubtitleTracker(false)
    val first = listOf(line("Hôm nay trời đẹp", 750), line("Chúng ta đi thôi", 800))
    tracker.observe(first, screen)
    assertEquals("Hôm nay trời đẹp Chúng ta đi thôi", tracker.observe(first, screen))
    val next = listOf(line("Chúng ta đi thôi", 750), line("Tôi sẽ chờ bạn", 800))
    tracker.observe(next, screen)
    assertEquals("Tôi sẽ chờ bạn", tracker.observe(next, screen))
  }

  @Test fun readsOnlyTheNewWordsWhenLineWrappingChanges() {
    val tracker = SubtitleTracker(false)
    val first = listOf(line("Chúng ta cùng đi ra ngoài"))
    tracker.observe(first, screen)
    tracker.observe(first, screen)
    val next = listOf(line("đi ra ngoài để gặp bạn"))
    tracker.observe(next, screen)
    assertEquals("để gặp bạn", tracker.observe(next, screen))
  }

  @Test fun aLaterRepeatedSentenceCanBeReadAfterABlankGap() {
    val tracker = SubtitleTracker(false)
    val text = listOf(line("Xin chào tất cả mọi người"))
    tracker.observe(text, screen)
    tracker.observe(text, screen)
    repeat(2) { tracker.observe(emptyList(), screen) }
    tracker.observe(text, screen)
    assertEquals("Xin chào tất cả mọi người", tracker.observe(text, screen))
  }

  @Test fun locksTheSubtitleRegionAndNeverMovesToOtherTextAfterAGap() {
    val tracker = SubtitleTracker(true)
    val caption = listOf(line("Đây là phụ đề của video"))
    repeat(3) { tracker.observe(caption, screen) }
    val locked = Rect(tracker.lockedBounds!!)
    repeat(30) { tracker.observe(emptyList(), screen) }
    repeat(5) { assertNull(tracker.observe(listOf(line("Bình luận không phải phụ đề", 100)), screen)) }
    assertEquals(locked, tracker.lockedBounds)
    assertFalse(tracker.visible)
    tracker.reset()
    repeat(3) { tracker.observe(listOf(line("Phụ đề mới ở phía trên", 100)), screen) }
    assertTrue(tracker.lockedBounds!!.centerY() < 300)
  }

  @Test fun keepsTheRegionThroughScreenRotation() {
    val tracker = SubtitleTracker(true)
    repeat(3) { tracker.observe(listOf(line("Một dòng phụ đề")), screen) }
    val before = Rect(tracker.lockedBounds!!)
    val landscape = Rect(0, 0, 2000, 500)
    tracker.resize(screen, landscape)
    assertEquals(before.left * 2, tracker.lockedBounds!!.left)
    assertEquals(before.top / 2, tracker.lockedBounds!!.top)
  }

  @Test fun doesNotLoseVietnameseAccentsOrTreatShortDifferentWordsAsDuplicates() {
    assertEquals("tiếng việt có dấu", SubtitleTracker.normalize("Tiếng Việt: có dấu!"))
    assertFalse(SubtitleTracker.similar("có", "cô"))
    assertTrue(SubtitleTracker.similar("đây là câu phụ đề rất dài hôm nay", "đây là câu phụ đề rất dài hôn nay"))
  }
}
