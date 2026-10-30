package com.google.android.accessibility.talkback.preference.base;

import java.text.Normalizer;
import java.util.Locale;

public final class SearchMatcher {
  private SearchMatcher() {}

  public static String normalize(CharSequence value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replace('đ', 'd')
        .trim()
        .replaceAll("\\s+", " ");
  }

  public static boolean matches(CharSequence query, CharSequence text) {
    return matchesNormalized(normalize(query), normalize(text));
  }

  public static boolean matchesNormalized(String query, String text) {
    for (String word : query.split(" ")) {
      if (!text.contains(word)) {
        return false;
      }
    }
    return true;
  }
}
