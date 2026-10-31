package com.google.android.accessibility.utils.output;

import android.os.VibrationEffect;
import android.os.Vibrator;

public final class VibrationIntensity {
  private static final int[] LEVEL_PERCENTAGES = {15, 35, 65, 100};

  private VibrationIntensity() {}

  public static int levelForPercentage(int percentage) {
    if (percentage < 25) return 0;
    if (percentage < 50) return 1;
    if (percentage < 85) return 2;
    return 3;
  }

  public static int percentageForLevel(int level) {
    return LEVEL_PERCENTAGES[Math.max(0, Math.min(3, level))];
  }

  public static long[] scaleOnDurations(long[] timings, float strength) {
    float scale = strength < 0.25f ? 0.4f : strength < 0.5f ? 0.7f : strength < 0.85f ? 1f : 1.4f;
    long[] result = timings.clone();
    for (int i = 1; i < result.length; i += 2) {
      if (result[i] > 0) {
        result[i] = Math.min(250L, Math.max(8L, Math.round(result[i] * scale)));
      }
    }
    return result;
  }

  public static VibrationEffect createWaveform(
      Vibrator vibrator, long[] timings, float strength) {
    if (!vibrator.hasAmplitudeControl()) {
      return VibrationEffect.createWaveform(scaleOnDurations(timings, strength), -1);
    }
    int amplitude = Math.max(1, Math.min(255, Math.round(255f * strength)));
    int[] amplitudes = new int[timings.length];
    for (int i = 1; i < amplitudes.length; i += 2) {
      if (timings[i] > 0) amplitudes[i] = amplitude;
    }
    return VibrationEffect.createWaveform(timings, amplitudes, -1);
  }
}
