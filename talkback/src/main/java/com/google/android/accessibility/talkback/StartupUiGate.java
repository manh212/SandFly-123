package com.google.android.accessibility.talkback;

final class StartupUiGate {
  private boolean pending;
  private boolean started;

  boolean onServiceConnected(boolean userUnlocked) {
    if (started) {
      return false;
    }
    if (!userUnlocked) {
      pending = true;
      return false;
    }
    pending = false;
    started = true;
    return true;
  }

  boolean onUserUnlocked() {
    if (started || !pending) {
      return false;
    }
    pending = false;
    started = true;
    return true;
  }
}
