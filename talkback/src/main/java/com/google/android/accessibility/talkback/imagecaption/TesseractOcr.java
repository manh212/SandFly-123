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
import android.os.Handler;
import android.os.Looper;
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

public final class TesseractOcr {

  public static final String DATA_URL =
      "https://github.com/tesseract-ocr/tessdata_best/raw/main/vie.traineddata";

  private static final String TAG = "TesseractOcr";
  private static final String DATA_DIR_NAME = "tesseract";
  private static final String DATA_SUBDIR_NAME = "tessdata";
  private static final String LANGUAGE = "vie";
  private static final String DATA_FILE_NAME = "vie.traineddata";
  private static final int CONNECT_TIMEOUT_MS = 30000;
  private static final int READ_TIMEOUT_MS = 60000;

  private static final Object ENGINE_LOCK = new Object();
  private static TessBaseAPI engine;

  public interface DownloadListener {
    void onDownloadFinished(boolean success);
  }

  private TesseractOcr() {}

  public static File getDataDir(Context context) {
    return new File(context.getFilesDir(), DATA_DIR_NAME);
  }

  public static File getDataFile(Context context) {
    return new File(new File(getDataDir(context), DATA_SUBDIR_NAME), DATA_FILE_NAME);
  }

  public static boolean isDataDownloaded(Context context) {
    File dataFile = getDataFile(context);
    return dataFile.exists() && dataFile.length() > 0;
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
    File tempFile = new File(dataDir, DATA_FILE_NAME + ".tmp");
    File dataFile = new File(dataDir, DATA_FILE_NAME);
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(DATA_URL).openConnection();
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
        return engine.getUTF8Text();
      } catch (RuntimeException e) {
        LogUtils.w(TAG, "Recognition failed: " + e.getMessage());
        return null;
      }
    }
  }

  public static void release() {
    synchronized (ENGINE_LOCK) {
      if (engine != null) {
        engine.recycle();
        engine = null;
      }
    }
  }
}
