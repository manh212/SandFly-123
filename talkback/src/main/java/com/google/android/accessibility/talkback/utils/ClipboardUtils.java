/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.google.android.accessibility.talkback.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import com.google.android.accessibility.talkback.clipboard.ClipboardHistory;
import java.util.List;

/** Utility class to copy text to clipboard. */
public final class ClipboardUtils {

  /**
   * Copies {@code text} to the clipboard.
   *
   * @return false if the given text is null or empty.
   */
  public static boolean copyToClipboard(Context context, CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return false;
    }

    return ClipboardHistory.copy(context, text);
  }

  /** Appends {@code text} to the current plain-text clipboard contents. */
  public static boolean appendToClipboard(Context context, CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return false;
    }

    final ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null) {
      return false;
    }

    String previousText = "";
    try {
      boolean hasPrimaryClip = clipboard.hasPrimaryClip();
      ClipData clipData = clipboard.getPrimaryClip();
      if (clipData != null) {
        StringBuilder existingText = new StringBuilder();
        for (int i = 0; i < clipData.getItemCount(); i++) {
          CharSequence itemText = clipData.getItemAt(i).coerceToText(context);
          if (!TextUtils.isEmpty(itemText)) {
            if (existingText.length() > 0) {
              existingText.append('\n');
            }
            existingText.append(itemText);
          }
        }
        previousText = existingText.toString();
      } else if (hasPrimaryClip) {
        // Some Android versions deny a background accessibility service's clipboard read.
        previousText = latestHistoryText();
      }
    } catch (RuntimeException e) {
      // Preserve text captured by the clipboard history if Android denies this read.
      previousText = latestHistoryText();
    }

    CharSequence combinedText =
        TextUtils.isEmpty(previousText) ? text : previousText + "\n" + text;
    return copyToClipboard(clipboard, combinedText);
  }

  /**
   * Copies {@code text} to the clipboard.
   *
   * @return false if the given text is null or empty.
   */
  public static boolean copyToClipboard(ClipboardManager clipboard, CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return false;
    }

    ClipData clipData = ClipData.newPlainText(null, text);
    clipboard.setPrimaryClip(clipData);
    ClipboardHistory.record(text);
    return true;
  }

  public static boolean copyToClipboardWithoutRecording(Context context, CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return false;
    }

    final ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null) {
      return false;
    }
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
    return true;
  }

  private static String latestHistoryText() {
    List<String> entries = ClipboardHistory.getEntries();
    return entries.isEmpty() ? "" : entries.get(0);
  }
}
