package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.ForumProtocol;
import org.junit.Test;

public final class ForumProtocolTest {
  @Test public void idsAreUniqueAndSortable() {
    String first = ForumProtocol.id();
    String second = ForumProtocol.id();
    assertTrue(ForumProtocol.validId(first));
    assertTrue(ForumProtocol.validId(second));
    assertNotEquals(first, second);
    assertTrue(ForumProtocol.validId("1700000000000-" + "aaaaaaaaaaaaaaaaaaaa"));
    assertFalse(ForumProtocol.validId("../admins/owner"));
  }

  @Test public void categoriesAreRestrictedToFivePublicGroups() {
    assertEquals(5, ForumProtocol.categories().size());
    for (String category : ForumProtocol.categories()) assertTrue(ForumProtocol.validCategory(category));
    assertFalse(ForumProtocol.validCategory("private-support"));
    assertEquals("Chia sẻ Lua", ForumProtocol.categoryName("lua"));
  }

  @Test public void textIsTrimmedAndBoundsChecked() {
    assertEquals("Xin chào", ForumProtocol.text("  Xin chào  ", 1, 60, "Tên"));
    try {
      ForumProtocol.text(" ", 1, 60, "Tên");
      fail("Blank content must be rejected");
    } catch (IllegalArgumentException expected) {}
  }

  @Test public void notificationIdsCannotEscapeTheirTopic() {
    String topic = "1700000000000-" + "aaaaaaaaaaaaaaaaaaaa";
    String post = "1700000000001-" + "bbbbbbbbbbbbbbbbbbbb";
    assertEquals(topic + "_" + post, ForumProtocol.noticeId(topic, post));
    assertTrue(ForumProtocol.validUid("AbC_-123"));
    assertFalse(ForumProtocol.validUid("supportIndex/alice"));
  }
}
