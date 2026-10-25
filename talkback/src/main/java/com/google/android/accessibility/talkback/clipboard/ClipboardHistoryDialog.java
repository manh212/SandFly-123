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

package com.google.android.accessibility.talkback.clipboard;

import android.app.AlertDialog;
import android.content.Context;
import android.view.WindowManager;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.utils.ClipboardUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;
import com.google.android.accessibility.utils.output.SpeechControllerImpl;
import java.util.List;

/** Shows the copied texts, newest first; tapping one puts it back on the clipboard. */
public class ClipboardHistoryDialog {

  private final Context context;
  private final SpeechControllerImpl speaker;

  public ClipboardHistoryDialog(Context context, SpeechControllerImpl speaker) {
    this.context = context;
    this.speaker = speaker;
  }

  public void show(EventId eventId) {
    // A clip copied while the change listener could not read it may be readable now, with this
    // dialog's window in front; recording it here keeps the list from missing it.
    ClipboardHistoryCapture.captureCurrent(context);
    List<String> history = ClipboardHistory.getEntries();
    if (history.isEmpty()) {
      speaker.speak(
          context.getString(R.string.clipboard_history_empty),
          eventId,
          SpeakOptions.create()
              .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
              .setFlags(FeedbackItem.FLAG_NO_HISTORY | FeedbackItem.FLAG_FORCE_FEEDBACK_ALL));
      return;
    }
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(R.string.title_clipboard_history)
            .setItems(
                history.toArray(new CharSequence[0]),
                (clicked, which) -> ClipboardUtils.copyToClipboard(context, history.get(which)))
            .create();
    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    dialog.show();
  }
}
