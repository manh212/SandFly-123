package com.google.android.accessibility.utils;

import com.google.android.accessibility.utils.Performance.EventId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

final class NavigationLatency {
  enum Stage {
    RESPONSE, COMPOSED, QUEUED, FIRST_PCM, AUDIO_SUBMITTED, TTS_START,
    PIVOT_READY, ORDER_READY, ACTION_RESULT, SCROLL_REQUEST, SCROLL_READY,
    FIRST_SOUND_PCM, SOUND_SUBMITTED
  }
  private static final int MAX_LINKS = 1024;
  private final LongSupplier clock;
  private final Map<EventId, Trace> events = boundedMap();
  private final Map<String, Trace> utterances = boundedMap();
  private final Map<String, Statistics[]> statistics = new TreeMap<>();
  private final Map<String, long[][]> violations = new TreeMap<>();
  private final ArrayDeque<Trace> traces = new ArrayDeque<>();
  private final ArrayDeque<String> stalls = new ArrayDeque<>();
  private long sessionStarted;

  private static <K, V> Map<K, V> boundedMap() {
    return new LinkedHashMap<K, V>() {
      @Override protected boolean removeEldestEntry(Map.Entry<K, V> entry) {
        return size() > MAX_LINKS;
      }
    };
  }

  private static final class Trace {
    final EventId origin;
    final long[] elapsed = new long[Stage.values().length];
    final Map<String, Long> details = new LinkedHashMap<>();
    String utterance;
    String outcome = "pending";
    boolean interrupted;
    boolean navigation;
    long motionEnd = -1;
    Trace(EventId origin) {
      this.origin = origin;
      Arrays.fill(elapsed, -1);
    }
  }

  NavigationLatency(LongSupplier clock) {
    this.clock = clock;
    sessionStarted = clock.getAsLong();
  }

  synchronized void start(EventId eventId) {
    if (eventId == null || eventId.getEventType() != Performance.EVENT_TYPE_GESTURE) return;
    Trace trace = new Trace(eventId);
    events.put(eventId, trace);
    if (LatencyExperiments.Feature.TRACE.enabled()) {
      if (traces.size() == MAX_LINKS) traces.removeFirst();
      traces.addLast(trace);
    }
  }

  synchronized void motionEnd(EventId eventId, long uptimeMs) {
    Trace trace = events.get(eventId);
    if (trace != null && uptimeMs >= 0 && uptimeMs <= trace.origin.getEventTimeMs()) {
      trace.motionEnd = uptimeMs;
    }
  }

  synchronized void link(EventId feedback, EventId origin) {
    Trace trace = events.get(origin);
    if (feedback == null || trace == null || feedback.equals(origin)) return;
    events.put(feedback, trace);
    record(trace, Stage.RESPONSE, clock.getAsLong());
  }

  synchronized void navigationAction(EventId eventId) {
    Trace trace = events.get(eventId);
    if (trace != null) trace.navigation = true;
  }

  synchronized boolean hasNavigation(EventId eventId) {
    Trace trace = events.get(eventId);
    return trace != null && trace.navigation;
  }

  synchronized void cacheResult(String utterance, boolean hit) {
    Trace trace = utteranceTrace(utterance);
    if (trace != null) trace.details.put("pcm_cache_hit", hit ? 1L : 0L);
  }

  synchronized void composed(EventId eventId) {
    record(events.get(eventId), Stage.COMPOSED, clock.getAsLong());
  }

  synchronized void queued(EventId eventId, String utterance) {
    Trace trace = events.get(eventId);
    if (trace == null || utterance == null) return;
    if (trace.utterance == null) {
      trace.utterance = utterance;
      utterances.put(utterance, trace);
      record(trace, Stage.QUEUED, clock.getAsLong());
    }
  }

  private Trace utteranceTrace(String utterance) {
    Trace trace = utterances.get(utterance);
    if (trace == null && utterance != null && utterance.endsWith("#chunk0")) {
      trace = utterances.get(utterance.substring(0, utterance.length() - 7));
    }
    return trace;
  }

  synchronized void audio(String utterance, Stage stage, long uptimeMs) {
    Trace trace = utteranceTrace(utterance);
    record(trace, stage, uptimeMs);
    if (trace != null && stage == Stage.AUDIO_SUBMITTED) {
      trace.outcome = trace.interrupted ? "interrupted_after_audio" : "audio_submitted";
    }
  }

  synchronized void interrupted(String utterance) {
    Trace trace = utteranceTrace(utterance);
    if (trace != null) {
      trace.interrupted = true;
      trace.outcome = trace.elapsed[Stage.AUDIO_SUBMITTED.ordinal()] >= 0
          ? "interrupted_after_audio" : "interrupted_before_audio";
    }
  }

  synchronized void stage(EventId eventId, Stage stage) {
    record(events.get(eventId), stage, clock.getAsLong());
  }

  synchronized void note(EventId eventId, String name, long value) {
    Trace trace = events.get(eventId);
    if (trace != null && trace.details.size() < 64) trace.details.put(name, value);
  }

