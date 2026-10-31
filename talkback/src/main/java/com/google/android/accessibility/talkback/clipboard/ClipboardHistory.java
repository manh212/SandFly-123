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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
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
  private static final List<Runnable> listeners = new ArrayList<>();
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());

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

  public static void init(Context context) {
    if (context == null) {
      init((File) null);
      return;
    }
    init(context.getFilesDir());
    SharedPreferences preferences =
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences("clipboard_history", Context.MODE_PRIVATE);
    synchronized (entries) {
      if (preferences.getBoolean("sandfly_history_restored", false)) {
        return;
      }
      String content = preferences.getString("entries", null);
      if (TextUtils.isEmpty(content)) {
        preferences.edit().putBoolean("sandfly_history_restored", true).apply();
        return;
      }
      List<String> previous = new ArrayList<>(entries);
      Deque<String> merged = new ArrayDeque<>();
      try {
        JSONArray stored = new JSONArray(content);
        for (int i = 0; i < stored.length(); i++) {
          String value = stored.optString(i, null);
          if (!TextUtils.isEmpty(value) && !merged.contains(value)) {
            merged.addLast(value);
          }
        }
      } catch (JSONException | RuntimeException e) {
        preferences.edit().putBoolean("sandfly_history_restored", true).apply();
        return;
      }
      for (String value : previous) {
        if (!merged.contains(value)) {
          merged.addLast(value);
        }
      }
      List<String> imported = new ArrayList<>(merged);
      if (!previous.equals(imported)) {
        entries.clear();
        entries.addAll(merged);
        if (!saveLocked()) {
          entries.clear();
          entries.addAll(previous);
          return;
        }
      }
      preferences.edit().putBoolean("sandfly_history_restored", true).apply();
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
    notifyListeners();
  }

  public static void remove(String text) {
    if (text == null) {
      return;
    }
    boolean changed;
    synchronized (entries) {
      changed = entries.remove(text);
      if (changed) {
        saveLocked();
      }
    }
    if (changed) {
      notifyListeners();
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
    boolean changed;
    synchronized (entries) {
      changed = !entries.isEmpty();
      entries.clear();
      saveLocked();
    }
    if (changed) {
      notifyListeners();
    }
  }

  public static void addListener(Runnable listener) {
    synchronized (listeners) {
      if (!listeners.contains(listener)) {
        listeners.add(listener);
      }
    }
  }

  public static void removeListener(Runnable listener) {
    synchronized (listeners) {
      listeners.remove(listener);
    }
  }

  private static void notifyListeners() {
    List<Runnable> snapshot;
    synchronized (listeners) {
      snapshot = new ArrayList<>(listeners);
    }
    if (snapshot.isEmpty()) {
      return;
    }
    mainHandler.post(
        () -> {
          for (Runnable listener : snapshot) {
            listener.run();
          }
        });
  }

  private static boolean saveLocked() {
    if (storeFile == null) {
      return false;
    }
    try {
      JSONArray stored = new JSONArray();
      for (String entry : entries) {
        stored.put(entry);
      }
      Files.write(storeFile.toPath(), stored.toString().getBytes(StandardCharsets.UTF_8));
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }
}
