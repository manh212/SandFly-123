package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CopyTextUtilsTest {
  @Test
  public void focusedIconCopiesItsAccessibilityLabel() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.ImageButton");
    node.setContentDescription("Telegram");
    node.setClickable(true);
    AccessibilityNodeInfoCompat focused = AccessibilityNodeInfoCompat.wrap(node);
    assertEquals(List.of("Telegram"), CopyTextUtils.getFocusedTextItems(focused));
    assertTrue(CopyTextUtils.getTextItems(focused).isEmpty());
  }

  @Test
  public void focusedTextTakesPriorityOverItsDescription() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setText("Tin nhắn");
    node.setContentDescription("Mô tả khác");
    assertEquals(
        List.of("Tin nhắn"),
        CopyTextUtils.getFocusedTextItems(AccessibilityNodeInfoCompat.wrap(node)));
  }

  @Test
  public void passwordIsNeverCopied() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.EditText");
    node.setPassword(true);
    node.setText("secret");
    assertTrue(
        CopyTextUtils.getFocusedTextItems(AccessibilityNodeInfoCompat.wrap(node)).isEmpty());
  }
}
