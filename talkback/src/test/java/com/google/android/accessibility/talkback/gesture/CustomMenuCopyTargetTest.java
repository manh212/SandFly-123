package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CustomMenuCopyTargetTest {
  @Test
  public void menuCopyUsesOriginalItemInsteadOfCurrentInputFocus() {
    AccessibilityNodeInfo message = AccessibilityNodeInfo.obtain();
    message.setText("Message to copy");
    AccessibilityNodeInfoCompat originalFocus = AccessibilityNodeInfoCompat.wrap(message);
    AccessibilityNodeInfo editor = AccessibilityNodeInfo.obtain();
    editor.setText("Tin nhắn");
    AccessibilityNodeInfoCompat inputFocus = AccessibilityNodeInfoCompat.wrap(editor);
    AtomicBoolean currentFocusQueried = new AtomicBoolean(false);

    AccessibilityNodeInfoCompat target =
        GestureController.copyTarget(
            true,
            originalFocus,
            () -> {
              currentFocusQueried.set(true);
              return inputFocus;
            });

    assertSame(originalFocus, target);
    assertEquals(List.of("Message to copy"), CopyTextUtils.getFocusedTextItems(target));
    assertFalse(currentFocusQueried.get());
  }

  @Test
  public void menuCopyWithoutOriginalFocusDoesNotCopyAnotherItem() {
    AtomicBoolean currentFocusQueried = new AtomicBoolean(false);

    AccessibilityNodeInfoCompat target =
        GestureController.copyTarget(
            true,
            null,
            () -> {
              currentFocusQueried.set(true);
              return AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain());
            });

    assertNull(target);
    assertFalse(currentFocusQueried.get());
  }

  @Test
  public void directCopyStillUsesCurrentFocus() {
    AccessibilityNodeInfo currentNode = AccessibilityNodeInfo.obtain();
    currentNode.setText("Current item");
    AccessibilityNodeInfoCompat currentFocus = AccessibilityNodeInfoCompat.wrap(currentNode);
    AtomicBoolean currentFocusQueried = new AtomicBoolean(false);

    AccessibilityNodeInfoCompat target =
        GestureController.copyTarget(
            false,
            null,
            () -> {
              currentFocusQueried.set(true);
              return currentFocus;
            });

    assertSame(currentFocus, target);
    assertTrue(currentFocusQueried.get());
  }
}
