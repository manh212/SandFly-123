package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EditorTargetResolverTest {
  @Test
  public void inputFocusWinsWhenAccessibilityFocusIsElsewhere() {
    AccessibilityNodeInfoCompat root = node(null, false, 0);
    AccessibilityNodeInfoCompat focused = node(root, false, 100);
    AccessibilityNodeInfoCompat nearest = node(root, true, 110);
    AccessibilityNodeInfoCompat typing = node(root, true, 500);
    assertEquals(typing, EditorTargetResolver.find(focused, null, typing, root));
    assertEquals(nearest, EditorTargetResolver.find(focused, null, null, root));
  }

  @Test
  public void directlyFocusedEditorAndKeyboardEditorHavePriority() {
    AccessibilityNodeInfoCompat root = node(null, false, 0);
    AccessibilityNodeInfoCompat editor = node(root, true, 100);
    AccessibilityNodeInfoCompat other = node(root, true, 200);
    assertEquals(editor, EditorTargetResolver.find(editor, other, other, root));
    AccessibilityNodeInfoCompat keyboardKey = node(null, false, 600);
    assertEquals(editor, EditorTargetResolver.find(keyboardKey, editor, other, root));
  }

  @Test
  public void invisibleDisabledAndReadOnlyNodesAreIgnored() {
    AccessibilityNodeInfoCompat root = node(null, false, 0);
    AccessibilityNodeInfoCompat hidden = node(root, true, 100);
    hidden.setVisibleToUser(false);
    AccessibilityNodeInfoCompat disabled = node(root, true, 200);
    disabled.setEnabled(false);
    AccessibilityNodeInfoCompat readOnly = node(root, true, 300);
    readOnly.setEditable(false);
    assertNull(EditorTargetResolver.find(null, null, null, root));
  }

  @Test
  public void missingEditorsDoNotProduceATarget() {
    AccessibilityNodeInfoCompat root = node(null, false, 0);
    node(root, false, 100);
    assertNull(EditorTargetResolver.find(null, null, null, root));
    assertNull(EditorTargetResolver.find(null, null, null, null));
  }

  private static AccessibilityNodeInfoCompat node(AccessibilityNodeInfoCompat parent, boolean editable, int top) {
    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
    info.setClassName(editable ? "android.widget.EditText" : "android.widget.Button");
    info.setEditable(editable);
    info.setEnabled(true);
    info.setVisibleToUser(true);
    info.setBoundsInScreen(new Rect(0, top, 100, top + 50));
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(info);
    }
    return AccessibilityNodeInfoCompat.wrap(info);
  }
}
