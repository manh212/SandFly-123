/*
 * Copyright (C) 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpeechControllerImplTest {

  /** A failover TTS that is always ready and records what it is asked to speak. */
  private static final class RecordingFailoverTts extends FailoverTextToSpeech {
    final List<String> spokenTexts = new ArrayList<>();

    RecordingFailoverTts(Context context) {
      super(context, /* enableSpeechCache= */ false);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void speak(
        CharSequence text,
        Locale locale,
        float pitch,
        float rate,
        Map<String, String> params,
        Map<String, Integer> customFlags,
        int stream,
        float volume,
        boolean preventDeviceSleep,
        boolean flushGlobalTtsQueue,
        EventId eventId) {
      spokenTexts.add(text == null ? null : text.toString());
    }
  }

  private static final SpeechController.Delegate NO_OP_DELEGATE =
      new SpeechController.Delegate() {
        @Override
        public boolean isAudioPlaybackActive() {
          return false;
        }

        @Override
        public boolean isMicrophoneActiveAndHeadphoneOff() {
          return false;
        }

        @Override
        public boolean isSsbActive() {
          return false;
        }

        @Override
        public boolean isPhoneCallActive() {
          return false;
        }

        @Override
        public void onSpeakingForcedFeedback() {}
      };

  private Context context;
  private RecordingFailoverTts failoverTts;
  private SpeechControllerImpl speechController;
  private RecordingFailoverTts activeTts;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    LastSpokenTextHolder.clearSpokenHistoryForTest();
    failoverTts = new RecordingFailoverTts(context);
    activeTts = failoverTts;
    speechController =
        new SpeechControllerImpl(
            context,
            NO_OP_DELEGATE,
            new FeedbackController(context),
            failoverTts,
            /* removeUnnecessarySpans= */ false);
  }

  @After
  public void tearDown() {
    setAlwaysInterruptPreference(false);
    Performance.getInstance().setComputeStatsEnabled(false);
    Performance.getInstance().clearAllStats();
  }

  private void setAlwaysInterruptPreference(boolean on) {
    SharedPreferencesUtils.getSharedPreferences(context)
        .edit()
        .putBoolean(FailoverTextToSpeech.PREF_ALWAYS_INTERRUPT_SPEECH_KEY, on)
        .commit();
  }

  /** Builds a controller whose failover TTS reads the current always-interrupt preference. */
  private SpeechControllerImpl controllerWithAlwaysInterrupt(boolean on) {
    setAlwaysInterruptPreference(on);
    activeTts = new RecordingFailoverTts(context);
    assertEquals(on, activeTts.isAlwaysInterruptSpeechOn());
    return new SpeechControllerImpl(
        context,
        NO_OP_DELEGATE,
        new FeedbackController(context),
        activeTts,
        /* removeUnnecessarySpans= */ false);
  }

  private void enablePerformanceStats() {
    Performance performance = Performance.getInstance();
    performance.setComputeStatsEnabled(true);
    performance.clearAllStats();
  }

  private void speak(SpeechControllerImpl controller, String text, int queueMode) {
    controller.speak(text, queueMode, 0 /* flags */, null /* speechParams */,
        Performance.EVENT_ID_UNTRACKED);
  }

  @Test
  public void speak_recordsTheUtteranceInHistory() {
    speechController.speak(
        "a real utterance",
        SpeechController.QUEUE_MODE_INTERRUPT,
        0 /* flags */,
        null /* speechParams */,
        Performance.EVENT_ID_UNTRACKED);

    assertEquals(List.of("a real utterance"), failoverTts.spokenTexts);
    assertEquals(List.of("a real utterance"), LastSpokenTextHolder.getSpokenHistory());
  }

  @Test
  public void speak_switchOff_queuedUtteranceWaitsForCurrent() {
    speak(speechController, "first utterance", SpeechController.QUEUE_MODE_QUEUE);
    speak(speechController, "second utterance", SpeechController.QUEUE_MODE_QUEUE);

    assertEquals(List.of("first utterance"), failoverTts.spokenTexts);
  }

  @Test
  public void speak_switchOff_interruptUtteranceInterruptsCurrent() {
    speak(speechController, "first utterance", SpeechController.QUEUE_MODE_QUEUE);
    speak(speechController, "second utterance", SpeechController.QUEUE_MODE_INTERRUPT);

    assertEquals(List.of("first utterance", "second utterance"), failoverTts.spokenTexts);
  }

  @Test
  public void speak_switchOn_queuedUtteranceInterruptsCurrent() {
    SpeechControllerImpl controller = controllerWithAlwaysInterrupt(true);

    speak(controller, "first utterance", SpeechController.QUEUE_MODE_QUEUE);
    speak(controller, "second utterance", SpeechController.QUEUE_MODE_QUEUE);

    assertEquals(List.of("first utterance", "second utterance"), activeTts.spokenTexts);
  }

  @Test
  public void speak_switchOn_uninterruptibleUtteranceStillFinishes() {
    SpeechControllerImpl controller = controllerWithAlwaysInterrupt(true);

    speak(
        controller,
        "uninterruptible announcement",
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH);
    speak(controller, "next utterance", SpeechController.QUEUE_MODE_QUEUE);

    // The announcement is uninterruptible by design, so the next utterance still waits.
    assertEquals(List.of("uninterruptible announcement"), activeTts.spokenTexts);
  }

  @Test
  public void speak_waitQueuedByDesign_recordsByDesignClass() {
    enablePerformanceStats();

    speak(speechController, "first utterance", SpeechController.QUEUE_MODE_QUEUE);
    speak(speechController, "second utterance", SpeechController.QUEUE_MODE_QUEUE);

    Performance performance = Performance.getInstance();
    assertEquals(1, performance.getSpeechWaitBlockerCount(Performance.SPEECH_WAIT_QUEUED_BY_DESIGN));
    assertEquals(
        0, performance.getSpeechWaitBlockerCount(Performance.SPEECH_WAIT_BLOCKER_SHORT_UNINTERRUPTIBLE));
    assertEquals(
        0,
        performance.getSpeechWaitBlockerCount(
            Performance.SPEECH_WAIT_BLOCKER_UNINTERRUPTIBLE_ANNOUNCEMENT));
    assertEquals(0, performance.getSpeechWaitBlockerCount(Performance.SPEECH_WAIT_BLOCKER_OTHER));
  }

  @Test
  public void speak_waitBehindShortUninterruptible_recordsShortClass() {
    enablePerformanceStats();

    speak(
        speechController,
        "short",
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH
            | SpeechController.QUEUE_MODE_CAN_IGNORE_INTERRUPTS);
    speak(speechController, "waiting utterance", SpeechController.QUEUE_MODE_INTERRUPT);

    assertEquals(List.of("short"), failoverTts.spokenTexts);
    assertEquals(
        1,
        Performance.getInstance()
            .getSpeechWaitBlockerCount(Performance.SPEECH_WAIT_BLOCKER_SHORT_UNINTERRUPTIBLE));
  }

  @Test
  public void speak_waitBehindUninterruptibleAnnouncement_recordsAnnouncementClass() {
    enablePerformanceStats();

    speak(
        speechController,
        "an uninterruptible announcement",
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH);
    speak(speechController, "waiting utterance", SpeechController.QUEUE_MODE_INTERRUPT);

    assertEquals(List.of("an uninterruptible announcement"), failoverTts.spokenTexts);
    assertEquals(
        1,
        Performance.getInstance()
            .getSpeechWaitBlockerCount(
                Performance.SPEECH_WAIT_BLOCKER_UNINTERRUPTIBLE_ANNOUNCEMENT));
  }

  @Test
  public void speak_switchOn_recordsNoWait() {
    enablePerformanceStats();
    SpeechControllerImpl controller = controllerWithAlwaysInterrupt(true);

    speak(controller, "first utterance", SpeechController.QUEUE_MODE_QUEUE);
    speak(controller, "second utterance", SpeechController.QUEUE_MODE_QUEUE);

    Performance performance = Performance.getInstance();
    assertEquals(0, performance.getSpeechWaitBlockerCount(Performance.SPEECH_WAIT_QUEUED_BY_DESIGN));
    assertEquals(
        0, performance.getSpeechWaitBlockerCount(Performance.SPEECH_WAIT_BLOCKER_SHORT_UNINTERRUPTIBLE));
    assertEquals(
        0,
        performance.getSpeechWaitBlockerCount(
            Performance.SPEECH_WAIT_BLOCKER_UNINTERRUPTIBLE_ANNOUNCEMENT));
    assertEquals(0, performance.getSpeechWaitBlockerCount(Performance.SPEECH_WAIT_BLOCKER_OTHER));
  }

  @Test
  public void copyTextToClipboard_announcementSpoken_historyKeepsTheCopiedUtterance() {
    LastSpokenTextHolder.setLastSpokenText("hello world");

    assertTrue(speechController.copyTextToClipboard("hello world", Performance.EVENT_ID_UNTRACKED));

    assertEquals(List.of("copied, hello world"), failoverTts.spokenTexts);
    assertEquals(List.of("hello world"), LastSpokenTextHolder.getSpokenHistory());
    assertEquals("hello world", LastSpokenTextHolder.getLastSpokenText());
  }
}
