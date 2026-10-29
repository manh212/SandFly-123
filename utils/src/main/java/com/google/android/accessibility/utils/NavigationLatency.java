package com.google.android.accessibility.utils;

import com.google.android.accessibility.utils.Performance.EventId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

final class NavigationLatency {
  enum Stage { RESPONSE, COMPOSED, QUEUED, FIRST_PCM, AUDIO_SUBMITTED, TTS_START }

  private static final int MAX_LINKS = 200;
  private final LongSupplier clock;
  private final Map<EventId, Trace> events = boundedMap();
  private final Map<String, Trace> utterances = boundedMap();
  private final Map<String, Statistics[]> statistics = new TreeMap<>();

  private static <K, V> Map<K, V> boundedMap() {
    return new LinkedHashMap<K, V>() {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, V> entry) {
        return size() > MAX_LINKS;
      }
    };
  }

  private static final class Trace {
    final EventId origin;
    final boolean[] recorded = new boolean[Stage.values().length];
    String utterance;

    Trace(EventId origin) {
      this.origin = origin;
    }
  }

  NavigationLatency(LongSupplier clock) {
    this.clock = clock;
  }

  synchronized void start(EventId eventId) {
    if (eventId != null && eventId.getEventType() == Performance.EVENT_TYPE_GESTURE) {
      events.put(eventId, new Trace(eventId));
    }
  }

  synchronized void link(EventId feedback, EventId origin) {
    Trace trace = events.get(origin);
    if (feedback == null || trace == null || feedback.equals(origin)) return;
    events.put(feedback, trace);
    record(trace, Stage.RESPONSE, clock.getAsLong());
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

  synchronized void audio(String utterance, Stage stage, long uptimeMs) {
    record(utterances.get(utterance), stage, uptimeMs);
  }

  private void record(Trace trace, Stage stage, long uptimeMs) {
    if (trace == null || trace.recorded[stage.ordinal()]) return;
    long elapsed = uptimeMs - trace.origin.getEventTimeMs();
    if (elapsed < 0) return;
    trace.recorded[stage.ordinal()] = true;
    String label = AccessibilityServiceCompatUtils.gestureIdToString(trace.origin.getEventSubtype());
    Statistics[] values = statistics.computeIfAbsent(label, unused -> {
      Statistics[] created = new Statistics[Stage.values().length];
      for (int i = 0; i < created.length; i++) created[i] = new Statistics();
      return created;
    });
    values[stage.ordinal()].increment(elapsed);
  }

  synchronized void display(Logger logger) {
    logger.log("== Linked gesture response latency (uptime milliseconds) ==");
    for (Map.Entry<String, Statistics[]> entry : statistics.entrySet()) {
      for (Stage stage : Stage.values()) {
        Statistics value = entry.getValue()[stage.ordinal()];
        if (value.getCount() == 0) continue;
        logger.log("NAVIGATION_%s/%s", entry.getKey(), stage);
        Performance.displayStatistics(logger, value);
      }
    }
  }

  synchronized void clearLinks() {
    events.clear();
    utterances.clear();
  }

  synchronized void reset() {
    clearLinks();
    statistics.clear();
  }
}
