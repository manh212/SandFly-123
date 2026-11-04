package com.google.android.accessibility.talkback.monitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;
import org.junit.Test;

public class VolumeMonitorCallTest {
  @Test
  public void suppressesSystemMediaChangesAroundCallTransitions() {
    assertTrue(VolumeMonitor.shouldSuppressCallMediaChange(
        AudioManager.STREAM_MUSIC, 1000, 3500, 0));
    assertTrue(VolumeMonitor.shouldSuppressCallMediaChange(
        AudioManager.STREAM_MUSIC, 3000, 3500, 0));
  }

  @Test
  public void preservesExplicitVolumeKeysAndChangesOutsideCallTransitions() {
    assertFalse(VolumeMonitor.shouldSuppressCallMediaChange(
        AudioManager.STREAM_MUSIC, 1000, 3500, 2000));
    assertFalse(VolumeMonitor.shouldSuppressCallMediaChange(
        AudioManager.STREAM_MUSIC, 4000, 3500, 0));
    assertFalse(VolumeMonitor.shouldSuppressCallMediaChange(
        AudioManager.STREAM_VOICE_CALL, 1000, 3500, 0));
  }
}
