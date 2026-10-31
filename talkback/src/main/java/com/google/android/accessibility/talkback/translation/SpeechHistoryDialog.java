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

import android.app.AlertDialog;
import android.content.Context;
import android.view.WindowManager;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.output.LastSpokenTextHolder;
import com.google.android.accessibility.utils.output.SpeechControllerImpl;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.List;

/** Shows the recent spoken utterances, newest first; tapping one copies it to the clipboard. */
public class SpeechHistoryDialog {

  private static final String TAG = "SpeechHistory";

  private final Context context;
  private final SpeechControllerImpl speaker;

  public SpeechHistoryDialog(Context context, SpeechControllerImpl speaker) {
    this.context = context;
    this.speaker = speaker;
  }

  public void show(EventId eventId) {
    List<String> history = LastSpokenTextHolder.getSpokenHistory();
    LogUtils.d(TAG, "SHOW_HISTORY received, entries=%d", history.size());
    if (history.isEmpty()) {
      AlertDialog emptyDialog =
          new AlertDialog.Builder(context)
              .setTitle(R.string.title_speech_history)
              .setMessage(R.string.speech_history_empty)
              .setPositiveButton(R.string.talkback_menu_close_button_text, null)
              .create();
      showDialog(emptyDialog, 0);
      return;
    }
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(R.string.title_speech_history)
            .setItems(
                history.toArray(new CharSequence[0]),
                (clicked, which) -> speaker.copyTextToClipboard(history.get(which), eventId))
            .create();
    showDialog(dialog, history.size());
  }

  private void showDialog(AlertDialog dialog, int entryCount) {
    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    dialog.setOnDismissListener(ignored -> LogUtils.d(TAG, "History dialog dismissed"));
    try {
      dialog.show();
      LogUtils.d(TAG, "History dialog show completed, showing=%s, entries=%d", dialog.isShowing(), entryCount);
    } catch (RuntimeException e) {
      LogUtils.e(TAG, "History dialog show failed: %s", e);
    }
  }
}
