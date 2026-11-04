package com.google.android.accessibility.utils.output;

import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class VoiceSwitching {
  public static final String SELECTED_PREFIX = "pref_engine_selected_voice_";
  private static final String ORDER_PREFIX = "pref_engine_voice_order_";

  private VoiceSwitching() {}

  public static String selectedKey(String engine) {
    return SELECTED_PREFIX + engine;
  }

  public static List<String> readOrder(SharedPreferences prefs, String engine) {
    List<String> names = new ArrayList<>();
    try {
      JSONArray stored = new JSONArray(prefs.getString(ORDER_PREFIX + engine, "[]"));
      for (int index = 0; index < stored.length(); index++) {
        String name = stored.optString(index, "");
        if (!name.isEmpty() && !names.contains(name)) names.add(name);
      }
    } catch (JSONException | ClassCastException e) {
      return names;
    }
    return names;
  }

  public static void saveOrder(SharedPreferences prefs, String engine, List<String> names) {
    prefs.edit().putString(ORDER_PREFIX + engine,
        new JSONArray(new ArrayList<>(new LinkedHashSet<>(names))).toString()).apply();
  }

  public static List<Voice> installed(@Nullable Collection<Voice> voices) {
    LinkedHashMap<String, Voice> unique = new LinkedHashMap<>();
    if (voices != null) {
      for (Voice voice : voices) {
        if (voice == null || voice.getName() == null || voice.getName().isEmpty()
            || voice.getLocale() == null) continue;
        Set<String> features = voice.getFeatures();
        if (features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
          unique.putIfAbsent(voice.getName(), voice);
        }
      }
    }
    List<Voice> result = new ArrayList<>(unique.values());
    result.sort(Comparator.comparing((Voice voice) -> voice.getLocale().toLanguageTag())
        .thenComparing(Voice::getName));
    return result;
  }

  public static List<Voice> candidates(
      @Nullable Collection<Voice> voices, @Nullable Locale locale, List<String> order) {
    List<Voice> installed = installed(voices);
    List<Voice> result = new ArrayList<>();
    if (!order.isEmpty()) {
      for (String name : order) {
        for (Voice voice : installed) {
          if (name.equals(voice.getName())) {
            result.add(voice);
            break;
          }
        }
      }
      return result;
    }
    for (Voice voice : installed) {
      if (locale == null || locale.getLanguage().equals(voice.getLocale().getLanguage())) {
        result.add(voice);
      }
    }
    return result;
  }

  public static @Nullable Voice adjacent(List<Voice> voices, @Nullable String current, boolean next) {
    if (voices.isEmpty()) return null;
    int index = -1;
    for (int position = 0; position < voices.size(); position++) {
      if (voices.get(position).getName().equals(current)) {
        index = position;
        break;
      }
    }
    if (index < 0) return voices.get(next ? 0 : voices.size() - 1);
    return voices.get(Math.floorMod(index + (next ? 1 : -1), voices.size()));
  }
}
