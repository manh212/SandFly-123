package com.google.android.accessibility.utils.traversal;

import android.os.SystemClock;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

public final class TraversalBudget {
  private static final ThreadLocal<Long> deadline = new ThreadLocal<>();

  private TraversalBudget() {}

  public static <T> T run(long deadlineMs, Supplier<T> task) {
    Long previous = deadline.get();
    deadline.set(deadlineMs);
    try {
      check();
      return task.get();
    } finally {
      if (previous == null) deadline.remove(); else deadline.set(previous);
    }
  }

  public static boolean isActive() {
    return deadline.get() != null;
  }

  public static void check() {
    Long limit = deadline.get();
    if (limit != null && SystemClock.uptimeMillis() >= limit) {
      throw new CancellationException("Traversal time budget exhausted");
    }
  }
}
