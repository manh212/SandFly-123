package com.google.android.accessibility.talkback.labeling;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.imagecaption.ImageContents;
import com.google.android.accessibility.utils.labeling.Label;
import com.google.android.accessibility.utils.labeling.LabelManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ManualControlLabelTest {
  private AccessibilityNodeInfoCompat button() {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain());
    node.setPackageName("com.example.app");
    node.setClassName("android.widget.Button");
    node.setClickable(true);
    node.setEnabled(true);
    node.setContentDescription("Chưa được gắn nhãn");
    return node;
  }

  @Test
  public void menuAllowsManualLabelEvenWhenAutomaticMissingLabelHeuristicRejectsIt() {
    AccessibilityNodeInfoCompat node = button();
    CustomLabelManager manager = mock(CustomLabelManager.class, CALLS_REAL_METHODS);
    doReturn(true).when(manager).canAddLabel(node);
    doReturn(false).when(manager).needsLabel(node);
    assertTrue(manager.new State().supportsLabel(node));
  }

  @Test
  public void speechFindsSavedIdlessLabelUsingSameKeyAsTheEditor() {
    AccessibilityNodeInfoCompat node = button();
    String key = LabelNodeId.resourceName(node);
    LabelManager manager = mock(LabelManager.class);
    Label label = new Label("com.example.app", "signature", key,
        "Gọi tài xế", "vi", 1, "", 0);
    when(manager.getLabelForViewIdFromCache(key)).thenReturn(label);
    assertEquals("Gọi tài xế", new ImageContents(manager, null).getLabel(node));
    verify(manager).getLabelForViewIdFromCache(key);
  }
}
