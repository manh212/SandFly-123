package com.google.android.accessibility.talkback.clipboard;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

public final class FavoriteTexts {
  private static final List<String> entries = new ArrayList<>();
  private static AtomicFile store;
  private static boolean readable;

  private FavoriteTexts() {}

  public static synchronized void init(File directory) {
    entries.clear();
    store = directory == null ? null : new AtomicFile(new File(directory, "favorite-texts.json"));
    readable = store != null;
    if (store == null) {
      return;
    }
    try {
      JSONArray values = new JSONArray(new String(store.readFully(), StandardCharsets.UTF_8));
      for (int i = 0; i < values.length(); i++) {
        String value = values.optString(i, "");
        if (!value.trim().isEmpty() && !entries.contains(value)) {
          entries.add(value);
        }
      }
    } catch (FileNotFoundException e) {
      readable = !store.getBaseFile().exists();
    } catch (IOException | JSONException | RuntimeException e) {
      entries.clear();
      readable = false;
    }
  }

  public static synchronized List<String> getEntries() {
    return new ArrayList<>(entries);
  }

  public static synchronized boolean add(CharSequence text) {
    if (text == null || text.toString().trim().isEmpty()) {
      return false;
    }
    String value = text.toString();
    if (entries.contains(value)) {
      return true;
    }
    List<String> updated = new ArrayList<>(entries);
    updated.add(0, value);
    return save(updated);
  }

  public static synchronized boolean remove(String text) {
    List<String> updated = new ArrayList<>(entries);
    updated.remove(text);
    return save(updated);
  }

  public static synchronized boolean clear() {
    return save(new ArrayList<>());
  }

  private static boolean save(List<String> updated) {
    if (store == null || !readable) {
      return false;
    }
    FileOutputStream output = null;
    try {
      JSONArray values = new JSONArray();
      for (String entry : updated) {
        values.put(entry);
      }
      byte[] data = values.toString().getBytes(StandardCharsets.UTF_8);
      output = store.startWrite();
      output.write(data);
      store.finishWrite(output);
      output = null;
      if (!Arrays.equals(data, store.readFully())) {
        return false;
      }
      entries.clear();
      entries.addAll(updated);
      return true;
    } catch (IOException | RuntimeException e) {
      if (output != null) {
        store.failWrite(output);
      }
      return false;
    }
  }
}
