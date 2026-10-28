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

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.ArrayList;
import java.util.List;

public class ScreenRecognitionOverlay extends View {

  public interface OnItemActivatedListener {
    void onItemActivated(RecognizedItem item);
  }

  public interface OnItemTouchedListener {
    void onItemTouched(RecognizedItem item);
  }

  private static final long DOUBLE_TAP_TIMEOUT_MS = 400;

  private final RecognitionHelper helper;
  private final OnItemTouchedListener touchListener;
  private final OnItemActivatedListener activationListener;
  private List<RecognizedItem> items = new ArrayList<>();
  private RecognizedItem touchedItem;
  private RecognizedItem lastUpItem;
  private long lastUpTimeMs;

  public ScreenRecognitionOverlay(
      Context context,
      OnItemActivatedListener activationListener,
      OnItemTouchedListener touchListener) {
    super(context);
    this.activationListener = activationListener;
    this.touchListener = touchListener;
    setFocusable(true);
    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    helper = new RecognitionHelper(this, activationListener);
    ViewCompat.setAccessibilityDelegate(this, helper);
  }

  public void setItems(List<RecognizedItem> items) {
    this.items = new ArrayList<>(items);
    touchedItem = null;
    lastUpItem = null;
    helper.invalidateRoot();
  }

  @Override
  public boolean dispatchHoverEvent(MotionEvent event) {
    return helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
      RecognizedItem item = findItemAt(event.getX(), event.getY());
      if (item != null && item != touchedItem) {
        touchedItem = item;
        touchListener.onItemTouched(item);
      } else if (item == null) {
        touchedItem = null;
      }
      return true;
    }
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      RecognizedItem item = findItemAt(event.getX(), event.getY());
      long now = SystemClock.uptimeMillis();
      if (action == MotionEvent.ACTION_UP
          && item != null
          && item == lastUpItem
          && now - lastUpTimeMs < DOUBLE_TAP_TIMEOUT_MS) {
        lastUpItem = null;
        activationListener.onItemActivated(item);
      } else if (action == MotionEvent.ACTION_UP) {
        lastUpItem = item;
        lastUpTimeMs = now;
      }
      if (action == MotionEvent.ACTION_CANCEL) {
        lastUpItem = null;
        lastUpTimeMs = 0;
      }
      touchedItem = null;
      return true;
    }
    return super.onTouchEvent(event);
  }

  private RecognizedItem findItemAt(float x, float y) {
    int[] location = new int[2];
    getLocationOnScreen(location);
    int index = RecognitionLayout.itemAt(items, (int) x + location[0], (int) y + location[1]);
    return index < 0 ? null : items.get(index);
  }

  private class RecognitionHelper extends ExploreByTouchHelper {

    private final OnItemActivatedListener listener;

    RecognitionHelper(View host, OnItemActivatedListener listener) {
      super(host);
      this.listener = listener;
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
      RecognizedItem item = findItemAt(x, y);
      return item == null ? INVALID_ID : items.indexOf(item);
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
      for (int i = 0; i < items.size(); i++) {
        virtualViewIds.add(i);
      }
    }

    @Override
    protected void onPopulateNodeForVirtualView(
        int virtualViewId, AccessibilityNodeInfoCompat node) {
      if (virtualViewId < 0 || virtualViewId >= items.size()) {
        node.setText(" ");
        node.setBoundsInParent(new Rect());
        node.setBoundsInScreen(new Rect());
        return;
      }
      RecognizedItem item = items.get(virtualViewId);
      int[] location = new int[2];
      getLocationOnScreen(location);
      Rect localBounds = new Rect(item.bounds);
      localBounds.offset(-location[0], -location[1]);
      node.setText(item.text);
      node.setBoundsInParent(localBounds);
      node.setBoundsInScreen(item.bounds);
      node.setFocusable(true);
      node.setScreenReaderFocusable(true);
      node.setClickable(true);
      node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
      if (virtualViewId > 0) {
        node.setTraversalAfter(ScreenRecognitionOverlay.this, virtualViewId - 1);
      }
    }

    @Override
    protected boolean onPerformActionForVirtualView(
        int virtualViewId, int action, Bundle arguments) {
      if (action == AccessibilityNodeInfo.ACTION_CLICK
          && virtualViewId >= 0
          && virtualViewId < items.size()) {
        listener.onItemActivated(items.get(virtualViewId));
        return true;
      }
      return false;
    }
  }
}
