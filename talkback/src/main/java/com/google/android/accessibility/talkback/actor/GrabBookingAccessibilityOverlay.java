/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.actor;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.List;

/**
 * Makes Grab's inaccessible transportBookButton reachable as a normal accessibility button.
 *
 * <p>This overlay is not part of Grab's window and does not alter Grab's view hierarchy. It is
 * only displayed while the visible Grab booking panel has its exact enabled booking action.
 * Merely focusing this button never books a ride. A user must explicitly activate it.
 */
public final class GrabBookingAccessibilityOverlay {
  private static final String GRAB_PACKAGE = "com.grabtaxi.passenger";
  private static final long UPDATE_DELAY_MS = 220;

  private final AccessibilityService service;
  private final WindowManager windowManager;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable update = this::refreshNow;

  private @Nullable Button button;
  private @Nullable WindowManager.LayoutParams params;
  private boolean closed;

  public GrabBookingAccessibilityOverlay(AccessibilityService service) {
    this.service = service;
    windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
  }

  /** Reevaluate after the application has processed a window, scroll, or content change. */
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (closed || event == null) {
      return;
    }
    final int type = event.getEventType();
    if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
      return;
    }

    // Our own accessibility overlay may emit window/content changes. Never trigger a loop.
    CharSequence sender = event.getPackageName();
    if (sender != null && sender.toString().equals(service.getPackageName())) {
      return;
    }

    handler.removeCallbacks(update);
    handler.postDelayed(update, UPDATE_DELAY_MS);
  }

  public void refresh() {
    if (closed) {
      return;
    }
    handler.removeCallbacks(update);
    handler.post(update);
  }

  private void refreshNow() {
    if (closed || windowManager == null) {
      return;
    }
    AccessibilityNodeInfoCompat target = currentBookingButton();
    if (target == null) {
      hide();
      return;
    }
    Rect bounds = new Rect();
    target.getBoundsInScreen(bounds);
    if (bounds.isEmpty()) {
      hide();
      return;
    }
    showNear(bounds);
  }

  /**
   * Look in the top app window, not in the active accessibility-overlay window. The latter can
   * become the active window when a user focuses the companion button.
   */
  private @Nullable AccessibilityNodeInfoCompat currentBookingButton() {
    List<AccessibilityWindowInfo> windows = service.getWindows();
    if (windows != null && !windows.isEmpty()) {
      for (AccessibilityWindowInfo window : windows) {
        int type = window.getType();
        if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
          continue;
        }
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
          return null; // Don't cover the keyboard or the editable booking form.
        }
        if (type != AccessibilityWindowInfo.TYPE_APPLICATION) {
          // System dialog, notification shade or another modal window is above Grab.
          if (window.isActive()) {
            return null;
          }
          continue;
        }
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null || root.getPackageName() == null
            || !GRAB_PACKAGE.contentEquals(root.getPackageName())) {
          return null;
        }
        return GrabBookingClickTarget.findInWindow(AccessibilityNodeInfoCompat.wrap(root));
      }
      return null;
    }
    // Some devices do not expose interactive windows. Use the active root only as a fallback.
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    return root == null
        ? null
        : GrabBookingClickTarget.findInWindow(AccessibilityNodeInfoCompat.wrap(root));
  }

  private void showNear(Rect targetBounds) {
    int width = dp(120);
    int height = dp(52);
    int margin = dp(10);
    Point size = new Point();
    windowManager.getDefaultDisplay().getSize(size);

    int left;
    int top;
    if (targetBounds.right + width + margin <= size.x) {
      left = targetBounds.right + margin;
      top = targetBounds.centerY() - height / 2;
    } else if (targetBounds.left - width - margin >= 0) {
      left = targetBounds.left - width - margin;
      top = targetBounds.centerY() - height / 2;
    } else if (targetBounds.top >= height + margin) {
      left = targetBounds.left;
      top = targetBounds.top - height - margin;
    } else {
      left = targetBounds.left;
      top = targetBounds.bottom + margin;
    }
    left = Math.max(0, Math.min(left, Math.max(0, size.x - width)));
    top = Math.max(0, Math.min(top, Math.max(0, size.y - height)));

    if (button == null) {
      Button newButton = new Button(service);
      newButton.setText("Đặt xe");
      newButton.setContentDescription("Đặt xe, nút hỗ trợ trợ năng");
      newButton.setFocusable(true);
      newButton.setClickable(true);
      newButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      newButton.setOnClickListener(view -> activateBooking());
      WindowManager.LayoutParams layout =
          new WindowManager.LayoutParams(
              width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                  | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
              PixelFormat.TRANSLUCENT);
      layout.gravity = Gravity.TOP | Gravity.LEFT;
      layout.x = left;
      layout.y = top;
      layout.setTitle("Meridian: nút Đặt xe hỗ trợ Grab");
      try {
        windowManager.addView(newButton, layout);
        button = newButton;
        params = layout;
      } catch (RuntimeException exception) {
        // The service/window may disappear during an app transition; retry on next app event.
        button = null;
        params = null;
      }
    } else if (params != null && (params.x != left || params.y != top)) {
      params.x = left;
      params.y = top;
      try {
        windowManager.updateViewLayout(button, params);
      } catch (RuntimeException exception) {
        hide();
      }
    }
  }

  private void activateBooking() {
    // Look up the target again: fare, route, or booking state can change while overlay is visible.
    AccessibilityNodeInfoCompat target = currentBookingButton();
    if (target == null || !target.isEnabled() || !target.isVisibleToUser()
        || !target.isClickable()) {
      hide();
      return;
    }
    // Only this explicit user click activates booking. No automatic click on focus or page change.
    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    handler.removeCallbacks(update);
    handler.postDelayed(update, 450);
  }

  private int dp(int value) {
    return (int) (value * service.getResources().getDisplayMetrics().density + 0.5f);
  }

  private void hide() {
    if (button == null) {
      return;
    }
    try {
      windowManager.removeView(button);
    } catch (RuntimeException ignored) {
      // The window may have been removed by Android already.
    }
    button = null;
    params = null;
  }

  /** Release the accessible overlay when the screen reader stops. */
  public void close() {
    closed = true;
    handler.removeCallbacks(update);
    hide();
  }
}
