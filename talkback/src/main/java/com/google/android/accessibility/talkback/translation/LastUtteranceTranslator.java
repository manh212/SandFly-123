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
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.output.SpeechControllerImpl;

public class LastUtteranceTranslator {
  private final TranslateCommand command;

  public LastUtteranceTranslator(Context context, SpeechControllerImpl speaker) {
    command = new TranslateCommand(context, speaker);
  }

  public void translateLastUtterance(EventId eventId) {
    command.translateLast();
  }

  public void translateSavedUtterance(EventId eventId) {
    command.translateSaved();
  }

  public void shutdown() {
    command.shutdown();
  }
}
