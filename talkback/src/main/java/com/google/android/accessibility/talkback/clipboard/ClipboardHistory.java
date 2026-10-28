/*
 * Copyright (C) 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

public final class ClipboardHistory {

  private static final String STORE_FILE_NAME = "clipboard-history.json";

  private static final Deque<String> entries = new ArrayDeque<>();

  private static File storeFile;

  private ClipboardHistory() {}

  public static void init(File directory) {
    synchronized (entries) {
      storeFile = null;
      if (directory == null) {
        return;
      }
      try {
        storeFile = new File(directory, STORE_FILE_NAME);
        entries.clear();
        if (storeFile.exists()) {
          String content = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
          JSONArray stored = new JSONArray(content);
          for (int i = 0; i < stored.length(); i++) {
            String value = stored.optString(i, null);
            if (value != null) {
              entries.addLast(value);
            }
          }
        }
      } catch (IOException | JSONException | RuntimeException e) {
        entries.clear();
        storeFile = null;
      }
    }
  }

  /** Records {@code text} as the newest entry, moving an existing copy of it to the front. */
  public static void record(CharSequence text) {
    if (TextUtils.isEmpty(text)) {
      return;
    }
    String value = text.toString();
    synchronized (entries) {
      entries.remove(value);
      entries.addFirst(value);
      saveLocked();
    }
  }

  public static void remove(String text) {
    if (text == null) {
      return;
    }
    synchronized (entries) {
      if (entries.remove(text)) {
        saveLocked();
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
      saveLocked();
    }
  }

  private static void saveLocked() {
    if (storeFile == null) {
      return;
    }
    try {
      JSONArray stored = new JSONArray();
      for (String entry : entries) {
        stored.put(entry);
      }
      Files.write(storeFile.toPath(), stored.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
    }
  }
}
