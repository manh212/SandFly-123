package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.traversal.TraversalBudget;
import java.util.concurrent.CancellationException;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NodeOverridesBudgetTest {
  private int calls;
  private final NodeOverrides.Source source = new NodeOverrides.Source() {
    @Override public Integer role(AccessibilityNodeInfoCompat node) { return null; }
    @Override public CharSequence state(AccessibilityNodeInfoCompat node) { return null; }
    @Override public boolean hidden(AccessibilityNodeInfoCompat node) { return false; }
    @Override public Boolean heading(AccessibilityNodeInfoCompat node) { return null; }
    @Override public boolean grouped(AccessibilityNodeInfoCompat node) { return false; }
    @Override public NodeOverrides.Order order(AccessibilityNodeInfoCompat node) { calls++; return null; }
  };

  @After
  public void reset() { NodeOverrides.set(null); }

  @Test
  public void newlyActivatedScriptCancelsWorkerTraversalBeforeCallingTheHost() {
    AccessibilityNodeInfoCompat node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain());
    try {
      TraversalBudget.run(SystemClock.uptimeMillis() + 1000, () -> {
        NodeOverrides.set(source);
        return NodeOverrides.order(node);
      });
      fail("Worker traversal called script overrides");
    } catch (CancellationException expected) {
      assertEquals(0, calls);
    }
    NodeOverrides.order(node);
    assertEquals(1, calls);
  }
}
