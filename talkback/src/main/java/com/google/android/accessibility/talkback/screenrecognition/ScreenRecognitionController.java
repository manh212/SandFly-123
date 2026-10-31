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

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr;
import com.google.android.accessibility.talkback.screenelements.ScreenElement;
import com.google.android.accessibility.talkback.screenelements.ScreenElements;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.android.libraries.accessibility.utils.screencapture.ScreenCaptureController.CaptureListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScreenRecognitionController {

  public interface Announcer {
    void announce(String text);
  }

  private static final String TAG = "ScreenRecognition";
  private static final long REFRESH_AFTER_ACTIVATION_DELAY_MS = 700L;
  private static final long CONTROL_TRAVERSAL_BUDGET_MS = 100L;
  private static final int MAX_CONTROL_NODES = 2000;

  private final TalkBackService service;
  @Nullable private ScreenRecognitionAi screenRecognitionAi;
  private final Announcer announcer;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService nodeTraversalExecutor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "screen-recognition-nodes");
            thread.setDaemon(true);
            return thread;
          });
  private volatile boolean active;
  private Runnable pendingRefresh;
  private volatile int generation;
  private int captureWindowId = -1;
  private String capturePackageName;
  private boolean showingElements;
  private int captureWidth;
  private int captureHeight;
  private int captureRotation;
  private List<Rect> controls = new ArrayList<>();

  public ScreenRecognitionController(TalkBackService service, Announcer announcer) {
    this.service = service;
    this.announcer = announcer;
  }

  private ScreenRecognitionAi screenRecognitionAi() {
    if (screenRecognitionAi == null) {
      screenRecognitionAi = new ScreenRecognitionAi(service);
    }
    return screenRecognitionAi;
  }

  public void toggle() {
    if (active) {
      stop();
      return;
    }
    active = true;
    startRecognition();
  }

  private void cancelPendingRefresh() {
    if (pendingRefresh != null) {
      mainHandler.removeCallbacks(pendingRefresh);
      pendingRefresh = null;
    }
  }

  private void startRecognition() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      finishFailure();
      return;
    }
    hide();
    if (!captureHostWindow()) {
      finishFailure();
      return;
    }
    int request = ++generation;
    captureRotation = service.getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
    announcer.announce(service.getString(R.string.screen_recognition_starting));
    ScreenshotCapture.takeScreenshot(
        service,
        new CaptureListener() {
          @Override
          public void onScreenCaptureFinished(
              @Nullable Bitmap bitmap, boolean isFormatSupported) {
            if (!active || request != generation) {
              if (bitmap != null) {
                bitmap.recycle();
              }
              return;
            }
            if (bitmap == null || !isFormatSupported) {
              if (bitmap != null) {
                bitmap.recycle();
              }
              finishFailure();
              return;
            }
            captureWidth = bitmap.getWidth();
            captureHeight = bitmap.getHeight();
            nodeTraversalExecutor.execute(
                () -> {
                  List<Rect> foundControls;
                  try {
                    foundControls = collectControls(request);
                  } catch (RuntimeException e) {
                    LogUtils.w(TAG, "Accessibility tree traversal failed: " + e.getMessage());
                    foundControls = new ArrayList<>();
                  }
                  List<Rect> result = foundControls;
                  mainHandler.post(
                      () -> {
                        if (!active || request != generation) {
                          bitmap.recycle();
                          return;
                        }
                        controls = result;
                        recognize(bitmap, request);
                      });
                });
          }
        });
  }

  private void recognize(Bitmap bitmap, int request) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    if ("ai".equals(prefs.getString("pref_screen_recognition_method", "ocr"))) {
      nodeTraversalExecutor.execute(
          () -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes);
            bitmap.recycle();
            mainHandler.post(
                () -> {
                  if (!active || request != generation) {
                    return;
                  }
                  if (!compressed) {
                    finishFailure();
                    return;
                  }
                  screenRecognitionAi().recognize(
                      bytes.toByteArray(),
                      captureWidth,
                      captureHeight,
                      items -> {
                        if (!active || request != generation) {
                          return;
                        }
                        if (items == null) {
                          finishFailure();
                        } else {
                          finishRecognition(items);
                        }
                      });
                });
          });
      return;
    }
    if (useTesseract(service)) {
      recognizeWithTesseract(bitmap, true, request);
    } else {
      recognizeWithMlKit(bitmap, true, request);
    }
  }

  private void recognizeWithMlKit(Bitmap bitmap, boolean allowFallback, int request) {
    TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    recognizer
        .process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener(
            text -> {
              if (active && request == generation) {
                finishRecognition(merge(text));
              }
              bitmap.recycle();
            })
        .addOnFailureListener(
            e -> {
              if (!active || request != generation) {
                bitmap.recycle();
                return;
              }
              LogUtils.w(TAG, "ML Kit recognition failed: " + e.getMessage());
              if (allowFallback && TesseractOcr.isDataDownloaded(service)) {
                recognizeWithTesseract(bitmap, false, request);
              } else {
                bitmap.recycle();
                finishFailure();
              }
            })
        .addOnCompleteListener(task -> recognizer.close());
  }

  private void recognizeWithTesseract(Bitmap bitmap, boolean allowFallback, int request) {
    new Thread(
            () -> {
              List<TesseractOcr.TextLine> segments = TesseractOcr.recognizeSegments(service, bitmap);
              mainHandler.post(
                  () -> {
                    if (!active || request != generation) {
                      bitmap.recycle();
                      return;
                    }
                    if (segments == null && allowFallback) {
                      recognizeWithMlKit(bitmap, false, request);
                      return;
                    }
                    bitmap.recycle();
                    if (segments == null) {
                      finishFailure();
                    } else {
                      finishRecognition(toItems(segments));
                    }
                  });
            })
        .start();
  }

  private static boolean useTesseract(TalkBackService service) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    String engine =
        prefs.getString(
            service.getString(R.string.pref_ocr_engine_key),
            service.getString(R.string.ocr_engine_mlkit_value));
    return service.getString(R.string.ocr_engine_tesseract_value).equals(engine)
        && TesseractOcr.isDataDownloaded(service);
  }

  private List<RecognizedItem> toItems(List<TesseractOcr.TextLine> segments) {
    List<RecognizedItem> items = new ArrayList<>();
    for (TesseractOcr.TextLine segment : segments) {
      List<RecognizedItem> words = new ArrayList<>();
      for (TesseractOcr.TextLine word : segment.words) {
        words.add(new RecognizedItem(word.text, word.bounds, false));
      }
      items.addAll(RecognitionLayout.splitLine(segment.text, segment.bounds, words, controls));
    }
    RecognitionLayout.sort(items);
    return items;
  }

  private List<RecognizedItem> merge(Text text) {
    List<RecognizedItem> items = new ArrayList<>();
    for (Text.TextBlock block : text.getTextBlocks()) {
      for (Text.Line line : block.getLines()) {
        Rect box = line.getBoundingBox();
        if (box == null) {
          continue;
        }
        List<RecognizedItem> words = new ArrayList<>();
        for (Text.Element element : line.getElements()) {
          Rect wordBox = element.getBoundingBox();
          if (wordBox != null) {
            words.add(new RecognizedItem(element.getText(), wordBox, false));
          }
        }
        items.addAll(RecognitionLayout.splitLine(line.getText(), box, words, controls));
      }
    }
    RecognitionLayout.sort(items);
    return items;
  }

  private void finishRecognition(List<RecognizedItem> items) {
    if (!sameDisplaySize() || !isCaptureHostCurrent()) {
      stopSilently();
      announcer.announce(service.getString(R.string.screen_recognition_failed));
      return;
    }
    Rect screen = new Rect(0, 0, captureWidth, captureHeight);
    items.removeIf(item -> !item.bounds.intersect(screen));
    if (items.isEmpty()) {
      active = false;
      announcer.announce(service.getString(R.string.screen_recognition_empty));
      return;
    }
    if (show(items)) {
      announcer.announce(service.getString(R.string.screen_recognition_done, items.size()));
    }
  }

  private void finishFailure() {
    active = false;
    announcer.announce(service.getString(R.string.screen_recognition_failed));
  }

  private boolean sameDisplaySize() {
    Point size = new Point();
    service.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(size);
    return size.x == captureWidth && size.y == captureHeight
        && captureRotation == service.getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
  }

  private boolean captureHostWindow() {
    List<AccessibilityWindowInfo> windows;
    try {
      windows = service.getWindows();
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Could not find the app window: " + e.getMessage());
      return false;
    }
    if (windows == null) return false;
    try {
      AccessibilityWindowInfo host = null;
      for (AccessibilityWindowInfo window : windows) {
        if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
        if (window.isActive()) {
          host = window;
          break;
        }
        if (host == null || window.getLayer() > host.getLayer()) host = window;
      }
      if (host == null) return false;
      AccessibilityNodeInfo root = host.getRoot();
      if (root == null) return false;
      try {
        CharSequence packageName = root.getPackageName();
        if (packageName == null || packageName.length() == 0) return false;
        captureWindowId = host.getId();
        capturePackageName = packageName.toString();
        return true;
      } finally {
        root.recycle();
      }
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Could not read the app window: " + e.getMessage());
      return false;
    } finally {
      for (AccessibilityWindowInfo window : windows) {
        window.recycle();
      }
    }
  }

  private boolean isCaptureHostCurrent() {
    List<AccessibilityWindowInfo> windows;
    try {
      windows = service.getWindows();
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Could not verify the app window: " + e.getMessage());
      return false;
    }
    if (windows == null) return false;
    try {
      AccessibilityWindowInfo host = null;
      for (AccessibilityWindowInfo window : windows) {
        if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
        if (window.isActive()) {
          host = window;
          break;
        }
        if (host == null || window.getLayer() > host.getLayer()) host = window;
      }
      if (host == null || host.getId() != captureWindowId) return false;
      AccessibilityNodeInfo root = host.getRoot();
      if (root == null) return false;
      try {
        CharSequence packageName = root.getPackageName();
        return packageName != null && capturePackageName.contentEquals(packageName);
      } finally {
        root.recycle();
      }
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Could not verify the app window: " + e.getMessage());
      return false;
    } finally {
      for (AccessibilityWindowInfo window : windows) {
        window.recycle();
      }
    }
  }

  private List<Rect> collectControls(int request) {
    List<Rect> result = new ArrayList<>();
    int[] visited = {0};
    long deadline = SystemClock.uptimeMillis() + CONTROL_TRAVERSAL_BUDGET_MS;
    List<AccessibilityWindowInfo> windows = service.getWindows();
    if (windows == null) {
      return result;
    }
    try {
      for (AccessibilityWindowInfo window : windows) {
        if (!isCurrent(request)
            || SystemClock.uptimeMillis() >= deadline
            || visited[0] >= MAX_CONTROL_NODES
            || result.size() >= 500) {
          break;
        }
        if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
          continue;
        }
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null) {
          try {
            CharSequence packageName = root.getPackageName();
            if (packageName != null && capturePackageName.contentEquals(packageName)) {
              collectControls(root, result, 0, visited, deadline, request);
            }
          } finally {
            root.recycle();
          }
        }
      }
    } finally {
      for (AccessibilityWindowInfo window : windows) {
        window.recycle();
      }
    }
    return result;
  }

  private boolean collectControls(
      AccessibilityNodeInfo node,
      List<Rect> result,
      int depth,
      int[] visited,
      long deadline,
      int request) {
    if (!isCurrent(request)
        || depth > 50
        || ++visited[0] > MAX_CONTROL_NODES
        || result.size() >= 500
        || SystemClock.uptimeMillis() >= deadline) {
      return false;
    }
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (node.isVisibleToUser() && node.isClickable() && !bounds.isEmpty()) {
      result.add(bounds);
    }
    int childCount = node.getChildCount();
    for (int i = 0; i < childCount; i++) {
      if (!isCurrent(request)
          || SystemClock.uptimeMillis() >= deadline
          || visited[0] >= MAX_CONTROL_NODES
          || result.size() >= 500) {
        return false;
      }
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null) {
        boolean complete;
        try {
          complete = collectControls(child, result, depth + 1, visited, deadline, request);
        } finally {
          child.recycle();
        }
        if (!complete) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean show(List<RecognizedItem> items) {
    List<ScreenElement> elements = ScreenRecognitionElements.from(items);
    int request = generation;
    try {
      ScreenElements.show(
          captureWindowId,
          capturePackageName,
          elements,
          Collections.emptyMap(),
          false,
          () -> {
            if (active && request == generation) scheduleRefresh(request);
          });
    } catch (RuntimeException e) {
      LogUtils.w(TAG, "Screen elements could not be shown: " + e.getMessage());
      active = false;
      announcer.announce(service.getString(R.string.screen_recognition_failed));
      return false;
    }
    showingElements = ScreenElements.isShowing();
    if (!showingElements) {
      active = false;
      announcer.announce(service.getString(R.string.screen_recognition_failed));
    }
    return showingElements;
  }

  public boolean stop() {
    return stop(true);
  }

  public boolean stopSilently() {
    return stop(false);
  }

  private boolean stop(boolean announce) {
    boolean wasActive = active;
    active = false;
    generation++;
    if (screenRecognitionAi != null) {
      screenRecognitionAi.cancel();
    }
    cancelPendingRefresh();
    hide();
    if (wasActive && announce) {
      announcer.announce(service.getString(R.string.screen_recognition_off));
    }
    return wasActive;
  }

  private void hide() {
    if (showingElements) {
      ScreenElements.clear("screen recognition closed");
      showingElements = false;
    }
  }

  private void scheduleRefresh(int request) {
    pendingRefresh =
        () -> {
          pendingRefresh = null;
          if (active && request == generation) {
            SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
            if ("ai".equals(prefs.getString("pref_screen_recognition_method", "ocr"))) {
              stopSilently();
            } else {
              startRecognition();
            }
          }
        };
    mainHandler.postDelayed(pendingRefresh, REFRESH_AFTER_ACTIVATION_DELAY_MS);
  }

  private boolean isCurrent(int request) {
    return active && request == generation;
  }
}
