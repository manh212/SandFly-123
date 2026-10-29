package com.google.android.accessibility.utils;

import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LatencyExperiments {
  public enum Feature {
    TRACE("trace"),
    RESET_SESSION("reset_session"),
    STALL_CAPTURE("stall_capture"),
    LIGHT_LOGGING("light_logging"),
    NONBLOCKING_PREFETCH("nonblocking_prefetch"),
    ASYNC_ORDER("async_order"),
    SCOPED_CACHE("scoped_cache"),
    INPUT_PRIORITY("input_priority"),
    TOAST_FILTER("toast_filter"),
    EARLY_CONTROLS("early_controls"),
    CACHED_TEXT("cached_text"),
    FRESH_PREPARED("fresh_prepared"),
    PCM_ADJACENT("pcm_adjacent"),
    PCM_SECOND("pcm_second"),
    IMMEDIATE_SPEECH("immediate_speech"),
    FAST_SCROLL("fast_scroll"),
    LIVE_EDGE_REUSE("live_edge_reuse"),
    AUDIO_RESAMPLE("audio_resample"),
    AUDIO_KEEP_WARM("audio_keep_warm");

    public final String key;
    private volatile boolean enabled;

    Feature(String suffix) {
      key = "pref_latency_" + suffix;
    }

    public boolean enabled() {
      return enabled;
    }
  }

  private static volatile long lastInput;
  private static volatile boolean touching;
  private static volatile boolean preparationAllowed;
  private static volatile Runnable inputListener;

  private LatencyExperiments() {}

  public static void load(SharedPreferences preferences) {
    for (Feature feature : Feature.values()) {
      feature.enabled = preferences.getBoolean(feature.key, false);
    }
  }

  public static void setEnabled(Feature feature, boolean enabled) {
    feature.enabled = enabled;
  }

  public static void setPreparationAllowed(boolean allowed) {
    preparationAllowed = allowed;
  }

  public static boolean preparationAllowed() {
    return preparationAllowed;
  }

  public static boolean preparePcm() {
    return preparationAllowed && (Feature.PCM_ADJACENT.enabled() || Feature.PCM_SECOND.enabled());
  }

  public static void setInputListener(Runnable listener) {
    inputListener = listener;
  }

  public static void onInput() {
    lastInput = SystemClock.uptimeMillis();
    Runnable listener = inputListener;
    if (listener != null) listener.run();
  }

  public static void onTouch(boolean active) {
    touching = active;
    onInput();
  }

  public static boolean inputIsActive(long quietMillis) {
    return touching || SystemClock.uptimeMillis() - lastInput < quietMillis;
  }

  public static Map<String, Boolean> snapshot() {
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (Feature feature : Feature.values()) result.put(feature.key, feature.enabled);
    return result;
  }
}
