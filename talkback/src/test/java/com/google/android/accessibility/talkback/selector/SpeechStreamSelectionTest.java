package com.google.android.accessibility.talkback.selector;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SpeechStreamSelectionTest {
  @Test
  public void cyclesThroughExactlyTheThreeRequestedAudioChannels() {
    assertEquals("media", SpeechStreamSelection.next("accessibility", true));
    assertEquals("ring", SpeechStreamSelection.next("media", true));
    assertEquals("accessibility", SpeechStreamSelection.next("ring", true));
    assertEquals("ring", SpeechStreamSelection.next("accessibility", false));
    assertEquals("accessibility", SpeechStreamSelection.next("call", true));
  }
}
