package io.meridian.licensing;

public final class LicenseLease {
  public static final long MAX_OFFLINE_MS = 7L * 24 * 60 * 60 * 1000;
  public static final long CLOCK_TOLERANCE_MS = 5L * 60 * 1000;

  private LicenseLease() {}

  public static boolean valid(long wall, long elapsed, int boot, long verifiedServer,
      long verifiedElapsed, int verifiedBoot, long highestWall) {
    if (verifiedServer <= 0 || wall <= 0 || elapsed < 0) return false;
    if (wall + CLOCK_TOLERANCE_MS < verifiedServer
        || wall + CLOCK_TOLERANCE_MS < highestWall) return false;
    if (verifiedBoot >= 0 && verifiedBoot == boot) {
      return elapsed >= verifiedElapsed && elapsed - verifiedElapsed < MAX_OFFLINE_MS;
    }
    return wall >= verifiedServer && wall - verifiedServer < MAX_OFFLINE_MS;
  }

  public static boolean newerVerification(long serverTime, long previousServerTime) {
    return serverTime > 0 && serverTime >= previousServerTime;
  }
}
