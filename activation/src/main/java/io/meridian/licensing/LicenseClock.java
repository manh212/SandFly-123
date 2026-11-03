package io.meridian.licensing;

public final class LicenseClock {
  private LicenseClock() {}
  public static long now(long wall, long floor, long trusted, long elapsed,
      long trustedElapsed, boolean sameBoot) {
    if (sameBoot && elapsed >= trustedElapsed) wall = Math.max(wall, trusted + elapsed - trustedElapsed);
    else if (wall + 300 < Math.max(floor, trusted)) return Long.MAX_VALUE;
    return Math.max(wall, Math.max(floor, trusted));
  }
}
