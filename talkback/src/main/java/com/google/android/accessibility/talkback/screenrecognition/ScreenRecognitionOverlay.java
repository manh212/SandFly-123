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
import android.os.Bundle;
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

  private final RecognitionHelper helper;
  private List<RecognizedItem> items = new ArrayList<>();

  public ScreenRecognitionOverlay(Context context, OnItemActivatedListener listener) {
    super(context);
    helper = new RecognitionHelper(this, listener);
    ViewCompat.setAccessibilityDelegate(this, helper);
  }

  public void setItems(List<RecognizedItem> items) {
    this.items = items;
    helper.invalidateRoot();
  }

  @Override
  public boolean dispatchHoverEvent(MotionEvent event) {
    return helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
  }

  private class RecognitionHelper extends ExploreByTouchHelper {

    private final OnItemActivatedListener listener;

    RecognitionHelper(View host, OnItemActivatedListener listener) {
      super(host);
      this.listener = listener;
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
      for (int i = 0; i < items.size(); i++) {
        if (items.get(i).bounds.contains((int) x, (int) y)) {
          return i;
        }
      }
      return INVALID_ID;
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
        return;
      }
      RecognizedItem item = items.get(virtualViewId);
      node.setText(item.text);
      node.setBoundsInScreen(item.bounds);
      node.setClickable(true);
      node.addAction(AccessibilityNodeInfo.ACTION_CLICK);
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
