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

package com.google.android.accessibility.talkback.translation;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.StringRes;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.LastSpokenTextHolder;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;
import com.google.android.accessibility.utils.output.SpeechControllerImpl;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Translates the last spoken utterance with the offline translator and speaks the result. */
public class LastUtteranceTranslator {

  private final Context context;
  private final SpeechControllerImpl speaker;
  private final OfflineTranslator translator;

  public LastUtteranceTranslator(Context context, SpeechControllerImpl speaker) {
    this.context = context;
    this.speaker = speaker;
    this.translator = new OfflineTranslator(context);
  }

  public void translateLastUtterance(EventId eventId) {
    translate(LastSpokenTextHolder.getLastSpokenText(), eventId);
  }

  public void translateSavedUtterance(EventId eventId) {
    translate(LastSpokenTextHolder.getSavedSpokenText(), eventId);
  }

  private void translate(@Nullable String text, EventId eventId) {
    if (TextUtils.isEmpty(text)) {
      announce(R.string.offline_translation_nothing_to_translate, eventId);
      return;
    }
    translator.isModelDownloaded(
        downloaded -> {
          if (!downloaded) {
            announce(R.string.offline_translation_pack_not_downloaded_announcement, eventId);
            return;
          }
          translator.translate(
              text,
              new OfflineTranslator.TranslateCallback() {
                @Override
                public void onSuccess(String translatedText) {
                  speak(translatedText, eventId);
                }

                @Override
                public void onFailure() {
                  announce(R.string.offline_translation_failed, eventId);
                }
              });
        });
  }

  private void announce(@StringRes int textResId, EventId eventId) {
    speak(context.getString(textResId), eventId);
  }

  private void speak(CharSequence text, EventId eventId) {
    speaker.speak(
        text,
        eventId,
        SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
            .setFlags(FeedbackItem.FLAG_NO_HISTORY | FeedbackItem.FLAG_FORCE_FEEDBACK_ALL));
  }
}
