package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.android.accessibility.talkback.gesture.SmartContextGestures.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SmartContextGesturesTest {
  @Test
  public void exactMatchingIgnoresCaseButPreservesVietnameseAccents() {
    Rule rule = new Rule("Chương tiếp theo", "exact", "", false, true);
    assertTrue(rule.matches("CHƯƠNG TIẾP THEO"));
    assertFalse(rule.matches("Chuong tiep theo"));
    assertFalse(rule.matches("Đọc chương tiếp theo"));
  }

  @Test
  public void caseSensitiveMatchingRequiresSameCase() {
    Rule rule = new Rule("Next", "exact", "", true, true);
    assertTrue(rule.matches("Next"));
    assertFalse(rule.matches("NEXT"));
  }

  @Test
  public void flexibleMatchingSupportsContainsStartsAndEnds() {
    assertTrue(new Rule("tiếp theo", "contains", "", false, true)
        .matches("Đọc chương tiếp theo"));
    assertTrue(new Rule("Chương", "starts", "", false, true)
        .matches("Chương tiếp theo"));
    assertTrue(new Rule("tiếp theo", "ends", "", false, true)
        .matches("Chương tiếp theo"));
  }

  @Test
  public void relativeMatchingUsesSecondPhrase() {
    assertTrue(new Rule("Chương", "before", "tiếp theo", false, true)
        .matches("Đọc chương tiếp theo"));
    assertTrue(new Rule("tiếp theo", "after", "Chương", false, true)
        .matches("Đọc chương tiếp theo"));
    assertFalse(new Rule("Chương", "before", "tiếp theo", false, true)
        .matches("Đọc tiếp theo chương"));
  }

  @Test
  public void prefixSuffixModesRequireOtherText() {
    assertTrue(new Rule("tiếp theo", "prefix", "", false, true)
        .matches("Chương tiếp theo"));
    assertTrue(new Rule("Chương", "suffix", "", false, true)
        .matches("Chương tiếp theo"));
    assertFalse(new Rule("Chương", "prefix", "", false, true).matches("Chương"));
    assertFalse(new Rule("Chương", "suffix", "", false, true).matches("Chương"));
  }

  @Test
  public void disabledRulesNeverMatch() {
    assertFalse(new Rule("Next", "exact", "", false, false).matches("Next"));
  }
}
