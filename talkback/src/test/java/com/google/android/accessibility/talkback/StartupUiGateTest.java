package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StartupUiGateTest {
  @Test
  public void lockedBootDefersUiUntilUnlock() {
    StartupUiGate gate = new StartupUiGate();

    assertFalse(gate.onServiceConnected(false));
    assertTrue(gate.onUserUnlocked());
    assertFalse(gate.onUserUnlocked());
    assertFalse(gate.onServiceConnected(true));
  }

  @Test
  public void unlockedConnectionStartsUiImmediatelyOnlyOnce() {
    StartupUiGate gate = new StartupUiGate();

    assertTrue(gate.onServiceConnected(true));
    assertFalse(gate.onUserUnlocked());
    assertFalse(gate.onServiceConnected(true));
  }

  @Test
  public void unlockBeforeServiceConnectionDoesNotStartUiEarly() {
    StartupUiGate gate = new StartupUiGate();

    assertFalse(gate.onUserUnlocked());
    assertTrue(gate.onServiceConnected(true));
    assertFalse(gate.onUserUnlocked());
  }

  @Test
  public void repeatedLockedConnectionStillStartsUiOnceAfterUnlock() {
    StartupUiGate gate = new StartupUiGate();

    assertFalse(gate.onServiceConnected(false));
    assertFalse(gate.onServiceConnected(false));
    assertTrue(gate.onUserUnlocked());
    assertFalse(gate.onUserUnlocked());
  }
}
