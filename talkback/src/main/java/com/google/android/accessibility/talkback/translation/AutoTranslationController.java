package com.google.android.accessibility.talkback.translation;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class AutoTranslationController {

  private static final String TAG = "AutoTranslation";
  private static boolean enabled;
  private static @Nullable TranslationClient translator;
  private static final ArrayDeque<PendingTranslation> pending = new ArrayDeque<>();
  private static @Nullable PendingTranslation translating;
  private static int dispatchDepth;
  private static int toggleGeneration;

  interface TranslationClient {
    void translate(String text, OfflineTranslator.TranslateCallback callback);
  }

  private static final class PendingTranslation {
    final String original;
    final Consumer<String> callback;
    boolean completed;

    PendingTranslation(String original, Consumer<String> callback) {
      this.original = original;
      this.callback = callback;
    }
  }

  private AutoTranslationController() {
    throw new AssertionError();
  }

  public static synchronized boolean isEnabled() {
    return enabled;
  }

  public static void toggle(Context context, Consumer<Boolean> result) {
    final int requestGeneration;
    final boolean turningOff;
    synchronized (AutoTranslationController.class) {
      if (enabled) {
        enabled = false;
        turningOff = true;
        requestGeneration = ++toggleGeneration;
      } else {
        turningOff = false;
        requestGeneration = ++toggleGeneration;
      }
    }
    if (turningOff) {
      cancelPending(true);
      result.accept(false);
      return;
    }
    OfflineTranslator client = new OfflineTranslator(context.getApplicationContext());
    synchronized (AutoTranslationController.class) {
      if (requestGeneration != toggleGeneration) {
        return;
      }
      translator = client::translate;
    }
    client.isModelDownloaded(
        downloaded -> {
          synchronized (AutoTranslationController.class) {
            if (requestGeneration != toggleGeneration) {
              return;
            }
            if (downloaded) {
              enabled = true;
            }
          }
          result.accept(downloaded);
        });
  }

  public static void translate(CharSequence text, Consumer<String> onTranslated) {
    String original = text == null ? "" : text.toString();
    synchronized (AutoTranslationController.class) {
      if (!enabled || translator == null || TextUtils.isEmpty(text)) {
        onTranslated.accept(original);
        return;
      }
      pending.add(new PendingTranslation(original, onTranslated));
    }
    startNext();
  }

  public static void cancelPending() {
    cancelPending(false);
  }

  static void setTranslatorForTesting(@Nullable TranslationClient testTranslator) {
    synchronized (AutoTranslationController.class) {
      enabled = testTranslator != null;
      translator = testTranslator;
      pending.clear();
      translating = null;
      dispatchDepth = 0;
      toggleGeneration++;
    }
  }

  private static void cancelPending(boolean speakOriginal) {
    List<PendingTranslation> cancelled = new ArrayList<>();
    synchronized (AutoTranslationController.class) {
      if (translating != null && !translating.completed) {
        translating.completed = true;
        cancelled.add(translating);
      }
      translating = null;
      while (!pending.isEmpty()) {
        PendingTranslation request = pending.remove();
        request.completed = true;
        cancelled.add(request);
      }
      dispatchDepth++;
    }
    try {
      for (PendingTranslation request : cancelled) {
        try {
          request.callback.accept(speakOriginal ? request.original : "");
        } catch (RuntimeException e) {
          LogUtils.w(TAG, "Could not finish interrupted translation: %s", e);
        }
      }
    } finally {
      synchronized (AutoTranslationController.class) {
        dispatchDepth--;
      }
      startNext();
    }
  }

  private static void startNext() {
    PendingTranslation request;
    TranslationClient client;
    synchronized (AutoTranslationController.class) {
      if (dispatchDepth > 0
          || translating != null
          || pending.isEmpty()
          || !enabled
          || translator == null) {
        return;
      }
      translating = request = pending.remove();
      client = translator;
    }
    try {
      client.translate(
          request.original,
          new OfflineTranslator.TranslateCallback() {
            @Override
            public void onSuccess(String translatedText) {
              complete(
                  request,
                  TextUtils.isEmpty(translatedText) ? request.original : translatedText);
            }

            @Override
            public void onFailure() {
              complete(request, request.original);
            }
          });
    } catch (RuntimeException e) {
      complete(request, request.original);
    }
  }

  private static void complete(PendingTranslation request, String text) {
    synchronized (AutoTranslationController.class) {
      if (translating != request || request.completed) {
        return;
      }
      request.completed = true;
      dispatchDepth++;
      try {
        try {
          request.callback.accept(text);
        } catch (RuntimeException e) {
          LogUtils.w(TAG, "Could not finish translated speech: %s", e);
        }
      } finally {
        if (translating == request) {
          translating = null;
        }
        dispatchDepth--;
      }
    }
    startNext();
  }
}
