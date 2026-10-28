/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.imagecaption;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class TesseractOcr {

  public static final String DATA_URL_BASE =
      "https://github.com/tesseract-ocr/tessdata_best/raw/main/";

  private static final String TAG = "TesseractOcr";
  private static final String DATA_DIR_NAME = "tesseract";
  private static final String DATA_SUBDIR_NAME = "tessdata";
  private static final String LANGUAGE = "vie+eng";
  private static final String[] DATA_FILE_NAMES = {"vie.traineddata", "eng.traineddata"};
  private static final int CONNECT_TIMEOUT_MS = 30000;
  private static final int READ_TIMEOUT_MS = 60000;

  private static final Object ENGINE_LOCK = new Object();
  private static final long IDLE_TIMEOUT_MS = 180000L;
  private static final Handler IDLE_HANDLER = new Handler(Looper.getMainLooper());
  private static TessBaseAPI engine;
  private static long lastUsedMs;
  private static final Runnable IDLE_RELEASE = TesseractOcr::onIdleTimeout;

  private static void onIdleTimeout() {
    synchronized (ENGINE_LOCK) {
      if (engine == null) {
        return;
      }
      long idleMs = SystemClock.elapsedRealtime() - lastUsedMs;
      if (idleMs >= IDLE_TIMEOUT_MS) {
        engine.recycle();
        engine = null;
      } else {
        IDLE_HANDLER.postDelayed(IDLE_RELEASE, IDLE_TIMEOUT_MS - idleMs);
      }
    }
  }

  public interface DownloadListener {
    void onDownloadFinished(boolean success);
  }

  public static final class TextLine {
    public final String text;
    public final Rect bounds;

    public TextLine(String text, Rect bounds) {
      this.text = text;
      this.bounds = bounds;
    }
  }

  private TesseractOcr() {}

  public static File getDataDir(Context context) {
    return new File(context.getFilesDir(), DATA_DIR_NAME);
  }

  public static File getDataFile(Context context, String fileName) {
    return new File(new File(getDataDir(context), DATA_SUBDIR_NAME), fileName);
  }

  public static boolean isDataDownloaded(Context context) {
    for (String fileName : DATA_FILE_NAMES) {
      File dataFile = getDataFile(context, fileName);
      if (!dataFile.exists() || dataFile.length() == 0) {
        return false;
      }
    }
    return true;
  }

  public static void downloadData(Context context, DownloadListener listener) {
    Context applicationContext = context.getApplicationContext();
    Handler mainHandler = new Handler(Looper.getMainLooper());
    new Thread(
            () -> {
              boolean success = downloadDataInternal(applicationContext);
              mainHandler.post(() -> listener.onDownloadFinished(success));
            })
        .start();
  }

  private static boolean downloadDataInternal(Context context) {
    File dataDir = new File(getDataDir(context), DATA_SUBDIR_NAME);
    if (!dataDir.exists() && !dataDir.mkdirs()) {
      return false;
    }
    for (String fileName : DATA_FILE_NAMES) {
      File dataFile = new File(dataDir, fileName);
      if (dataFile.exists() && dataFile.length() > 0) {
        continue;
      }
      if (!downloadOneFile(dataDir, fileName)) {
        return false;
      }
    }
    return true;
  }

  private static boolean downloadOneFile(File dataDir, String fileName) {
    File tempFile = new File(dataDir, fileName + ".tmp");
    File dataFile = new File(dataDir, fileName);
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(DATA_URL_BASE + fileName).openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        return false;
      }
      try (InputStream input = connection.getInputStream();
          OutputStream output = new FileOutputStream(tempFile)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          output.write(buffer, 0, read);
        }
      }
      if (tempFile.length() == 0) {
        return false;
      }
      if (dataFile.exists() && !dataFile.delete()) {
        return false;
      }
      return tempFile.renameTo(dataFile);
    } catch (IOException e) {
      LogUtils.w(TAG, "Download failed: " + e.getMessage());
      return false;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
      if (tempFile.exists()) {
        tempFile.delete();
      }
    }
  }

  @Nullable
  public static String recognizeText(Context context, Bitmap bitmap) {
    synchronized (ENGINE_LOCK) {
      try {
        if (engine == null) {
          engine = new TessBaseAPI();
          if (!engine.init(getDataDir(context).getAbsolutePath(), LANGUAGE)) {
            engine.recycle();
            engine = null;
            return null;
          }
        }
        engine.setImage(bitmap);
        String text = engine.getUTF8Text();
        lastUsedMs = SystemClock.elapsedRealtime();
        IDLE_HANDLER.removeCallbacks(IDLE_RELEASE);
        IDLE_HANDLER.postDelayed(IDLE_RELEASE, IDLE_TIMEOUT_MS);
        return text;
      } catch (RuntimeException e) {
        LogUtils.w(TAG, "Recognition failed: " + e.getMessage());
        return null;
      }
    }
  }

  @Nullable
  public static List<TextLine> recognizeWords(Context context, Bitmap bitmap) {
    synchronized (ENGINE_LOCK) {
      try {
        if (engine == null) {
          engine = new TessBaseAPI();
          if (!engine.init(getDataDir(context).getAbsolutePath(), LANGUAGE)) {
            engine.recycle();
            engine = null;
            return null;
          }
        }
        engine.setImage(bitmap);
        if (engine.getUTF8Text() == null) {
          return null;
        }
        String hocr = engine.getHOCRText(0);
        if (hocr == null) {
          return null;
        }
        List<TextLine> words = parseHocrWords(hocr);
        if (words.isEmpty()) {
          words = parseHocrLines(hocr);
        }
        lastUsedMs = SystemClock.elapsedRealtime();
        IDLE_HANDLER.removeCallbacks(IDLE_RELEASE);
        IDLE_HANDLER.postDelayed(IDLE_RELEASE, IDLE_TIMEOUT_MS);
        return words;
      } catch (RuntimeException e) {
        LogUtils.w(TAG, "Word recognition failed: " + e.getMessage());
        return null;
      }
    }
  }

  private static List<TextLine> parseHocrWords(String hocr) {
    List<TextLine> words = new ArrayList<>();
    int pos = 0;
    while (true) {
      int wordStart = hocr.indexOf("ocrx_word", pos);
      if (wordStart < 0) {
        break;
      }
      int tagEnd = hocr.indexOf('>', wordStart);
      if (tagEnd < 0) {
        break;
      }
      Rect bounds = parseHocrBbox(hocr.substring(wordStart, tagEnd));
      int wordEnd = hocr.indexOf("</span", tagEnd);
      if (wordEnd < 0) {
        break;
      }
      String word =
          unescapeHocr(hocr.substring(tagEnd + 1, wordEnd).replaceAll("<[^>]*>", "")).trim();
      pos = wordEnd + 7;
      if (bounds != null && !bounds.isEmpty() && !word.isEmpty()) {
        words.add(new TextLine(word, bounds));
      }
    }
    return words;
  }

  private static List<TextLine> parseHocrLines(String hocr) {
    List<TextLine> lines = new ArrayList<>();
    int searchFrom = 0;
    while (true) {
      int lineStart = hocr.indexOf("ocr_line", searchFrom);
      if (lineStart < 0) {
        break;
      }
      int tagEnd = hocr.indexOf('>', lineStart);
      if (tagEnd < 0) {
        break;
      }
      Rect bounds = parseHocrBbox(hocr.substring(lineStart, tagEnd));
      int depth = 1;
      int pos = tagEnd + 1;
      int contentEnd = -1;
      while (pos < hocr.length()) {
        int tagStart = hocr.indexOf('<', pos);
        if (tagStart < 0) {
          break;
        }
        int tagClose = hocr.indexOf('>', tagStart);
        if (tagClose < 0) {
          break;
        }
        if (hocr.startsWith("</span", tagStart)) {
          depth--;
          if (depth == 0) {
            contentEnd = tagStart;
            break;
          }
        } else if (hocr.startsWith("<span", tagStart)) {
          depth++;
        }
        pos = tagClose + 1;
      }
      if (contentEnd < 0) {
        break;
      }
      String text = extractHocrLineText(hocr.substring(tagEnd + 1, contentEnd));
      searchFrom = contentEnd + 7;
      if (bounds != null && !bounds.isEmpty() && text != null && !text.trim().isEmpty()) {
        lines.add(new TextLine(text, bounds));
      }
    }
    return lines;
  }

  private static Rect parseHocrBbox(String tag) {
    int bboxIndex = tag.indexOf("bbox");
    if (bboxIndex < 0) {
      return null;
    }
    String[] parts = tag.substring(bboxIndex + 4).trim().split("[\\s;]+");
    if (parts.length < 4) {
      return null;
    }
    try {
      return new Rect(
          Integer.parseInt(parts[0]),
          Integer.parseInt(parts[1]),
          Integer.parseInt(parts[2]),
          Integer.parseInt(parts[3]));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String extractHocrLineText(String content) {
    StringBuilder builder = new StringBuilder();
    int pos = 0;
    while (true) {
      int wordStart = content.indexOf("ocrx_word", pos);
      if (wordStart < 0) {
        break;
      }
      int tagEnd = content.indexOf('>', wordStart);
      if (tagEnd < 0) {
        break;
      }
      int wordEnd = content.indexOf("</span", tagEnd);
      if (wordEnd < 0) {
        break;
      }
      String word = unescapeHocr(content.substring(tagEnd + 1, wordEnd).replaceAll("<[^>]*>", "")).trim();
      if (!word.isEmpty()) {
        if (builder.length() > 0) {
          builder.append(' ');
        }
        builder.append(word);
      }
      pos = wordEnd + 7;
    }
    if (builder.length() > 0) {
      return builder.toString();
    }
    return unescapeHocr(content.replaceAll("<[^>]*>", "")).trim().replaceAll("\\s+", " ");
  }

  private static String unescapeHocr(String text) {
    return text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&");
  }

  public static void release() {
    synchronized (ENGINE_LOCK) {
      IDLE_HANDLER.removeCallbacks(IDLE_RELEASE);
      if (engine != null) {
        engine.recycle();
        engine = null;
      }
    }
  }
}
