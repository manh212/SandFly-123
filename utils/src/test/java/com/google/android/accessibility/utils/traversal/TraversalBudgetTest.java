package com.google.android.accessibility.utils.traversal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.SystemClock;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TraversalBudgetTest {
  @Test
  public void expiredBudgetRejectsWorkAndDoesNotLeakToTheNextTraversal() {
    AtomicBoolean called = new AtomicBoolean();
    try {
      TraversalBudget.run(SystemClock.uptimeMillis(), () -> { called.set(true); return null; });
      fail("Expired traversal returned a result");
    } catch (CancellationException expected) {
      assertFalse(called.get());
    }
    TraversalBudget.check();
    assertTrue(TraversalBudget.run(SystemClock.uptimeMillis() + 1000, () -> true));
  }

  @Test
  public void nestedBudgetRestoresItsOuterDeadlineEvenAfterCancellation() {
    TraversalBudget.run(SystemClock.uptimeMillis() + 1000, () -> {
      try {
        TraversalBudget.run(SystemClock.uptimeMillis(), () -> true);
        fail("Expired nested traversal returned a result");
      } catch (CancellationException expected) {
        TraversalBudget.check();
      }
      return null;
    });
    TraversalBudget.check();
  }
}