  synchronized void failed(EventId eventId, String reason) {
    Trace trace = events.get(eventId);
    if (trace != null && trace.elapsed[Stage.AUDIO_SUBMITTED.ordinal()] < 0) trace.outcome = reason;
  }

  synchronized void stall(long durationMs, String stack) {
    if (stalls.size() == 16) stalls.removeFirst();
    stalls.addLast("uptime=" + clock.getAsLong() + " blocked_ms=" + durationMs + " " + stack);
  }

  private void record(Trace trace, Stage stage, long uptimeMs) {
    if (trace == null || trace.elapsed[stage.ordinal()] >= 0) return;
    long elapsed = uptimeMs - trace.origin.getEventTimeMs();
    if (elapsed < 0) return;
    trace.elapsed[stage.ordinal()] = elapsed;
    String label = AccessibilityServiceCompatUtils.gestureIdToString(trace.origin.getEventSubtype());
    Statistics[] values = statistics.computeIfAbsent(label, unused -> {
      Statistics[] created = new Statistics[Stage.values().length];
      for (int i = 0; i < created.length; i++) created[i] = new Statistics();
      return created;
    });
    values[stage.ordinal()].increment(elapsed);
    if (LatencyExperiments.Feature.TRACE.enabled()) {
      long[][] counts = violations.computeIfAbsent(label, unused -> new long[Stage.values().length][2]);
      if (elapsed > 20) counts[stage.ordinal()][0]++;
      if (elapsed > 50) counts[stage.ordinal()][1]++;
    }
  }

  void display(Logger logger) {
    Map<String, Statistics[]> values = new TreeMap<>();
    Map<String, long[][]> budgets = new TreeMap<>();
    List<String> rows = new ArrayList<>();
    List<String> capturedStalls;
    long started;
    synchronized (this) {
      started = sessionStarted;
      for (Map.Entry<String, Statistics[]> entry : statistics.entrySet()) {
        Statistics[] copy = new Statistics[Stage.values().length];
        for (int i = 0; i < copy.length; i++) copy[i] = entry.getValue()[i].snapshot();
        values.put(entry.getKey(), copy);
        long[][] counts = violations.getOrDefault(entry.getKey(), new long[Stage.values().length][2]);
        long[][] countsCopy = new long[counts.length][2];
        for (int i = 0; i < counts.length; i++) countsCopy[i] = counts[i].clone();
        budgets.put(entry.getKey(), countsCopy);
      }
      if (LatencyExperiments.Feature.TRACE.enabled()) {
        for (Trace trace : traces) {
          StringBuilder row = new StringBuilder();
          row.append("gesture_uptime=").append(trace.origin.getEventTimeMs())
              .append(" gesture=").append(trace.origin.getEventSubtype())
              .append(" outcome=").append(trace.outcome);
          if ("pending".equals(trace.outcome)
              && clock.getAsLong() - trace.origin.getEventTimeMs() > 1000) row.append(" overdue=true");
          if (trace.motionEnd >= 0) row.append(" input_to_callback_ms=")
              .append(trace.origin.getEventTimeMs() - trace.motionEnd);
          for (Stage stage : Stage.values()) {
            if (trace.elapsed[stage.ordinal()] >= 0) row.append(' ').append(stage)
                .append('=').append(trace.elapsed[stage.ordinal()]);
          }
          for (Map.Entry<String, Long> detail : trace.details.entrySet()) {
            row.append(' ').append(detail.getKey()).append('=').append(detail.getValue());
          }
          rows.add(row.toString());
        }
      }
      capturedStalls = new ArrayList<>(stalls);
    }
    logger.log("== Linked gesture response latency (uptime milliseconds) ==");
    logger.log("Measurement session uptime: %d", started);
    for (Map.Entry<String, Statistics[]> entry : values.entrySet()) {
      for (Stage stage : Stage.values()) {
        Statistics value = entry.getValue()[stage.ordinal()];
        if (value.getCount() == 0) continue;
        logger.log("NAVIGATION_%s/%s", entry.getKey(), stage);
        Performance.displayStatistics(logger, value);
        if (LatencyExperiments.Feature.TRACE.enabled()) {
          long[] counts = budgets.get(entry.getKey())[stage.ordinal()];
          logger.log("    budget: p95=%d ms, max=%d ms, over20=%d, over50=%d",
              value.getPercentile(95), value.getMaximum(), counts[0], counts[1]);
        }
      }
    }
    if (!rows.isEmpty()) {
      logger.log("== Per-gesture latency trace (latest %d gestures) ==", rows.size());
      for (String row : rows) logger.log("%s", row);
    }
    if (!capturedStalls.isEmpty()) {
      logger.log("== Captured main-thread stalls ==");
      for (String stall : capturedStalls) logger.log("%s", stall);
    }
  }

  synchronized void clearLinks() {
    events.clear();
    utterances.clear();
  }

  synchronized void reset() {
    clearLinks();
    statistics.clear();
    violations.clear();
    traces.clear();
    stalls.clear();
    sessionStarted = clock.getAsLong();
  }
}

