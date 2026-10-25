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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;

/**
 * Feeds {@link ClipboardHistory} from the system clipboard: every time the primary clip changes,
 * its text is read once, at the change itself, and recorded.
 *
 * <p>Since Android 10 the system lets an app read the clipboard only in limited situations, and
 * an accessibility service is not always one of them; a read the system refuses simply records
 * nothing. Clips the copying app marked sensitive (a password, typically) are never recorded.
 */
public final class ClipboardHistoryCapture {

  /** The extras key apps use to mark a clip sensitive; read by name, it exists on every API. */
  private static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";

  private final ClipboardManager clipboard;
  private final ClipboardManager.OnPrimaryClipChangedListener listener = this::captureCurrent;

  public ClipboardHistoryCapture(Context context) {
    clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
  }

  /** Starts listening for clip changes, and records the clip already there. */
  public void start() {
    if (clipboard == null) {
      return;
    }
    clipboard.addPrimaryClipChangedListener(listener);
    captureCurrent();
  }

  /** Stops listening for clip changes. */
  public void stop() {
    if (clipboard == null) {
      return;
    }
    clipboard.removePrimaryClipChangedListener(listener);
  }

  /** Records the current primary clip's text, if the system lets this service read it. */
  public void captureCurrent() {
    readCurrent(clipboard);
  }

  /** Records the current primary clip's text, if the system lets this app read it. */
  public static void captureCurrent(Context context) {
    readCurrent((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE));
  }

  private static void readCurrent(ClipboardManager clipboard) {
    if (clipboard == null) {
      return;
    }
    ClipData clip;
    try {
      clip = clipboard.getPrimaryClip();
    } catch (RuntimeException e) {
      // The system refused the read; there is nothing to record.
      return;
    }
    if (clip == null || clip.getItemCount() == 0 || isSensitive(clip)) {
      return;
    }
    CharSequence text = clip.getItemAt(0).getText();
    if (text != null) {
      ClipboardHistory.record(text);
    }
  }

  private static boolean isSensitive(ClipData clip) {
    PersistableBundle extras = clip.getDescription().getExtras();
    return extras != null && extras.getBoolean(EXTRA_IS_SENSITIVE, false);
  }
}
