package com.google.android.accessibility.talkback.volume;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TouchExplorationResumePolicyTest {
  @Test
  public void keyguardWaitsForVisibleLockScreen() {
    TouchExplorationResumePolicy policy = new TouchExplorationResumePolicy();
    policy.setSuspended(true, true, false);
    assertFalse(policy.update("KEYGUARD", false, true));
    assertTrue(policy.isSuspended());
    assertTrue(policy.update("KEYGUARD", true, true));
    assertFalse(policy.isSuspended());
  }

  @Test
  public void turningOffOnLockScreenWaitsForNextAppearance() {
    TouchExplorationResumePolicy policy = new TouchExplorationResumePolicy();
    policy.setSuspended(true, true, true);
    assertFalse(policy.update("KEYGUARD", true, true));
    assertFalse(policy.update("KEYGUARD", false, true));
    assertTrue(policy.update("KEYGUARD", true, true));
  }

  @Test
  public void screenOffAndScreenOnHaveDifferentTriggers() {
    TouchExplorationResumePolicy policy = new TouchExplorationResumePolicy();
    policy.setSuspended(true, true, false);
    assertTrue(policy.update("SCREEN_OFF", false, false));
    policy.setSuspended(true, true, false);
    assertFalse(policy.update("SCREEN_ON", false, false));
    assertTrue(policy.update("SCREEN_ON", true, false));
  }

  @Test
  public void manualModeNeverRestoresAutomatically() {
    TouchExplorationResumePolicy policy = new TouchExplorationResumePolicy();
    policy.setSuspended(true, true, false);
    assertFalse(policy.update("MANUAL", false, true));
    assertFalse(policy.update("MANUAL", true, true));
    assertTrue(policy.isSuspended());
  }

  @Test
  public void showingLockScreenWithoutScreenOffRestoresOnce() {
    TouchExplorationResumePolicy policy = new TouchExplorationResumePolicy();
    policy.setSuspended(true, true, false);
    assertTrue(policy.update("KEYGUARD", true, true));
    assertFalse(policy.update("KEYGUARD", true, true));
  }
}
