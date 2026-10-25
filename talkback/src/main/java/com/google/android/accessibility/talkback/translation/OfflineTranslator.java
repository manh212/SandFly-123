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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.List;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

/** On-device translation backed by ML Kit, for the language pair stored in preferences. */
public class OfflineTranslator {

  public interface DownloadCallback {
    void onSuccess();

    void onFailure();
  }

  public interface TranslateCallback {
    void onSuccess(String translatedText);

    void onFailure();
  }

  /** How long the client stays open after the last translation before it is closed. */
  private static final long IDLE_CLOSE_MS = 10 * 60 * 1000L;

  private final Context context;
  private final Handler idleHandler = new Handler(Looper.getMainLooper());
  private final Runnable idleClose = this::closeClientIfIdle;
  private @Nullable Translator translator;
  private @Nullable String translatorSourceLanguage;
  private @Nullable String translatorTargetLanguage;
  private volatile boolean downloadInProgress;
  private int translationsInFlight;

  public OfflineTranslator(Context context) {
    this.context = context.getApplicationContext();
  }

  public String getSourceLanguage() {
    return TranslationPreferences.getSourceLanguage(context);
  }

  public String getTargetLanguage() {
    return TranslationPreferences.getTargetLanguage(context);
  }

  public boolean isDownloadInProgress() {
    return downloadInProgress;
  }

  private synchronized Translator getOrCreateTranslator() {
    String sourceLanguage = getSourceLanguage();
    String targetLanguage = getTargetLanguage();
    if (translator == null
        || !sourceLanguage.equals(translatorSourceLanguage)
        || !targetLanguage.equals(translatorTargetLanguage)) {
      closeClient();
      translator =
          Translation.getClient(
              new TranslatorOptions.Builder()
                  .setSourceLanguage(sourceLanguage)
                  .setTargetLanguage(targetLanguage)
                  .build());
      translatorSourceLanguage = sourceLanguage;
      translatorTargetLanguage = targetLanguage;
    }
    noteActivity();
    return translator;
  }

  /** Restarts the idle countdown; the client is closed once it runs out with nothing in flight. */
  private void noteActivity() {
    idleHandler.removeCallbacks(idleClose);
    idleHandler.postDelayed(idleClose, IDLE_CLOSE_MS);
  }

  private void closeClientIfIdle() {
    synchronized (this) {
      if (translationsInFlight > 0 || downloadInProgress) {
        noteActivity();
        return;
      }
      closeClient();
    }
  }

  private synchronized void closeClient() {
    if (translator != null) {
      translator.close();
      translator = null;
      translatorSourceLanguage = null;
      translatorTargetLanguage = null;
    }
  }

  public void downloadModelIfNeeded(DownloadCallback callback) {
    downloadInProgress = true;
    getOrCreateTranslator()
        .downloadModelIfNeeded(new DownloadConditions.Builder().build())
        .addOnSuccessListener(
            unused -> {
              downloadInProgress = false;
              callback.onSuccess();
            })
        .addOnFailureListener(
            e -> {
              downloadInProgress = false;
              callback.onFailure();
            });
  }

  /** Reports whether the models for both languages of the current pair are on the device. */
  public void isModelDownloaded(Consumer<Boolean> callback) {
    RemoteModelManager modelManager = RemoteModelManager.getInstance();
    Tasks.whenAllSuccess(
            modelManager.isModelDownloaded(
                new TranslateRemoteModel.Builder(getSourceLanguage()).build()),
            modelManager.isModelDownloaded(
                new TranslateRemoteModel.Builder(getTargetLanguage()).build()))
        .addOnSuccessListener(
            results -> {
              boolean downloaded = true;
              for (Object result : (List<?>) results) {
                downloaded &= Boolean.TRUE.equals(result);
              }
              callback.accept(downloaded);
            })
        .addOnFailureListener(e -> callback.accept(false));
  }

  public void translate(String text, TranslateCallback callback) {
    Translator client;
    synchronized (this) {
      translationsInFlight++;
      client = getOrCreateTranslator();
    }
    client
        .translate(text)
        .addOnSuccessListener(
            result -> {
              translationFinished();
              callback.onSuccess(result);
            })
        .addOnFailureListener(
            e -> {
              translationFinished();
              callback.onFailure();
            });
  }

  private void translationFinished() {
    synchronized (this) {
      translationsInFlight--;
    }
    noteActivity();
  }
}
