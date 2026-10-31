package com.google.android.accessibility.talkback.gesture;

import android.content.Context;
import com.google.android.accessibility.talkback.R;
import java.util.List;

public final class MenuGestureActions {
  private static final String PREFIX = "MENU_ACTION:";
  public static final class Entry {
    public final int menuId;
    public final int label;
    private final String name;
    private final int existingValue;
    private Entry(int menuId, int label, String name, int existingValue) {
      this.menuId = menuId; this.label = label; this.name = name; this.existingValue = existingValue;
    }
    public String value(Context context) { return existingValue == 0 ? PREFIX + name : context.getString(existingValue); }
  }
  public static final List<Entry> ENTRIES = List.of(
      new Entry(R.id.typo_suggestions_menu, R.string.menu_gesture_typo_suggestions_menu, "typo_suggestions_menu", 0),
      new Entry(R.id.custom_action_menu, R.string.menu_gesture_custom_action_menu, "custom_action_menu", R.string.shortcut_value_show_custom_actions),
      new Entry(R.id.viewpager_menu, R.string.menu_gesture_viewpager_menu, "viewpager_menu", 0),
      new Entry(R.id.labeling_breakout_add_label, R.string.menu_gesture_labeling_breakout_add_label, "labeling_breakout_add_label", 0),
      new Entry(R.id.image_caption_menu, R.string.menu_gesture_image_caption_menu, "image_caption_menu", R.string.shortcut_value_describe_image),
      new Entry(R.id.summarize_view_menu, R.string.menu_gesture_summarize_view_menu, "summarize_view_menu", R.string.shortcut_value_screen_overview),
      new Entry(R.id.granularity_menu, R.string.menu_gesture_granularity_menu, "granularity_menu", 0),
      new Entry(R.id.read_from_top, R.string.menu_gesture_read_from_top, "read_from_top", R.string.shortcut_value_read_from_top),
      new Entry(R.id.read_from_current, R.string.menu_gesture_read_from_current, "read_from_current", R.string.shortcut_value_read_from_current),
      new Entry(R.id.copy_last_utterance_to_clipboard, R.string.menu_gesture_copy_last_utterance_to_clipboard, "copy_last_utterance_to_clipboard", R.string.shortcut_value_copy_last_spoken_phrase),
      new Entry(R.id.spell_last_utterance, R.string.menu_gesture_spell_last_utterance, "spell_last_utterance", R.string.shortcut_value_spell_last_spoken_phrase),
      new Entry(R.id.repeat_last_utterance, R.string.menu_gesture_repeat_last_utterance, "repeat_last_utterance", R.string.shortcut_value_repeat_last_spoken_phrase),
      new Entry(R.id.translate_last_utterance, R.string.menu_gesture_translate_last_utterance, "translate_last_utterance", R.string.shortcut_value_translate_last_spoken_phrase),
      new Entry(R.id.speech_history, R.string.menu_gesture_speech_history, "speech_history", R.string.shortcut_value_speech_history),
      new Entry(R.id.favorites, R.string.menu_gesture_favorites, "favorites", R.string.shortcut_value_favorites),
      new Entry(R.id.clipboard_history, R.string.menu_gesture_clipboard_history, "clipboard_history", R.string.shortcut_value_clipboard_history),
      new Entry(R.id.text_formatting, R.string.menu_gesture_text_formatting, "text_formatting", 0),
      new Entry(R.id.keyboard_echo_on_screen_menu, R.string.menu_gesture_keyboard_echo_on_screen_menu, "keyboard_echo_on_screen_menu", 0),
      new Entry(R.id.keyboard_echo_physical_menu, R.string.menu_gesture_keyboard_echo_physical_menu, "keyboard_echo_physical_menu", 0),
      new Entry(R.id.verbosity, R.string.menu_gesture_verbosity, "verbosity", 0),
      new Entry(R.id.language_menu, R.string.menu_gesture_language_menu, "language_menu", R.string.shortcut_value_show_language_options),
      new Entry(R.id.emoji_menu, R.string.menu_gesture_emoji_menu, "emoji_menu", 0),
      new Entry(R.id.voice_profile_menu, R.string.menu_gesture_voice_profile_menu, "voice_profile_menu", R.string.shortcut_value_voice_profiles),
      new Entry(R.id.audio_ducking, R.string.menu_gesture_audio_ducking, "audio_ducking", 0),
      new Entry(R.id.sound_feedback, R.string.menu_gesture_sound_feedback, "sound_feedback", 0),
      new Entry(R.id.vibration_feedback, R.string.menu_gesture_vibration_feedback, "vibration_feedback", 0),
      new Entry(R.id.screen_search, R.string.menu_gesture_screen_search, "screen_search", R.string.shortcut_value_screen_search),
      new Entry(R.id.enable_dimming, R.string.menu_gesture_enable_dimming, "enable_dimming", R.string.shortcut_value_show_hide_screen),
      new Entry(R.id.disable_dimming, R.string.menu_gesture_disable_dimming, "disable_dimming", R.string.shortcut_value_show_hide_screen),
      new Entry(R.id.voice_commands, R.string.menu_gesture_voice_commands, "voice_commands", R.string.shortcut_value_voice_commands),
      new Entry(R.id.pause_backtalk, R.string.menu_gesture_pause_backtalk, "pause_backtalk", R.string.shortcut_value_pause_backtalk),
      new Entry(R.id.show_keyboard_shortcuts, R.string.menu_gesture_show_keyboard_shortcuts, "show_keyboard_shortcuts", 0),
      new Entry(R.id.talkback_settings, R.string.menu_gesture_talkback_settings, "talkback_settings", 0),
      new Entry(R.id.tts_settings, R.string.menu_gesture_tts_settings, "tts_settings", 0),
      new Entry(R.id.window_menu, R.string.menu_gesture_window_menu, "window_menu", 0),
      new Entry(R.id.inspect_item, R.string.menu_gesture_inspect_item, "inspect_item", R.string.shortcut_value_inspect_item),
      new Entry(R.id.copy_screen_tree, R.string.menu_gesture_copy_screen_tree, "copy_screen_tree", R.string.shortcut_value_copy_screen_tree),
      new Entry(R.id.enable_telling_time, R.string.menu_gesture_enable_telling_time, "enable_telling_time", R.string.shortcut_value_control_telling_time),
      new Entry(R.id.disable_telling_time, R.string.menu_gesture_disable_telling_time, "disable_telling_time", R.string.shortcut_value_control_telling_time),
      new Entry(R.id.text_statistics_menu, R.string.menu_gesture_text_statistics_menu, "text_statistics_menu", R.string.shortcut_value_text_statistics),
      new Entry(R.id.lock_screen_menu, R.string.menu_gesture_lock_screen_menu, "lock_screen_menu", R.string.shortcut_value_lock_screen),
      new Entry(R.id.media_previous_menu, R.string.menu_gesture_media_previous_menu, "media_previous_menu", R.string.shortcut_value_media_previous),
      new Entry(R.id.media_next_menu, R.string.menu_gesture_media_next_menu, "media_next_menu", R.string.shortcut_value_media_next),
      new Entry(R.id.copy_focused_menu, R.string.menu_gesture_copy_focused_menu, "copy_focused_menu", R.string.shortcut_value_copy_focused),
      new Entry(R.id.clear_all_text_menu, R.string.menu_gesture_clear_all_text_menu, "clear_all_text_menu", R.string.shortcut_value_clear_all_text),
      new Entry(R.id.direct_tap_menu, R.string.menu_gesture_direct_tap_menu, "direct_tap_menu", R.string.shortcut_value_direct_tap),
      new Entry(R.id.take_screenshot_menu, R.string.menu_gesture_take_screenshot_menu, "take_screenshot_menu", R.string.shortcut_value_take_screenshot),
      new Entry(R.id.toggle_touch_exploration_menu, R.string.menu_gesture_toggle_touch_exploration_menu, "toggle_touch_exploration_menu", R.string.shortcut_value_toggle_touch_exploration),
      new Entry(R.id.monitor_focus_menu, R.string.menu_gesture_monitor_focus_menu, "monitor_focus_menu", R.string.shortcut_value_monitor_focus),
      new Entry(R.id.auto_translate_menu, R.string.menu_gesture_auto_translate_menu, "auto_translate_menu", R.string.shortcut_value_auto_translate),
      new Entry(R.id.copy_more_menu, R.string.menu_gesture_copy_more_menu, "copy_more_menu", R.string.shortcut_value_copy_more),
      new Entry(R.id.copy_full_screen_menu, R.string.menu_gesture_copy_full_screen_menu, "copy_full_screen_menu", R.string.shortcut_value_copy_full_screen),
      new Entry(R.id.take_focus_screenshot_menu, R.string.menu_gesture_take_focus_screenshot_menu, "take_focus_screenshot_menu", R.string.shortcut_value_take_focus_screenshot),
      new Entry(R.id.task_assistant_menu, R.string.menu_gesture_task_assistant_menu, "task_assistant_menu", R.string.shortcut_value_task_assistant),
      new Entry(R.id.screen_recognition_menu, R.string.menu_gesture_screen_recognition_menu, "screen_recognition_menu", R.string.shortcut_value_screen_recognition),
      new Entry(R.id.recognize_screen_menu, R.string.menu_gesture_recognize_screen_menu, "recognize_screen_menu", 0),
      new Entry(R.id.screen_recognition_app_menu, R.string.menu_gesture_screen_recognition_app_menu, "screen_recognition_app_menu", 0),
      new Entry(R.id.subtitles_menu, R.string.menu_gesture_subtitles_menu, "subtitles_menu", R.string.shortcut_value_subtitles),
      new Entry(R.id.subtitle_find_again_menu, R.string.menu_gesture_subtitle_find_again_menu, "subtitle_find_again_menu", R.string.shortcut_value_subtitle_find_again),
      new Entry(R.id.script_command, R.string.menu_gesture_script_command, "script_command", 0)
  );
  private MenuGestureActions() {}
  public static int menuId(Context context, String value) {
    if (value == null || !value.startsWith(PREFIX)) return 0;
    for (Entry entry : ENTRIES) if (entry.value(context).equals(value)) return entry.menuId;
    return 0;
  }
  public static String label(Context context, String value) {
    for (Entry entry : ENTRIES) if (entry.value(context).equals(value)) return context.getString(entry.label);
    return null;
  }
}
