package com.google.android.accessibility.talkback.volume;

public final class TouchExplorationResumePolicy {
  private boolean suspended;
  private boolean screenOn;
  private boolean locked;

  public boolean isSuspended() {
    return suspended;
  }

  public void setSuspended(boolean value, boolean interactive, boolean keyguardLocked) {
    suspended = value;
    screenOn = interactive;
    locked = keyguardLocked;
  }

  public boolean update(String mode, boolean interactive, boolean keyguardLocked) {
    boolean restore = suspended && switch (mode) {
      case "SCREEN_ON" -> !screenOn && interactive;
      case "SCREEN_OFF" -> screenOn && !interactive;
      case "MANUAL" -> false;
      default -> interactive && keyguardLocked && (!screenOn || !locked);
    };
    screenOn = interactive;
    locked = keyguardLocked;
    if (restore) suspended = false;
    return restore;
  }
}
