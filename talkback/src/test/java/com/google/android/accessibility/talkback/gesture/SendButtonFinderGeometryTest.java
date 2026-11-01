package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SendButtonFinderGeometryTest {
  @Test
  public void findsSendBesideEditorAndIgnoresConversationHistory() {
    AccessibilityNodeInfoCompat root = node(null, null, new Rect(0, 0, 1000, 1000));
    AccessibilityNodeInfoCompat field = node(root, null, new Rect(100, 800, 800, 900));
    field.setEditable(true);
    node(root, "Gửi", new Rect(800, 100, 900, 200));
    AccessibilityNodeInfoCompat send = node(root, "Gửi", new Rect(850, 800, 950, 900));
    assertEquals(send, SendButtonFinder.find(field, List.of("Gửi")));
    send.setEnabled(false);
    assertNull(SendButtonFinder.find(field, List.of("Gửi")));
  }

  @Test
  public void rejectsAmbiguousButtonsAndContainersAroundField() {
    AccessibilityNodeInfoCompat root = node(null, "Gửi", new Rect(0, 0, 1000, 1000));
    AccessibilityNodeInfoCompat field = node(root, null, new Rect(200, 800, 800, 900));
    field.setEditable(true);
    assertNull(SendButtonFinder.find(field, List.of("Gửi")));
    node(root, "Gửi", new Rect(100, 800, 150, 900));
    node(root, "Gửi", new Rect(850, 800, 900, 900));
    assertNull(SendButtonFinder.find(field, List.of("Gửi")));
  }

  private static AccessibilityNodeInfoCompat node(
      AccessibilityNodeInfoCompat parent, String label, Rect bounds) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setClassName("android.widget.Button");
    info.setContentDescription(label);
    info.setClickable(true);
    info.setEnabled(true);
    info.setVisibleToUser(true);
    info.setBoundsInScreen(bounds);
    if (parent != null) shadowOf(parent.unwrap()).addChild(info);
    return AccessibilityNodeInfoCompat.wrap(info);
  }
}
