package com.google.android.accessibility.talkback.screenrecognition;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;

final class RecognitionClick {

  private RecognitionClick() {}

  static GestureDescription gesture(Rect bounds, int tapDuration, boolean touchExploration) {
    Path path = new Path();
    path.moveTo(bounds.centerX(), bounds.centerY());
    GestureDescription.Builder builder = new GestureDescription.Builder()
        .addStroke(new GestureDescription.StrokeDescription(path, 0, tapDuration));
    if (touchExploration) {
      builder.addStroke(new GestureDescription.StrokeDescription(path, tapDuration + 40L, tapDuration));
    }
    return builder.build();
  }
}
