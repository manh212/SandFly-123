package com.google.android.accessibility.talkback.keyboardecho;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.input.TextEventFilter;

public final class BrailleKeyboardEchoSettings {
  private BrailleKeyboardEchoSettings() {}

  public static int read(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    String key = context.getString(R.string.pref_echo_braille_keyboard_key);
    if (prefs.contains(key)) {
      return SharedPreferencesUtils.getIntFromStringPref(
          prefs, context.getResources(), R.string.pref_echo_braille_keyboard_key,
          R.string.pref_keyboard_echo_default);
    }
    int legacy = context.getSharedPreferences("BraillePhoneBreakthroughV2Prefs", Context.MODE_PRIVATE)
        .getInt("echo_mode", 0);
    return switch (legacy) {
      case 1 -> TextEventFilter.PREF_ECHO_CHARACTERS;
      case 2 -> TextEventFilter.PREF_ECHO_WORDS;
      case 3 -> TextEventFilter.PREF_ECHO_NONE;
      default -> TextEventFilter.PREF_ECHO_CHARACTERS_AND_WORDS;
    };
  }

  public static int cycle(Context context) {
    int[] choices = KeyboardEchoSettings.choices(context.getResources());
    int current = read(context);
    int next = choices[0];
    for (int i = 0; i < choices.length; i++) {
      if (choices[i] == current) {
        next = choices[(i + 1) % choices.length];
        break;
      }
    }
    SharedPreferencesUtils.getSharedPreferences(context).edit()
        .putString(context.getString(R.string.pref_echo_braille_keyboard_key), Integer.toString(next))
        .apply();
    return next;
  }

  public static boolean isTypingSilent(int echo) {
    return echo == TextEventFilter.PREF_ECHO_SILENT_TYPING;
  }
}
