package com.google.android.accessibility.talkback.compositor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DialPadLabelFilterTest {
  @Test
  public void stripsTelephoneLettersOnlyInDialers() {
    assertEquals("2", DialPadLabelFilter.filter("com.google.android.dialer", "2, A B C"));
    assertEquals("7", DialPadLabelFilter.filter("com.android.dialer", "7 (PQRS)"));
    assertEquals("9", DialPadLabelFilter.filter("com.samsung.android.dialer", "9 W X Y Z"));
  }

  @Test
  public void preservesOrdinaryDigitsAndOtherApplications() {
    assertEquals("2 ABC", DialPadLabelFilter.filter("com.example.messages", "2 ABC"));
    assertEquals("234", DialPadLabelFilter.filter("com.google.android.dialer", "234"));
    assertEquals("2 ABC notes", DialPadLabelFilter.filter("com.google.android.dialer", "2 ABC notes"));
    assertEquals("*", DialPadLabelFilter.filter("com.google.android.dialer", "*"));
    assertEquals("#", DialPadLabelFilter.filter("com.google.android.dialer", "#"));
  }
}
