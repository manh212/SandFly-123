package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import io.meridian.licensing.LicenseLease;
import org.junit.Test;

public final class LicenseLeaseTest {
  private static final long SERVER = 1_700_000_000_000L;
  private static final long ELAPSED = 5_000_000L;
  private static final int BOOT = 4;

  @Test public void withinSevenDaysIsAllowedButExactBoundaryIsNot() {
    assertTrue(LicenseLease.valid(SERVER + 1_000L, ELAPSED + 1_000L, BOOT,
        SERVER, ELAPSED, BOOT, SERVER));
    assertTrue(LicenseLease.valid(SERVER + LicenseLease.MAX_OFFLINE_MS - 1,
        ELAPSED + LicenseLease.MAX_OFFLINE_MS - 1, BOOT, SERVER, ELAPSED, BOOT, SERVER));
    assertFalse(LicenseLease.valid(SERVER + LicenseLease.MAX_OFFLINE_MS,
        ELAPSED + LicenseLease.MAX_OFFLINE_MS, BOOT, SERVER, ELAPSED, BOOT, SERVER));
  }

  @Test public void restartUsesWallClockAndBlocksRollback() {
    assertTrue(LicenseLease.valid(SERVER + 10_000L, 20_000L, BOOT + 1,
        SERVER, ELAPSED, BOOT, SERVER));
    assertFalse(LicenseLease.valid(SERVER - 1_000_000L, 20_000L, BOOT + 1,
        SERVER, ELAPSED, BOOT, SERVER));
    assertFalse(LicenseLease.valid(SERVER + LicenseLease.MAX_OFFLINE_MS, 20_000L, BOOT + 1,
        SERVER, ELAPSED, BOOT, SERVER));
  }

  @Test public void backwardsClockAndMissingAnchorDenyAccess() {
    assertFalse(LicenseLease.valid(SERVER, ELAPSED, BOOT, 0, 0, BOOT, SERVER));
    assertFalse(LicenseLease.valid(SERVER, ELAPSED - 1, BOOT,
        SERVER, ELAPSED, BOOT, SERVER));
    assertFalse(LicenseLease.valid(SERVER, ELAPSED, BOOT,
        SERVER, ELAPSED, BOOT, SERVER + 1_000_000));
  }

  @Test public void oldServerTimeCannotExtendLease() {
    assertFalse(LicenseLease.newerVerification(SERVER - 1, SERVER));
    assertTrue(LicenseLease.newerVerification(SERVER, SERVER));
    assertTrue(LicenseLease.newerVerification(SERVER + 1, SERVER));
  }
}
