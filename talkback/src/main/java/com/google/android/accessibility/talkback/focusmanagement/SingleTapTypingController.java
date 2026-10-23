/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.focusmanagement;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.google.android.accessibility.utils.FeatureSupport;

/**
 * Keeps the keyboard window in a gesture-detection passthrough region while the {@link
 * FocusProcessorForTapAndTouchExploration#SINGLE_TAP_TO_TYPE} typing method is selected. Touches
 * starting in the keyboard then bypass gesture detection, while touch exploration still focuses
 * and speaks the key under the finger, and the key is clicked when the touch interaction ends.
 */
public class SingleTapTypingController {
  private final AccessibilityService service;
  private boolean enabled;
  private boolean paused;
  private boolean regionSet;

  public SingleTapTypingController(AccessibilityService service) {
    this.service = service;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (enabled) {
      updateRegion();
    } else {
      clearRegion();
    }
  }

  /** Stops sending the region while the service is paused, and restores it on resume. */
  public void setPaused(boolean paused) {
    this.paused = paused;
    if (paused) {
      clearRegion();
    } else {
      updateRegion();
    }
  }

  public void shutdown() {
    clearRegion();
  }

  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (!FeatureSupport.supportPassthrough()) {
      return;
    }
    switch (event.getEventType()) {
      case AccessibilityEvent.TYPE_WINDOWS_CHANGED -> updateRegion();
      case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
        AccessibilityNodeInfo source = event.getSource();
        AccessibilityWindowInfo window = source == null ? null : source.getWindow();
        if (window == null) {
          return;
        }
        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
          setRegion(window);
        } else {
          // Restore gesture detection as soon as a non-keyboard element is touched.
          clearRegion();
        }
      }
      case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
        AccessibilityNodeInfo focus = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        AccessibilityWindowInfo window = focus == null ? null : focus.getWindow();
        if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
          clearRegion();
        }
      }
      default -> {}
    }
  }

  private void updateRegion() {
    if (!enabled || paused || !FeatureSupport.supportPassthrough()) {
      return;
    }
    AccessibilityWindowInfo keyboardWindow = null;
    if (service.getWindows() != null) {
      for (AccessibilityWindowInfo window : service.getWindows()) {
        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
          keyboardWindow = window;
          break;
        }
      }
    }
    if (keyboardWindow == null) {
      clearRegion();
    } else {
      setRegion(keyboardWindow);
    }
  }

  private void setRegion(AccessibilityWindowInfo keyboardWindow) {
    if (!enabled || paused) {
      return;
    }
    Rect bounds = new Rect();
    keyboardWindow.getBoundsInScreen(bounds);
    service.setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, new Region(bounds));
    regionSet = true;
  }

  private void clearRegion() {
    if (!regionSet || !FeatureSupport.supportPassthrough()) {
      return;
    }
    service.setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, new Region());
    regionSet = false;
  }
}
