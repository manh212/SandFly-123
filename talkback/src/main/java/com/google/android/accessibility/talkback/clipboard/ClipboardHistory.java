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

import android.text.TextUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The texts the user has copied, newest first. Kept in memory only, like the speech history:
 * nothing is written to disk, and the list is gone when the service restarts.
 */
public final class ClipboardHistory {

  private static final int MAX_ENTRIES = 50;

  private static final Deque<String> entries = new ArrayDeque<>();

  private ClipboardHistory() {}

  /** Records {@code text} as the newest entry, moving an existing copy of it to the front. */
  public static void record(CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return;
    }
    String value = text.toString();
    synchronized (entries) {
      entries.remove(value);
      entries.addFirst(value);
      while (entries.size() > MAX_ENTRIES) {
        entries.removeLast();
      }
    }
  }

  /** The recorded texts, newest first. */
  public static List<String> getEntries() {
    synchronized (entries) {
      return new ArrayList<>(entries);
    }
  }

  /** Forgets every recorded text. */
  public static void clear() {
    synchronized (entries) {
      entries.clear();
    }
  }
}
