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
import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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
    SpeechControllerImpl.navigationSpeechInterruptsAnnouncements = true;
    SpeechControllerImpl.windowFocusSpeechInterruptsAnnouncements = true;
    SpeechControllerImpl.speechWaitCapEnabled = true;
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

  /** Speaks as the answer to the user's own swipe, which is what focus speech arrives as. */
  private void speakGesture(SpeechControllerImpl controller, String text) {
    controller.speak(
        text,
        SpeechController.QUEUE_MODE_FLUSH_ALL,
        0 /* flags */,
        null /* speechParams */,
        new EventId(0, Performance.EVENT_TYPE_GESTURE, 1));
  }

  @Test
  public void gestureSpeech_interruptsUninterruptibleAnnouncement() {
    enablePerformanceStats();

    speak(
        speechController,
        "an uninterruptible announcement",
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH);
    speakGesture(speechController, "swipe target");

    assertEquals(
        List.of("an uninterruptible announcement", "swipe target"), failoverTts.spokenTexts);
    assertEquals(1, Performance.getInstance().getNavigationSpeechInterruptCount());
  }

  @Test
  public void gestureSpeech_switchOff_waitsBehindAnnouncement() {
    SpeechControllerImpl.navigationSpeechInterruptsAnnouncements = false;

    speak(
        speechController,
        "an uninterruptible announcement",
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH);
    speakGesture(speechController, "swipe target");

    assertEquals(List.of("an uninterruptible announcement"), failoverTts.spokenTexts);
  }

  @Test
  public void gestureSpeech_neverInterruptsGloballyUninterruptible() {
    speak(speechController, "critical utterance", SpeechController.QUEUE_MODE_GLOBALLY_UNINTERRUPTED);
    speakGesture(speechController, "swipe target");

    assertEquals(List.of("critical utterance"), failoverTts.spokenTexts);
  }

  @Test
  public void nonGestureSpeech_stillWaitsBehindAnnouncement() {
    speak(
        speechController,
        "an uninterruptible announcement",
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH);
    speak(speechController, "waiting utterance", SpeechController.QUEUE_MODE_FLUSH_ALL);

    assertEquals(List.of("an uninterruptible announcement"), failoverTts.spokenTexts);
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
  public void waitCap_cutsGloballyUninterruptibleBlocker_afterCap() {
    enablePerformanceStats();

    speak(speechController, "a globally protected utterance", SpeechController.QUEUE_MODE_GLOBALLY_UNINTERRUPTED);
    speakGesture(speechController, "the swiped item");
    assertEquals(List.of("a globally protected utterance"), failoverTts.spokenTexts);

    Robolectric.getForegroundThreadScheduler().advanceBy(200, TimeUnit.MILLISECONDS);

    assertEquals(
        List.of("a globally protected utterance", "the swiped item"), failoverTts.spokenTexts);
    assertEquals(1, Performance.getInstance().getSpeechWaitCapFlushCount());
    assertEquals(0, Performance.getInstance().getSpeechWaitCapHeldCount());
  }

  @Test
  public void waitCap_neverCutsExemptSpeech() {
    enablePerformanceStats();

    speechController.speak(
        "the speaking clock",
        SpeechController.QUEUE_MODE_GLOBALLY_UNINTERRUPTED,
        FeedbackItem.FLAG_EXEMPT_FROM_SPEECH_WAIT_CAP,
        null /* speechParams */,
        null /* eventId */);
    speakGesture(speechController, "the swiped item");

    Robolectric.getForegroundThreadScheduler().advanceBy(400, TimeUnit.MILLISECONDS);

    assertEquals(List.of("the speaking clock"), failoverTts.spokenTexts);
    assertEquals(0, Performance.getInstance().getSpeechWaitCapFlushCount());
    assertEquals(1, Performance.getInstance().getSpeechWaitCapHeldCount());
  }

  @Test
  public void waitCap_switchOff_leavesTheWaitAlone() {
    SpeechControllerImpl.speechWaitCapEnabled = false;
    enablePerformanceStats();

    speak(speechController, "a globally protected utterance", SpeechController.QUEUE_MODE_GLOBALLY_UNINTERRUPTED);
    speakGesture(speechController, "the swiped item");

    Robolectric.getForegroundThreadScheduler().advanceBy(400, TimeUnit.MILLISECONDS);

    assertEquals(List.of("a globally protected utterance"), failoverTts.spokenTexts);
    assertEquals(0, Performance.getInstance().getSpeechWaitCapFlushCount());
  }

  @Test
  public void waitCap_waiterGoneBeforeCap_noFlushNoCrash() {
    enablePerformanceStats();

    speak(speechController, "a globally protected utterance", SpeechController.QUEUE_MODE_GLOBALLY_UNINTERRUPTED);
    speakGesture(speechController, "the swiped item");
    speechController.interrupt(true);

    Robolectric.getForegroundThreadScheduler().advanceBy(400, TimeUnit.MILLISECONDS);

    assertEquals(List.of("a globally protected utterance"), failoverTts.spokenTexts);
    assertEquals(0, Performance.getInstance().getSpeechWaitCapFlushCount());
  }

  /** Speaks as the title of a window the user just opened. */
  private void speakWindowChange(SpeechControllerImpl controller, String text) {
    controller.speak(
        text,
        SpeechController.QUEUE_MODE_FLUSH_ALL,
        0 /* flags */,
        null /* speechParams */,
        new EventId(
            0, Performance.EVENT_TYPE_ACCESSIBILITY, AccessibilityEvent.TYPE_WINDOWS_CHANGED));
  }

  /** Speaks as the item focus landed on, flushing the way focus speech does. */
  private void speakFocusFlush(SpeechControllerImpl controller, String text) {
    controller.speak(
        text,
        SpeechController.QUEUE_MODE_FLUSH_ALL,
        0 /* flags */,
        null /* speechParams */,
        new EventId(
            0,
            Performance.EVENT_TYPE_ACCESSIBILITY,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
  }

  @Test
  public void windowChangeSpeech_interruptsUninterruptibleAnnouncement() {
    enablePerformanceStats();

    speakAnnouncement(speechController, "an uninterruptible announcement");
    speakWindowChange(speechController, "the new window");

    assertEquals(
        List.of("an uninterruptible announcement", "the new window"), failoverTts.spokenTexts);
    assertEquals(1, Performance.getInstance().getWindowFocusSpeechInterruptCount());
    assertEquals(0, Performance.getInstance().getNavigationSpeechInterruptCount());
  }

  @Test
  public void focusSpeech_interruptsUninterruptibleAnnouncement() {
    enablePerformanceStats();

    speakAnnouncement(speechController, "an uninterruptible announcement");
    speakFocusFlush(speechController, "the focused item");

    assertEquals(
        List.of("an uninterruptible announcement", "the focused item"), failoverTts.spokenTexts);
    assertEquals(1, Performance.getInstance().getWindowFocusSpeechInterruptCount());
  }

  @Test
  public void windowChangeSpeech_switchOff_waitsBehindAnnouncement() {
    SpeechControllerImpl.windowFocusSpeechInterruptsAnnouncements = false;

    speakAnnouncement(speechController, "an uninterruptible announcement");
    speakWindowChange(speechController, "the new window");

    assertEquals(List.of("an uninterruptible announcement"), failoverTts.spokenTexts);
  }

  @Test
  public void windowChangeSpeech_neverInterruptsGloballyUninterruptible() {
    speak(speechController, "critical utterance", SpeechController.QUEUE_MODE_GLOBALLY_UNINTERRUPTED);
    speakWindowChange(speechController, "the new window");

    assertEquals(List.of("critical utterance"), failoverTts.spokenTexts);
  }

  /** Speaks as an announcement event, the kind of speech that blocks navigation answers. */
  private void speakAnnouncement(SpeechControllerImpl controller, String text) {
    controller.speak(
        text,
        SpeechController.QUEUE_MODE_UNINTERRUPTIBLE_BY_NEW_SPEECH,
        0 /* flags */,
        null /* speechParams */,
        new EventId(0, Performance.EVENT_TYPE_ACCESSIBILITY, AccessibilityEvent.TYPE_ANNOUNCEMENT));
  }

  /** Speaks as the first focus of a window, which queues behind whatever is speaking. */
  private void speakFocus(SpeechControllerImpl controller, String text) {
    controller.speak(
        text,
        SpeechController.QUEUE_MODE_QUEUE,
        0 /* flags */,
        null /* speechParams */,
        new EventId(
            0,
            Performance.EVENT_TYPE_ACCESSIBILITY,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
  }

  @Test
  public void longWait_isProfiledNamingBlockerAndWaiter() {
    enablePerformanceStats();

    speakAnnouncement(speechController, "a long announcement");
    speakFocus(speechController, "the focused item");
    Robolectric.getForegroundThreadScheduler().advanceBy(250, TimeUnit.MILLISECONDS);
    // A swipe cuts the announcement, and the waiting focus speech is spoken next.
    speakGesture(speechController, "swipe target");

    assertEquals(
        List.of("a long announcement", "the focused item"), failoverTts.spokenTexts);
    List<String> profiles = Performance.getInstance().speechWaitProfileLines();
    assertEquals(1, profiles.size());
    assertTrue(profiles.get(0), profiles.get(0).contains("focus waited behind announcement"));
    assertTrue(profiles.get(0), profiles.get(0).contains("(uninterruptible)"));
  }

  @Test
  public void shortWait_isCountedButNotProfiled() {
    enablePerformanceStats();

    speakAnnouncement(speechController, "a long announcement");
    speakFocus(speechController, "the focused item");
    speakGesture(speechController, "swipe target");

    assertEquals(
        List.of("a long announcement", "the focused item"), failoverTts.spokenTexts);
    assertTrue(Performance.getInstance().speechWaitProfileLines().isEmpty());
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
