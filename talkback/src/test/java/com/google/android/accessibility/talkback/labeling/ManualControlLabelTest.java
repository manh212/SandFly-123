package com.google.android.accessibility.talkback.labeling;

import static org.junit.Assert.*;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.imagecaption.ImageContents;
import com.google.android.accessibility.utils.labeling.Label;
import com.google.android.accessibility.utils.labeling.LabelManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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
    android.content.Context context = RuntimeEnvironment.getApplication();
    node.setPackageName(context.getPackageName());
    CustomLabelManager manager = new CustomLabelManager(context);
    try {
      assertFalse(manager.stateReader().needsLabel(node));
      assertTrue(manager.stateReader().supportsLabel(node));
    } finally {
      manager.shutdown();
    }
  }

  @Test
  public void speechFindsSavedIdlessLabelUsingSameKeyAsTheEditor() {
    AccessibilityNodeInfoCompat node = button();
    String key = LabelNodeId.resourceName(node);
    Label label = new Label("com.example.app", "signature", key,
        "Gọi tài xế", "vi", 1, "", 0);
    LabelManager manager = new LabelManager() {
      @Override
      public Label getLabelForViewIdFromCache(String requested) {
        assertEquals(key, requested);
        return label;
      }

      @Override
      public LabelManager.State stateReader() {
        return null;
      }
    };
    assertEquals("Gọi tài xế", new ImageContents(manager, null).getLabel(node));
  }
}
