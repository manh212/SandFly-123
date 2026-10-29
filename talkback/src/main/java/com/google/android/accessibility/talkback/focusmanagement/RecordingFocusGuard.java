package com.google.android.accessibility.talkback.focusmanagement;

import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;

public final class RecordingFocusGuard {
  private int windowId = -1;
  private boolean recording;
  private boolean allowTouchExploration;

  public void start(int windowId) {
    this.windowId = windowId;
    recording = true;
    allowTouchExploration = false;
  }

  public void stop() {
    recording = false;
    windowId = -1;
  }

  public void onTouchStart() {
    if (recording) {
      allowTouchExploration = true;
    }
  }

  public boolean blocks(int targetWindowId, int sourceAction) {
    if (!recording || windowId < 0 || targetWindowId != windowId) {
      return false;
    }
    return switch (sourceAction) {
      case FocusActionInfo.UNKNOWN,
          FocusActionInfo.FOCUS_SYNCHRONIZATION,
          FocusActionInfo.SCREEN_STATE_CHANGE,
          FocusActionInfo.ENSURE_ON_SCREEN -> true;
      case FocusActionInfo.TOUCH_EXPLORATION -> !allowTouchExploration;
      default -> false;
    };
  }
}
