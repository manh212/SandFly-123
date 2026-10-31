package com.google.android.accessibility.talkback.labeling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabelNodeIdTest {
  private static AccessibilityNodeInfoCompat button() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName(Button.class.getName());
    node.setPackageName("com.example.app");
    node.setClickable(true);
    node.setEnabled(true);
    return AccessibilityNodeInfoCompat.wrap(node);
  }

  @Test
  public void missingIdButtonHasStableSyntheticResourceName() {
    AccessibilityNodeInfoCompat node = button();
    String id = LabelNodeId.resourceName(node);
    assertTrue(id.startsWith("com.example.app:id/sandfly_unlabeled_"));
    assertEquals(id, LabelNodeId.resourceName(node));
  }

  @Test
  public void viewResourceIdIsPreserved() {
    AccessibilityNodeInfoCompat node = button();
    node.setViewIdResourceName("com.example.app:id/save");
    assertEquals("com.example.app:id/save", LabelNodeId.resourceName(node));
  }

  @Test
  public void nonButtonWithoutIdHasNoSyntheticLabel() {
    AccessibilityNodeInfoCompat node = button();
    node.setClassName(TextView.class.getName());
    node.setClickable(false);
    assertNull(LabelNodeId.resourceName(node));
  }

  @Test
  public void existingTextMakesButtonLabeled() {
    AccessibilityNodeInfoCompat node = button();
    assertFalse(LabelNodeId.hasSpokenLabel(node));
    node.setContentDescription("Save");
    assertTrue(LabelNodeId.hasSpokenLabel(node));
  }
}
