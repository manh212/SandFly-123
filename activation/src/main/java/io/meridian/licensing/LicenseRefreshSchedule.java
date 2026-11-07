package io.meridian.licensing;

public final class LicenseRefreshSchedule {
  public static final long POLL_MS = 6 * 60 * 60 * 1000L;
  public static final long RETRY_MS = 900_000L;

  private LicenseRefreshSchedule() {}

  public static boolean due(long wall, long elapsed, int boot, long checkedWall, long checkedElapsed, int checkedBoot) {
    if (checkedWall <= 0) return true;
    if (boot >= 0 && boot == checkedBoot && elapsed >= checkedElapsed) return elapsed - checkedElapsed >= POLL_MS;
    return wall < checkedWall || wall - checkedWall >= POLL_MS;
  }
}
