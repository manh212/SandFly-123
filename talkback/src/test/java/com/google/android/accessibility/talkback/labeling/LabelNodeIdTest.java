package com.google.android.accessibility.talkback.labeling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
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

  private static AccessibilityNodeInfoCompat zaloImage() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName(ImageView.class.getName());
    node.setPackageName("com.zing.zalo");
    node.setClickable(true);
    node.setEnabled(true);
    return AccessibilityNodeInfoCompat.wrap(node);
  }

  @Test
  public void clickableZaloImageWithoutIdHasStableSyntheticResourceName() {
    AccessibilityNodeInfoCompat node = zaloImage();
    String id = LabelNodeId.resourceName(node);
    assertTrue(id.startsWith("com.zing.zalo:id/sandfly_unlabeled_"));
    assertEquals(id, LabelNodeId.resourceName(node));
    assertFalse(LabelNodeId.hasSpokenLabel(node));
  }

  @Test
  public void imageViewWithResourceIdKeepsOriginalId() {
    AccessibilityNodeInfoCompat node = zaloImage();
    node.setViewIdResourceName("com.zing.zalo:id/icon_action");
    assertEquals("com.zing.zalo:id/icon_action", LabelNodeId.resourceName(node));
  }

  @Test
  public void focusableImageViewCanBeLabeledWithoutClickAction() {
    AccessibilityNodeInfoCompat node = zaloImage();
    node.setClickable(false);
    node.setFocusable(true);
    assertTrue(LabelNodeId.resourceName(node).startsWith("com.zing.zalo:id/sandfly_unlabeled_"));
  }

  @Test
  public void longClickableImageViewCanBeLabeled() {
    AccessibilityNodeInfoCompat node = zaloImage();
    node.setClickable(false);
    node.setLongClickable(true);
    assertTrue(LabelNodeId.resourceName(node).startsWith("com.zing.zalo:id/sandfly_unlabeled_"));
  }

  @Test
  public void screenReaderFocusableImageViewCanBeLabeled() {
    AccessibilityNodeInfoCompat node = zaloImage();
    node.setClickable(false);
    node.setFocusable(false);
    node.setScreenReaderFocusable(true);
    assertTrue(LabelNodeId.resourceName(node).startsWith("com.zing.zalo:id/sandfly_unlabeled_"));
  }

  @Test
  public void decorativeImageViewWithoutActionHasNoSyntheticId() {
    AccessibilityNodeInfoCompat node = zaloImage();
    node.setClickable(false);
    node.setLongClickable(false);
    node.setFocusable(false);
    assertNull(LabelNodeId.resourceName(node));
  }

  @Test
  public void imageLabelsDoNotLeakAcrossApplications() {
    AccessibilityNodeInfoCompat node = zaloImage();
    String zaloId = LabelNodeId.resourceName(node);
    node.setPackageName("com.example.photo");
    assertNotEquals(zaloId, LabelNodeId.resourceName(node));
  }

  @Test
  public void labeledImageViewReportsExistingDescription() {
    AccessibilityNodeInfoCompat node = zaloImage();
    assertFalse(LabelNodeId.hasSpokenLabel(node));
    node.setContentDescription("Mở ảnh");
    assertTrue(LabelNodeId.hasSpokenLabel(node));
  }

  @Test
  public void idlessImageButtonStillCanBeLabeled() {
    AccessibilityNodeInfoCompat node = zaloImage();
    node.setClassName(ImageButton.class.getName());
    assertTrue(LabelNodeId.resourceName(node).startsWith("com.zing.zalo:id/sandfly_unlabeled_"));
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
  public void frameworkIdsAreScopedToTheApplicationInsteadOfAllAndroidDialogs() {
    AccessibilityNodeInfoCompat node = button();
    node.setViewIdResourceName("android:id/button1");
    String firstApp = LabelNodeId.resourceName(node);
    assertTrue(firstApp.startsWith("com.example.app:id/sandfly_unlabeled_"));
    node.setPackageName("com.example.other");
    String secondApp = LabelNodeId.resourceName(node);
    assertTrue(secondApp.startsWith("com.example.other:id/sandfly_unlabeled_"));
    assertNotEquals(firstApp, secondApp);
  }

  @Test
  public void unlabeledPlaceholderDoesNotPreventManualLabeling() {
    AccessibilityNodeInfoCompat node = button();
    node.setContentDescription("Chưa được gắn nhãn");
    assertTrue(LabelNodeId.isLabelableControl(node));
    assertTrue(LabelNodeId.resourceName(node).startsWith("com.example.app:id/sandfly_unlabeled_"));
  }

  @Test
  public void clickableCustomContainerCanHaveManualLabelWithoutId() {
    AccessibilityNodeInfoCompat node = button();
    node.setClassName("android.view.ViewGroup");
    assertTrue(LabelNodeId.isLabelableControl(node));
    assertTrue(LabelNodeId.resourceName(node).startsWith("com.example.app:id/sandfly_unlabeled_"));
  }

  @Test
  public void existingTextMakesButtonLabeled() {
    AccessibilityNodeInfoCompat node = button();
    assertFalse(LabelNodeId.hasSpokenLabel(node));
    node.setContentDescription("Save");
    assertTrue(LabelNodeId.hasSpokenLabel(node));
  }
}
