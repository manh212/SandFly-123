package com.google.android.accessibility.talkback.interpreters;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AutoScrollSettleTest {
  @Test
  public void quietScrollKeepsItsNormalSettleDelay() {
    assertEquals(110, AutoScrollInterpreter.AutoScrollHandler.settleDelay(1000, 1000, 110));
  }

  @Test
  public void repeatedEventsCannotExtendTheWaitPastTheDeadline() {
    assertEquals(40, AutoScrollInterpreter.AutoScrollHandler.settleDelay(1000, 1180, 110));
    assertEquals(0, AutoScrollInterpreter.AutoScrollHandler.settleDelay(1000, 1300, 110));
  }
}
