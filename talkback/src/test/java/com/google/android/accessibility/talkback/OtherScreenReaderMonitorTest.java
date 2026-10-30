package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import org.junit.Test;

public class OtherScreenReaderMonitorTest {
  private static final String OWN = "io.github.aaron_gh.meridian";
  private static final int SPOKEN = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
  private static final int EXPLORE =
      AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;

  @Test
  public void yieldsForAnotherScreenReaderRequestingTouchExploration() {
    assertTrue(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN, "another.reader", SPOKEN, EXPLORE));
  }

  @Test
  public void ignoresOwnServiceAndUnrelatedAccessibilityServices() {
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(OWN, OWN, SPOKEN, EXPLORE));
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN, "another.reader", AccessibilityServiceInfo.FEEDBACK_GENERIC, EXPLORE));
    assertFalse(
        OtherScreenReaderMonitor.isCompetingScreenReader(
            OWN, "another.reader", SPOKEN, 0));
  }
}
