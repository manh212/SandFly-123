/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.input.CursorGranularity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GranularityTraversalTest {
  @Test
  public void handlesTextWhenFrameworkDoesNotOfferLineMovement() {
    AccessibilityNodeInfoCompat node =
        textNode(
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH);

    assertTrue(GranularityTraversal.shouldHandleLineGranularityTraversalInTalkback(node, true));
  }

  @Test
  public void offersLineMovementWhenNativeTextOmitsIt() {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setClassName("android.widget.TextView");
    info.setText("first line\nsecond line");
    info.setMovementGranularities(
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
            | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH);

    assertTrue(
        CursorGranularityManager.getSupportedGranularities(
                AccessibilityNodeInfoCompat.wrap(info))
            .contains(CursorGranularity.LINE));
  }

  @Test
  public void leavesTextToFrameworkWhenLineMovementIsAvailable() {
    AccessibilityNodeInfoCompat node =
        textNode(
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);

    assertFalse(GranularityTraversal.shouldHandleLineGranularityTraversalInTalkback(node, true));
  }

  @Test
  public void leavesWebContentToFramework() {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setClassName("android.webkit.WebView");
    info.setContentDescription("first line\nsecond line");
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(info);

    assertFalse(GranularityTraversal.shouldHandleLineGranularityTraversalInTalkback(node, true));
  }

  private static AccessibilityNodeInfoCompat textNode(int movementGranularities) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setClassName("android.widget.TextView");
    info.setContentDescription("first line\nsecond line");
    info.setMovementGranularities(movementGranularities);
    return AccessibilityNodeInfoCompat.wrap(info);
  }
}
