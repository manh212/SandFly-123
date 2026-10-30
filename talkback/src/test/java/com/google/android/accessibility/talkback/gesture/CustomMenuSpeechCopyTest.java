package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;

import com.google.android.accessibility.talkback.Feedback;
import org.junit.Test;

public class CustomMenuSpeechCopyTest {
  @Test
  public void menuUsesSpeechSavedBeforeMenuOpened() {
    assertEquals(
        Feedback.Speech.Action.COPY_SAVED, GestureController.lastUtteranceCopyAction(true));
  }

  @Test
  public void directGestureStillCopiesMostRecentUtterance() {
    assertEquals(
        Feedback.Speech.Action.COPY_LAST, GestureController.lastUtteranceCopyAction(false));
  }
}
