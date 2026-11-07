package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.ForumProtocol;
import java.util.Arrays;
import org.junit.Test;

public final class ForumProtocolTest {
  @Test public void categoriesStayFixedAndSharingIsGeneral() {
    assertEquals(5, ForumProtocol.categories().size());
    assertEquals(Arrays.asList("general", "questions", "lua", "ideas", "guides"),
        ForumProtocol.categories());
    assertEquals("Chia sẻ", ForumProtocol.categoryName("lua"));
    assertFalse(ForumProtocol.validCategory("files"));
    assertThrows(IllegalArgumentException.class,
        () -> ForumProtocol.validDisplayName("Nhà phát triển"));
  }

  @Test public void ratingsFadeBehindNewActivity() {
    long week = 604_800_000L;
    assertEquals(-2 * week, ForumProtocol.ratingOffset(1));
    assertEquals(0L, ForumProtocol.ratingOffset(3));
    assertEquals(2 * week, ForumProtocol.ratingOffset(5));
    long ratedFiveOld = 1_000_000_000_000L + ForumProtocol.ratingOffset(5);
    long ratedThreeNew = 1_000_000_000_000L + 3 * week;
    long ratedFiveNew = 1_000_000_000_000L + 3 * week + ForumProtocol.ratingOffset(5);
    assertTrue(ratedThreeNew > ratedFiveOld);
    assertTrue(ratedFiveNew > ratedThreeNew);
    assertTrue(ratedFiveNew > ratedFiveOld);
    assertThrows(IllegalArgumentException.class, () -> ForumProtocol.ratingOffset(0));
  }

  @Test public void dailyQuotaUsesVietnamMidnightNotLocalClock() {
    long utc = 1_735_776_000_000L;
    long before = utc - 25_200_000L - 1;
    long after = before + 1;
    assertEquals(ForumProtocol.vietnamDay(before) + 1, ForumProtocol.vietnamDay(after));
    assertEquals(after, ForumProtocol.nextVietnamMidnight(before));
    assertTrue(ForumProtocol.canUseDailySlot(0));
    assertTrue(ForumProtocol.canUseDailySlot(2));
    assertFalse(ForumProtocol.canUseDailySlot(3));
  }

  @Test public void multilineTextIsAllowedButControlCharactersAndInvalidPollsAreNot() {
    assertEquals("Dòng một\nDòng hai", ForumProtocol.text("Dòng một\nDòng hai", 1, 100, "Nội dung"));
    assertThrows(IllegalArgumentException.class,
        () -> ForumProtocol.text("Dòng đầu\u0001", 1, 100, "Nội dung"));
    assertEquals(3, ForumProtocol.pollOptions(Arrays.asList("A", "B", "C")).length());
    assertThrows(IllegalArgumentException.class,
        () -> ForumProtocol.pollOptions(Arrays.asList("Trùng", "trùng")));
  }

  @Test public void generatedIdsAreValidAndUnexpectedUserIdsAreRejected() {
    assertTrue(ForumProtocol.validId(ForumProtocol.id()));
    assertFalse(ForumProtocol.validId("../etc/passwd"));
    assertFalse(ForumProtocol.validUid("a/b"));
  }
}
