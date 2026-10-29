package com.google.android.accessibility.talkback.eventprocessor;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.utils.Performance;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class EventProcessingStats {
  private static final int MAX_ROWS = 128;
  private static final Map<String, Row> rows = new LinkedHashMap<>();
  private static long startedAt = SystemClock.uptimeMillis();
  private static int maximumQueueDepth;

  private static final class Row {
    long received;
    long deferred;
    long coalesced;
    long admitted;
    long gateWaitTotalMs;
    long gateWaitMaxMs;
    long serviceTotalMs;
    long serviceMaxMs;
    long dequeued;
    long queueWaitTotalMs;
    long queueWaitMaxMs;
    long compositorTotalMs;
    long dropped;
  }

  private EventProcessingStats() {}

  private static Row row(AccessibilityEvent event) {
    String key = String.valueOf(event.getPackageName()) + "/"
        + AccessibilityEvent.eventTypeToString(event.getEventType());
    if (!rows.containsKey(key) && rows.size() >= MAX_ROWS) {
      key = "other";
    }
    return rows.computeIfAbsent(key, unused -> new Row());
  }

  public static synchronized void received(AccessibilityEvent event) {
    if (Performance.getInstance().getComputeStatsEnabled()) {
      row(event).received++;
    }
  }

  public static synchronized void deferred(AccessibilityEvent event) {
    if (Performance.getInstance().getComputeStatsEnabled()) {
      row(event).deferred++;
    }
  }

  public static synchronized void coalesced(AccessibilityEvent event) {
    if (Performance.getInstance().getComputeStatsEnabled()) {
      row(event).coalesced++;
    }
  }

  public static synchronized void dropped(AccessibilityEvent event) {
    if (Performance.getInstance().getComputeStatsEnabled()) row(event).dropped++;
  }

  public static synchronized void admitted(AccessibilityEvent event, long waitMs) {
    if (Performance.getInstance().getComputeStatsEnabled()) {
      Row values = row(event);
      values.admitted++;
      values.gateWaitTotalMs += waitMs;
      values.gateWaitMaxMs = Math.max(values.gateWaitMaxMs, waitMs);
    }
  }

  public static synchronized void handled(AccessibilityEvent event, long durationMs) {
    if (Performance.getInstance().getComputeStatsEnabled()) {
      Row values = row(event);
      values.serviceTotalMs += durationMs;
      values.serviceMaxMs = Math.max(values.serviceMaxMs, durationMs);
    }
  }

  public static synchronized void dequeued(AccessibilityEvent event, long waitMs, int depth) {
    if (Performance.getInstance().getComputeStatsEnabled()) {
      Row values = row(event);
      values.dequeued++;
      values.queueWaitTotalMs += waitMs;
      values.queueWaitMaxMs = Math.max(values.queueWaitMaxMs, waitMs);
      maximumQueueDepth = Math.max(maximumQueueDepth, depth);
    }
  }

  public static synchronized void compositorHandled(AccessibilityEvent event, long durationMs) {
    if (Performance.getInstance().getComputeStatsEnabled()) {
      row(event).compositorTotalMs += durationMs;
    }
  }

  public static synchronized void reset() {
    rows.clear();
    startedAt = SystemClock.uptimeMillis();
    maximumQueueDepth = 0;
  }

  public static synchronized String snapshot() {
    StringBuilder out = new StringBuilder("== Event processing ==\n");
    out.append("Interval: ").append(SystemClock.uptimeMillis() - startedAt).append(" ms\n");
    out.append("Background limit: ")
        .append("very_low".equals(ProcessorEventQueue.getProcessingLevel())
            ? "one latest event globally per 500 ms; deferred during touch and for 150 ms after input"
            : "off")
        .append('\n');
    out.append("Counts start at service entry; Android can still deliver suppressed events.\n");
    out.append("Admitted passes the early gate; later filters may still drop it.\n");
    out.append("Maximum compositor queue depth: ").append(maximumQueueDepth).append('\n');
    out.append("source/type: received deferred coalesced dropped admitted gate_wait_total/max_ms "
        + "service_total/max_ms dequeued queue_wait_total/max_ms compositor_total_ms\n");
    for (Map.Entry<String, Row> entry : rows.entrySet()) {
      Row v = entry.getValue();
      out.append(String.format(Locale.ROOT,
          "%s: %d %d %d %d %d %d/%d %d/%d %d %d/%d %d%n", entry.getKey(),
          v.received, v.deferred, v.coalesced, v.dropped, v.admitted,
          v.gateWaitTotalMs, v.gateWaitMaxMs, v.serviceTotalMs, v.serviceMaxMs,
          v.dequeued, v.queueWaitTotalMs, v.queueWaitMaxMs, v.compositorTotalMs));
    }
    return out.append('\n').toString();
  }
}
