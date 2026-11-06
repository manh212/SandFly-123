package com.google.android.accessibility.talkback.selector;

import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import java.util.List;

public final class SpeechStreamSelection {
  private static final List<String> CHANNELS = List.of(
      FailoverTextToSpeech.STREAM_VALUE_ACCESSIBILITY,
      FailoverTextToSpeech.STREAM_VALUE_MEDIA,
      FailoverTextToSpeech.STREAM_VALUE_RING);

  private SpeechStreamSelection() {}

  public static String next(String current, boolean forward) {
    int index = CHANNELS.indexOf(current);
    if (index < 0) return CHANNELS.get(0);
    return CHANNELS.get(Math.floorMod(index + (forward ? 1 : -1), CHANNELS.size()));
  }
}
