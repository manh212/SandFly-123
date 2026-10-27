/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.screenrecognition;

import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.android.libraries.accessibility.utils.screencapture.ScreenCaptureController.CaptureListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScreenRecognitionController {

  public interface Announcer {
    void announce(String text);
  }

  private static final String TAG = "ScreenRecognition";
  private static final int ROW_TOLERANCE_PX = 40;

  private final TalkBackService service;
  private final Announcer announcer;
  private ScreenRecognitionOverlay overlay;
  private boolean active;
  private boolean running;

  public ScreenRecognitionController(TalkBackService service, Announcer announcer) {
    this.service = service;
    this.announcer = announcer;
  }

  public void toggle() {
    if (active) {
      hide();
      announcer.announce(service.getString(R.string.screen_recognition_off));
      return;
    }
    if (running) {
      return;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      announcer.announce(service.getString(R.string.screen_recognition_failed));
      return;
    }
    running = true;
    announcer.announce(service.getString(R.string.screen_recognition_starting));
    ScreenshotCapture.takeScreenshot(
        service,
        new CaptureListener() {
          @Override
          public void onScreenCaptureFinished(
              @Nullable Bitmap bitmap, boolean isFormatSupported) {
            running = false;
            if (bitmap == null || !isFormatSupported) {
              announcer.announce(service.getString(R.string.screen_recognition_failed));
              return;
            }
            recognize(bitmap);
          }
        });
  }

  private void recognize(Bitmap bitmap) {
    TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    recognizer
        .process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener(
            text -> {
              List<RecognizedItem> items = merge(bitmap, text);
              if (items.isEmpty()) {
                announcer.announce(service.getString(R.string.screen_recognition_empty));
                return;
              }
              show(items);
              announcer.announce(
                  service.getString(R.string.screen_recognition_done, items.size()));
            })
        .addOnFailureListener(
            e -> {
              LogUtils.w(TAG, "Recognition failed: " + e.getMessage());
              announcer.announce(service.getString(R.string.screen_recognition_failed));
            })
        .addOnCompleteListener(task -> recognizer.close());
  }

  private List<RecognizedItem> merge(Bitmap bitmap, Text text) {
    int displayWidth = service.getResources().getDisplayMetrics().widthPixels;
    int displayHeight = service.getResources().getDisplayMetrics().heightPixels;
    float scaleX = (float) displayWidth / bitmap.getWidth();
    float scaleY = (float) displayHeight / bitmap.getHeight();
    List<RecognizedItem> items = new ArrayList<>();
    for (Text.TextBlock block : text.getTextBlocks()) {
      for (Text.Line line : block.getLines()) {
        Rect box = line.getBoundingBox();
        String lineText = line.getText();
        if (box == null || lineText == null || lineText.trim().isEmpty()) {
          continue;
        }
        Rect screenBox =
            new Rect(
                Math.round(box.left * scaleX),
                Math.round(box.top * scaleY),
                Math.round(box.right * scaleX),
                Math.round(box.bottom * scaleY));
        items.add(new RecognizedItem(lineText, screenBox, false));
      }
    }
    Collections.sort(
        items,
        (first, second) -> {
          int firstRow = Math.round(first.bounds.centerY() / (float) ROW_TOLERANCE_PX);
          int secondRow = Math.round(second.bounds.centerY() / (float) ROW_TOLERANCE_PX);
          if (firstRow != secondRow) {
            return firstRow - secondRow;
          }
          return first.bounds.centerX() - second.bounds.centerX();
        });
    return items;
  }

  private void show(List<RecognizedItem> items) {
    hide();
    WindowManager windowManager = service.getSystemService(WindowManager.class);
    overlay =
        new ScreenRecognitionOverlay(
            service, this::activate, item -> announcer.announce(item.text));
    WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    params.setTitle(service.getString(R.string.title_screen_recognition));
    params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    params.flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    params.width = WindowManager.LayoutParams.MATCH_PARENT;
    params.height = WindowManager.LayoutParams.MATCH_PARENT;
    params.format = PixelFormat.TRANSLUCENT;
    try {
      windowManager.addView(overlay, params);
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Overlay could not be shown: " + e.getMessage());
      overlay = null;
      active = false;
      announcer.announce(service.getString(R.string.screen_recognition_failed));
      return;
    }
    overlay.setItems(items);
    active = true;
  }

  private void hide() {
    if (overlay != null) {
      try {
        service.getSystemService(WindowManager.class).removeView(overlay);
      } catch (IllegalArgumentException e) {
        LogUtils.w(TAG, "Overlay already removed: " + e.getMessage());
      }
      overlay = null;
    }
    active = false;
  }

  private void activate(RecognizedItem item) {
    hide();
    Path path = new Path();
    path.moveTo(item.bounds.centerX(), item.bounds.centerY());
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(
                new GestureDescription.StrokeDescription(
                    path, 0, ViewConfiguration.getTapTimeout()))
            .build();
    service.dispatchGesture(gesture, null, null);
  }
}
