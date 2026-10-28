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
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.android.libraries.accessibility.utils.screencapture.ScreenCaptureController.CaptureListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScreenRecognitionController {

  public interface Announcer {
    void announce(String text);
  }

  private static final String TAG = "ScreenRecognition";
  private static final long OVERLAY_REMOVAL_DELAY_MS = 150L;
  private static final long REFRESH_AFTER_ACTIVATION_DELAY_MS = 700L;
  private static final long CONTROL_TRAVERSAL_BUDGET_MS = 100L;
  private static final int MAX_CONTROL_NODES = 2000;

  private final TalkBackService service;
  private final Announcer announcer;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService nodeTraversalExecutor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "screen-recognition-nodes");
            thread.setDaemon(true);
            return thread;
          });
  private ScreenRecognitionOverlay overlay;
  private volatile boolean active;
  private boolean running;
  private Runnable pendingRefresh;
  private Runnable pendingActivation;
  private volatile int generation;
  private int captureWidth;
  private int captureHeight;
  private int captureRotation;
  private List<Rect> controls = new ArrayList<>();
  private List<RecognizedItem> currentItems = new ArrayList<>();

  public ScreenRecognitionController(TalkBackService service, Announcer announcer) {
    this.service = service;
    this.announcer = announcer;
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
    running = true;
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
    running = false;
    if (!sameDisplaySize()) {
      stop();
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
    currentItems = items;
    if (show(items)) {
      announcer.announce(service.getString(R.string.screen_recognition_done, items.size()));
    }
  }

  private void finishFailure() {
    running = false;
    active = false;
    announcer.announce(service.getString(R.string.screen_recognition_failed));
  }

  private boolean sameDisplaySize() {
    Point size = new Point();
    service.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(size);
    return size.x == captureWidth && size.y == captureHeight
        && captureRotation == service.getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
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
        if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
          continue;
        }
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null) {
          try {
            collectControls(root, result, 0, visited, deadline, request);
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
    params.gravity = Gravity.TOP | Gravity.LEFT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      params.setFitInsetsTypes(0);
      params.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    }
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
      return false;
    }
    overlay.setItems(items);
    return true;
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
    running = false;
    generation++;
    cancelPendingRefresh();
    if (pendingActivation != null) {
      mainHandler.removeCallbacks(pendingActivation);
      pendingActivation = null;
    }
    hide();
    currentItems = new ArrayList<>();
    if (wasActive && announce) {
      announcer.announce(service.getString(R.string.screen_recognition_off));
    }
    return wasActive;
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
  }

  private void activate(RecognizedItem item) {
    if (!active || running || pendingActivation != null || pendingRefresh != null) {
      return;
    }
    if (!sameDisplaySize()) {
      stop();
      announcer.announce(service.getString(R.string.screen_recognition_failed));
      return;
    }
    hide();
    int request = generation;
    pendingActivation =
        () -> {
          pendingActivation = null;
          if (!active || request != generation) {
            return;
          }
          if (!sameDisplaySize()) {
            stop();
            announcer.announce(service.getString(R.string.screen_recognition_failed));
            return;
          }
          nodeTraversalExecutor.execute(
              () -> {
                boolean clicked;
                try {
                  clicked = clickNative(item.bounds, request);
                } catch (RuntimeException e) {
                  LogUtils.w(TAG, "Native click traversal failed: " + e.getMessage());
                  clicked = false;
                }
                boolean result = clicked;
                mainHandler.post(
                    () -> {
                      if (!active || request != generation) {
                        return;
                      }
                      if (result) {
                        scheduleRefresh(request);
                      } else {
                        dispatchRecognizedClick(item.bounds, request);
                      }
                    });
              });
        };
    mainHandler.postDelayed(pendingActivation, OVERLAY_REMOVAL_DELAY_MS);
  }

  private void scheduleRefresh(int request) {
    pendingRefresh =
        () -> {
          pendingRefresh = null;
          if (active && request == generation) {
            startRecognition();
          }
        };
    mainHandler.postDelayed(pendingRefresh, REFRESH_AFTER_ACTIVATION_DELAY_MS);
  }

  private void restoreAfterFailedClick(int request) {
    if (active && request == generation) {
      show(currentItems);
      announcer.announce("Không thể nhấp vào mục này. Hãy thử lại.");
    }
  }

  private void dispatchRecognizedClick(Rect bounds, int request) {
    if (!active || request != generation) {
      return;
    }
    AccessibilityManager manager = service.getSystemService(AccessibilityManager.class);
    GestureDescription gesture =
        RecognitionClick.gesture(
            bounds, ViewConfiguration.getTapTimeout(), manager.isTouchExplorationEnabled());
    boolean accepted =
        service.dispatchGesture(
            gesture,
            new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
              @Override
              public void onCompleted(GestureDescription completed) {
                if (active && request == generation) {
                  scheduleRefresh(request);
                }
              }

              @Override
              public void onCancelled(GestureDescription cancelled) {
                restoreAfterFailedClick(request);
              }
            },
            mainHandler);
    if (!accepted) {
      restoreAfterFailedClick(request);
    }
  }

  private boolean clickNative(Rect target, int request) {
    long deadline = SystemClock.uptimeMillis() + CONTROL_TRAVERSAL_BUDGET_MS;
    int[] visited = {0};
    List<AccessibilityWindowInfo> serviceWindows = service.getWindows();
    if (serviceWindows == null) {
      return false;
    }
    List<AccessibilityWindowInfo> windows = new ArrayList<>(serviceWindows);
    try {
      windows.sort((first, second) -> Integer.compare(second.getLayer(), first.getLayer()));
      for (AccessibilityWindowInfo window : windows) {
        if (!isCurrent(request)
            || SystemClock.uptimeMillis() >= deadline
            || visited[0] >= MAX_CONTROL_NODES) {
          return false;
        }
        if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
          continue;
        }
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        if (!bounds.contains(target.centerX(), target.centerY())) {
          continue;
        }
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) {
          continue;
        }
        try {
          AccessibilityNodeInfo node =
              findClickTarget(root, target, 0, deadline, visited, request);
          if (node == null) {
            continue;
          }
          try {
            return isCurrent(request) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
          } finally {
            node.recycle();
          }
        } catch (RuntimeException e) {
          LogUtils.w(TAG, "Native click failed: " + e.getMessage());
          return false;
        } finally {
          root.recycle();
        }
      }
    } finally {
      for (AccessibilityWindowInfo window : windows) {
        window.recycle();
      }
    }
    return false;
  }

  private boolean isCurrent(int request) {
    return active && request == generation;
  }

  @Nullable
  private AccessibilityNodeInfo findClickTarget(
      AccessibilityNodeInfo node,
      Rect target,
      int depth,
      long deadline,
      int[] visited,
      int request) {
    if (!isCurrent(request)
        || depth > 50
        || ++visited[0] > MAX_CONTROL_NODES
        || SystemClock.uptimeMillis() >= deadline
        || !node.isVisibleToUser()
        || !node.isEnabled()) {
      return null;
    }
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (!bounds.contains(target.centerX(), target.centerY())) {
      return null;
    }
    int childCount = node.getChildCount();
    for (int i = 0; i < childCount; i++) {
      if (!isCurrent(request)
          || SystemClock.uptimeMillis() >= deadline
          || visited[0] >= MAX_CONTROL_NODES) {
        return null;
      }
      AccessibilityNodeInfo child = node.getChild(i);
      if (child != null) {
        AccessibilityNodeInfo result;
        try {
          result = findClickTarget(child, target, depth + 1, deadline, visited, request);
        } finally {
          child.recycle();
        }
        if (result != null) {
          return result;
        }
      }
    }
    return isCurrent(request) && node.isClickable() ? AccessibilityNodeInfo.obtain(node) : null;
  }
}
