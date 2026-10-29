package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TextStatisticsTest {
  @Test
  public void emptyFieldHasNoContent() {
    assertEquals(new TextStatistics.Result(0, 0, 0), TextStatistics.count(""));
    assertEquals(new TextStatistics.Result(0, 0, 0), TextStatistics.count(null));
  }

  @Test
  public void vietnameseWordsIgnorePunctuationAndCountWhitespace() {
    assertEquals(new TextStatistics.Result(2, 4, 18),
        TextStatistics.count("Xin chào\nThế giới!"));
  }

  @Test
  public void windowsAndUnicodeLineBreaksAreCountedOnce() {
    assertEquals(5, TextStatistics.count("a\r\nb\rc\u2028d\u2029").lines());
    assertEquals(2, TextStatistics.count("a\n").lines());
  }

  @Test
  public void emojiAndCombiningAccentsAreCharacters() {
    assertEquals(3, TextStatistics.count("A😀e\u0301").characters());
  }

  @Test
  public void punctuationIsNotAWord() {
    assertEquals(0, TextStatistics.count(" ... 😀 ").words());
    assertEquals(new TextStatistics.Result(1, 0, 3), TextStatistics.count("   "));
  }
}
