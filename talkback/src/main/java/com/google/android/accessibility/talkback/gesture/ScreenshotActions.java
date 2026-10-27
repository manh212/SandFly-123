package com.google.android.accessibility.talkback.gesture;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.utils.ScreenshotSaver;
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture;
import com.google.android.libraries.accessibility.utils.screencapture.ScreenCaptureController.CaptureListener;
import com.google.android.libraries.accessibility.utils.bitmap.BitmapUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

public class ScreenshotActions {

  public interface Callback {
    void onSavedAndShared();

    void onFailed();

    void onSystemFallback();
  }

  private ScreenshotActions() {
    throw new AssertionError();
  }

  public static void takeFullScreenshot(
      TalkBackService service, String chooserTitle, Callback callback) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      callback.onSystemFallback();
      return;
    }
    ScreenshotCapture.takeScreenshot(
        service,
        new CaptureListener() {
          @Override
          public void onScreenCaptureFinished(@Nullable Bitmap bitmap, boolean isFormatSupported) {
            if (bitmap == null || !isFormatSupported) {
              callback.onFailed();
              return;
            }
            saveAndShare(service, bitmap, chooserTitle, callback);
          }
        });
  }

  public static void takeFocusScreenshot(
      TalkBackService service,
      AccessibilityNodeInfoCompat node,
      String chooserTitle,
      Callback callback) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || node == null) {
      callback.onSystemFallback();
      return;
    }
    takeFocusScreenshotApi30(service, node, chooserTitle, callback);
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private static void takeFocusScreenshotApi30(
      TalkBackService service,
      AccessibilityNodeInfoCompat node,
      String chooserTitle,
      Callback callback) {
    ScreenshotCapture.takeScreenshotByNode(
        service,
        node,
        new ScreenshotCapture.WindowOrFullScreenScreenshotCallback() {
          @Override
          public void onFailure(int errorCode) {
            super.onFailure(errorCode);
            callback.onFailed();
          }

          @Override
          public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
            super.onSuccess(screenshot);
            Bitmap full = getBitmap();
            if (full == null) {
              callback.onFailed();
              return;
            }
            Bitmap cropped;
            try {
              Rect bounds = new Rect();
              if (isCaptureByWindow()) {
                node.getBoundsInWindow(bounds);
              } else {
                node.getBoundsInScreen(bounds);
              }
              if (bounds.isEmpty()) {
                cropped = full;
              } else {
                bounds.intersect(0, 0, full.getWidth(), full.getHeight());
                cropped = bounds.isEmpty() ? full : BitmapUtils.cropBitmap(full, bounds);
              }
            } catch (RuntimeException e) {
              callback.onFailed();
              return;
            }
            if (cropped == null) {
              callback.onFailed();
              return;
            }
            saveAndShare(service, cropped, chooserTitle, callback);
          }
        });
  }

  private static void saveAndShare(
      TalkBackService service, Bitmap bitmap, String chooserTitle, Callback callback) {
    ScreenshotSaver.saveAndShare(
        service,
        bitmap,
        chooserTitle,
        new ScreenshotSaver.Callback() {
          @Override
          public void onSavedAndShared(android.net.Uri uri) {
            callback.onSavedAndShared();
          }

          @Override
          public void onFailed() {
            callback.onFailed();
          }
        });
  }
}
