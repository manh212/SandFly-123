package com.google.android.accessibility.talkback.focusmanagement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import org.junit.Test;

public class RecordingFocusGuardTest {
  @Test
  public void recordingBlocksAutomaticFocusInItsWindow() {
    RecordingFocusGuard guard = new RecordingFocusGuard();
    guard.start(7);
    assertTrue(guard.blocks(7, FocusActionInfo.ENSURE_ON_SCREEN));
    assertTrue(guard.blocks(7, FocusActionInfo.SCREEN_STATE_CHANGE));
    assertTrue(guard.blocks(7, FocusActionInfo.FOCUS_SYNCHRONIZATION));
    assertTrue(guard.blocks(7, FocusActionInfo.UNKNOWN));
    assertFalse(guard.blocks(8, FocusActionInfo.SCREEN_STATE_CHANGE));
  }

  @Test
  public void heldFingerDoesNotRetargetButFreshTouchAndNavigationWork() {
    RecordingFocusGuard guard = new RecordingFocusGuard();
    guard.start(7);
    assertTrue(guard.blocks(7, FocusActionInfo.TOUCH_EXPLORATION));
    assertFalse(guard.blocks(7, FocusActionInfo.LOGICAL_NAVIGATION));
    assertFalse(guard.blocks(7, FocusActionInfo.MANUAL_SCROLL));
    guard.onTouchStart();
    assertFalse(guard.blocks(7, FocusActionInfo.TOUCH_EXPLORATION));
    assertTrue(guard.blocks(7, FocusActionInfo.ENSURE_ON_SCREEN));
    guard.stop();
    assertFalse(guard.blocks(7, FocusActionInfo.ENSURE_ON_SCREEN));
  }

  @Test
  public void newRecordingResetsTouchPermission() {
    RecordingFocusGuard guard = new RecordingFocusGuard();
    guard.start(7);
    guard.onTouchStart();
    guard.stop();
    guard.start(7);
    assertTrue(guard.blocks(7, FocusActionInfo.TOUCH_EXPLORATION));
  }

  @Test
  public void automaticFocusCannotForceSpeechOverMicrophone() {
    assertFalse(FocusActionInfo.builder().setSourceAction(FocusActionInfo.ENSURE_ON_SCREEN)
        .build().forceFeedbackEvenIfMicrophoneActive());
    assertFalse(FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE)
        .build().forceFeedbackEvenIfMicrophoneActive());
    assertTrue(FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
        .build().forceFeedbackEvenIfMicrophoneActive());
  }
}
