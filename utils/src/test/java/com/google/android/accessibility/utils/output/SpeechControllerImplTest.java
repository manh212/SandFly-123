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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  private RecordingFailoverTts failoverTts;
  private SpeechControllerImpl speechController;

  @Before
  public void setUp() {
    Context context = RuntimeEnvironment.getApplication();
    LastSpokenTextHolder.clearSpokenHistoryForTest();
    failoverTts = new RecordingFailoverTts(context);
    speechController =
        new SpeechControllerImpl(
            context,
            NO_OP_DELEGATE,
            new FeedbackController(context),
            failoverTts,
            /* removeUnnecessarySpans= */ false);
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
  public void copyTextToClipboard_announcementSpoken_historyKeepsTheCopiedUtterance() {
    LastSpokenTextHolder.setLastSpokenText("hello world");

    assertTrue(speechController.copyTextToClipboard("hello world", Performance.EVENT_ID_UNTRACKED));

    assertEquals(List.of("copied, hello world"), failoverTts.spokenTexts);
    assertEquals(List.of("hello world"), LastSpokenTextHolder.getSpokenHistory());
    assertEquals("hello world", LastSpokenTextHolder.getLastSpokenText());
  }
}
