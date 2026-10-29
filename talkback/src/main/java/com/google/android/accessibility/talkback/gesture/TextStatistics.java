package com.google.android.accessibility.talkback.gesture;

import java.text.BreakIterator;
import java.util.Locale;

public final class TextStatistics {
  private TextStatistics() {}

  public record Result(int lines, int words, int characters) {}

  public static Result count(CharSequence content) {
    String text = content == null ? "" : content.toString();
    if (text.isEmpty()) {
      return new Result(0, 0, 0);
    }
    int lines = 1;
    for (int i = 0; i < text.length(); i++) {
      char character = text.charAt(i);
      if (character == '\r') {
        lines++;
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
      } else if (character == '\n' || character == '\u0085'
          || character == '\u2028' || character == '\u2029') {
        lines++;
      }
    }
    BreakIterator wordIterator = BreakIterator.getWordInstance(Locale.getDefault());
    wordIterator.setText(text);
    int words = 0;
    int start = wordIterator.first();
    for (int end = wordIterator.next(); end != BreakIterator.DONE; end = wordIterator.next()) {
      for (int i = start; i < end; ) {
        int codePoint = text.codePointAt(i);
        if (Character.isLetterOrDigit(codePoint)) {
          words++;
          break;
        }
        i += Character.charCount(codePoint);
      }
      start = end;
    }
    BreakIterator characterIterator = BreakIterator.getCharacterInstance(Locale.ROOT);
    characterIterator.setText(text);
    int characters = 0;
    while (characterIterator.next() != BreakIterator.DONE) {
      characters++;
    }
    return new Result(lines, words, characters);
  }
}
