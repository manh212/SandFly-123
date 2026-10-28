package com.google.android.accessibility.talkback.actor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.output.EditTextActionHistory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TextEditActorClearAllTest {

  public static class TestService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
  }

  @Test
  public void clearAllSetsTextToEmpty() {
    TestService service = Robolectric.buildService(TestService.class).get();
    TextEditActor actor =
        new TextEditActor(service, new EditTextActionHistory(), null, null, null, null, null);
    actor.setPipeline(feedback -> true);
    AccessibilityNodeInfo node = editTextNode("Existing text");
    String[] written = {null};
    Shadows.shadowOf(node)
        .setOnPerformActionListener(
            (action, args) -> {
              assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action);
              written[0] =
                  args.getCharSequence(
                          AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                      .toString();
              return true;
            });

    assertTrue(actor.clearAll(AccessibilityNodeInfoCompat.wrap(node), null));
    assertEquals("", written[0]);
  }

  @Test
  public void clearAllDoesNotRunForAnEmptyEditText() {
    TestService service = Robolectric.buildService(TestService.class).get();
    TextEditActor actor =
        new TextEditActor(service, new EditTextActionHistory(), null, null, null, null, null);
    actor.setPipeline(feedback -> true);
    AccessibilityNodeInfo node = editTextNode("");
    boolean[] performed = {false};
    Shadows.shadowOf(node)
        .setOnPerformActionListener(
            (action, args) -> {
              performed[0] = true;
              return true;
            });

    assertFalse(actor.clearAll(AccessibilityNodeInfoCompat.wrap(node), null));
    assertFalse(performed[0]);
  }

  private static AccessibilityNodeInfo editTextNode(String text) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.EditText");
    node.setEditable(true);
    node.setText(text);
    node.addAction(AccessibilityNodeInfo.ACTION_SET_TEXT);
    node.addAction(AccessibilityNodeInfo.ACTION_SET_SELECTION);
    return node;
  }
}
