package com.google.android.accessibility.talkback.translation;

import android.content.Context;
import android.text.TextUtils;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

public class AutoTranslationController {

  private static boolean enabled;
  private static @Nullable Context appContext;
  private static @Nullable OfflineTranslator translator;
  private static int generation;

  private AutoTranslationController() {
    throw new AssertionError();
  }

  public static synchronized boolean isEnabled() {
    return enabled;
  }

  public static void toggle(Context context, Consumer<Boolean> result) {
    synchronized (AutoTranslationController.class) {
      if (enabled) {
        enabled = false;
        generation++;
        result.accept(false);
        return;
      }
    }
    appContext = context.getApplicationContext();
    OfflineTranslator client = new OfflineTranslator(appContext);
    synchronized (AutoTranslationController.class) {
      translator = client;
    }
    client.isModelDownloaded(
        downloaded -> {
          synchronized (AutoTranslationController.class) {
            if (downloaded) {
              enabled = true;
              result.accept(true);
            } else {
              result.accept(false);
            }
          }
        });
  }

  public static void translate(CharSequence text, Consumer<String> onTranslated) {
    OfflineTranslator client;
    int requestGeneration;
    synchronized (AutoTranslationController.class) {
      if (!enabled || translator == null || TextUtils.isEmpty(text)) {
        onTranslated.accept(text == null ? "" : text.toString());
        return;
      }
      client = translator;
      requestGeneration = ++generation;
    }
    String original = text.toString();
    client.translate(
        original,
        new OfflineTranslator.TranslateCallback() {
          @Override
          public void onSuccess(String translatedText) {
            synchronized (AutoTranslationController.class) {
              if (requestGeneration == generation) {
                onTranslated.accept(translatedText);
              }
            }
          }

          @Override
          public void onFailure() {
            synchronized (AutoTranslationController.class) {
              if (requestGeneration == generation) {
                onTranslated.accept(original);
              }
            }
          }
        });
  }
}
