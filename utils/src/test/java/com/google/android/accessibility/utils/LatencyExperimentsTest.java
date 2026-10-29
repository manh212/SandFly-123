package com.google.android.accessibility.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LatencyExperimentsTest {
  @After
  public void reset() {
    for (LatencyExperiments.Feature feature : LatencyExperiments.Feature.values()) {
      LatencyExperiments.setEnabled(feature, false);
    }
    LatencyExperiments.setInputListener(null);
    LatencyExperiments.onTouch(false);
    LatencyExperiments.setPreparationAllowed(false);
  }

  @Test
  public void persistedSwitchesAreIndependentAndMissingSwitchesAreOff() {
    SharedPreferences preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("latency-test", Context.MODE_PRIVATE);
    preferences.edit().clear().putBoolean(LatencyExperiments.Feature.ASYNC_ORDER.key, true).commit();
    LatencyExperiments.load(preferences);
    for (LatencyExperiments.Feature feature : LatencyExperiments.Feature.values()) {
      assertEquals(feature == LatencyExperiments.Feature.ASYNC_ORDER, feature.enabled());
    }
    preferences.edit().clear().commit();
    LatencyExperiments.load(preferences);
    assertFalse(LatencyExperiments.Feature.ASYNC_ORDER.enabled());
  }

  @Test
  public void backgroundPreparationRequiresItsMasterAndAnIndividualSwitch() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.PCM_SECOND, true);
    LatencyExperiments.setPreparationAllowed(false);
    assertFalse(LatencyExperiments.preparePcm());
    LatencyExperiments.setPreparationAllowed(true);
    assertTrue(LatencyExperiments.preparePcm());
    assertFalse(LatencyExperiments.Feature.PCM_ADJACENT.enabled());
  }

  @Test
  public void inputCancelsPreparationImmediatelyAndTouchBlocksNewWork() {
    AtomicInteger cancellations = new AtomicInteger();
    LatencyExperiments.setInputListener(cancellations::incrementAndGet);
    LatencyExperiments.onTouch(true);
    assertEquals(1, cancellations.get());
    assertTrue(LatencyExperiments.inputIsActive(0));
    LatencyExperiments.onTouch(false);
    assertEquals(2, cancellations.get());
    assertFalse(LatencyExperiments.inputIsActive(0));
  }
}
