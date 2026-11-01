Warning: truncated output (original token count: 35118)
Total output lines: 3355

/*
 * Copyright (C) 2017 Google Inc.
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

package com.google.android.accessibility.talkback.selector;

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP;
import static com.google.android.accessibility.talkback.Feedback.AdjustValue.Action.DECREASE_VALUE;
import static com.google.android.accessibility.talkback.Feedback.AdjustValue.Action.INCREASE_VALUE;
import static com.google.android.accessibility.talkback.Feedback.AdjustVolume.Action.DECREASE_VOLUME;
import static com.google.android.accessibility.talkback.Feedback.AdjustVolume.Action.INCREASE_VOLUME;
import static com.google.android.accessibility.talkback.Feedback.AdjustVolume.StreamType.STREAM_TYPE_ACCESSIBILITY;
import static com.google.android.accessibility.talkback.Feedback.DimScreen.Action.BRIGHTEN;
import static com.google.android.accessibility.talkback.Feedback.DimScreen.Action.DIM;
import static com.google.android.accessibility.talkback.Feedback.Focus.Action.CLICK_NODE;
import static com.google.android.accessibility.talkback.Feedback.FocusDirection.Action.NEXT_PAGE;
import static com.google.android.accessibility.talkback.Feedback.FocusDirection.Action.PREVIOUS_PAGE;
import static com.google.android.accessibility.talkback.Feedback.Language.Action.NEXT_LANGUAGE;
import static com.google.android.accessibility.talkback.Feedback.Language.Action.PREVIOUS_LANGUAGE;
import static com.google.android.accessibility.talkback.Feedback.ServiceFlag.Action.DISABLE_FLAG;
import static com.google.android.accessibility.talkback.Feedback.ServiceFlag.Action.ENABLE_FLAG;
import static com.google.android.accessibility.talkback.actor.TalkBackUIActor.Type.SELECTOR_ITEM_ACTION_OVERLAY;
import static com.google.android.accessibility.talkback.actor.TalkBackUIActor.Type.SELECTOR_MENU_ITEM_OVERLAY_MULTI_FINGER;
import static com.google.android.accessibility.talkback.actor.TalkBackUIActor.Type.SELECTOR_MENU_ITEM_OVERLAY_SINGLE_FINGER;
import static com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo.RESTORED_LAST_FOCUS;
import static com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo.SCREEN_STATE_CHANGE;
import static com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo.TOUCH_EXPLORATION;
import static com.google.android.accessibility.talkback.menurules.NodeMenuRuleCreator.MenuRules.RULE_CUSTOM_ACTION;
import static com.google.android.accessibility.talkback.selector.SelectorController.AnnounceType.DESCRIPTION_AND_HINT;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.ACTIONS;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.ADJUSTABLE_WIDGET;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.FORMATTING;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.GRANULARITY_CHARACTERS;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.GRANULARITY_CONTAINERS;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.GRANULARITY_SEARCH;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.GRANULARITY_TYPO;
import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.GRANULARITY_WINDOWS;
import static com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED;
import static com.google.android.accessibility.utils.monitor.InputModeTracker.INPUT_MODE_TOUCH;
import static com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_BACKWARD;
import static com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_FORWARD;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.IdRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.ActorState;
import com.google.android.accessibility.talkback.Feedback;
import com.google.android.accessibility.talkback.Feedback.Speech;
import com.google.android.accessibility.talkback.Feedback.SpeechRate.Action;
import com.google.android.accessibility.talkback.Feedback.TalkBackUI;
import com.google.android.accessibility.talkback.Feedback.TalkBackUI.Item;
import com.google.android.accessibility.talkback.Interpretation;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.ItemOrder;
import com.google.android.accessibility.talkback.UserInterface.UserInputEventListener;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.compositor.Compositor;
import com.google.android.accessibility.talkback.compositor.CompositorUtils;
import com.google.android.accessibility.talkback.contextmenu.ContextMenu;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem.ContextMenuItemId;
import com.google.android.accessibility.talkback.eventprocessor.ProcessorAccessibilityHints;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.focusmanagement.LiftToActivateMode;
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.talkback.gesture.CopyTextUtils;
import com.google.android.accessibility.talkback.gesture.GestureHints;
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping;
import com.google.android.accessibility.talkback.keyboardecho.KeyboardEchoSettings;
import com.google.android.accessibility.talkback.menurules.NodeMenuRuleCreator;
import com.google.android.accessibility.talkback.monitor.VolumeMonitor;
import com.google.android.accessibility.talkback.monitor.VolumeMonitor.VolumeChangedListener;
import com.google.android.accessibility.talkback.recognition.LiveRecognition;
import com.google.android.accessibility.talkback.scripting.ScriptReadingControl;
import com.google.android.accessibility.talkback.scripting.Scripts;
import com.google.android.accessibility.talkback.selector.SelectorController.Setting.DescriptionAndHint;
import com.google.android.accessibility.talkback.speech.VoiceProfileNames;
import com.google.android.accessibility.talkback.translation.TranslationSettings;
import com.google.android.accessibility.talkback.utils.ClipboardUtils;
import com.google.android.accessibility.talkback.utils.VerbosityPreferences;
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.accessibility.utils.Role;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.input.CursorGranularity;
import com.google.android.accessibility.utils.input.TextEventFilter.TypingInput;
import com.google.android.accessibility.utils.monitor.CollectionState;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Class to handle changes to selector and calls from {@link GestureController}. */
public class SelectorController implements UserInputEventListener {

  /** The type of granularity. */
  @IntDef({GRANULARITY_FOR_ALL_NODE, GRANULARITY_FOR_NATIVE_NODE, GRANULARITY_FOR_WEB_NODE})
  public @interface GranularityType {}

  private static final String TAG = "SelectorController";
  private static final int GRANULARITY_FOR_ALL_NODE = 0;
  @VisibleForTesting static final int GRANULARITY_FOR_NATIVE_NODE = 1;
  @VisibleForTesting static final int GRANULARITY_FOR_WEB_NODE = 2;

  /** The current action id selected for the actions setting. */
  private ContextMenuItemId currentActionId;

  private boolean hasRequestServiceHandlesDoubleTap = false;
  // To reduce the frequency of currentSetting update, we don't update the setting while the touch
  // interaction's ongoing. This flag records the touch interaction start/end, respectively. So that
  // the currentSetting will only happen when touch event's ended.
  private boolean touchActive = false;
  // Accompanying the 'touchActive' flag, this variable avoids the reassign of the same node.
  private AccessibilityNodeInfo lastFocusedNode = null;

  /** Audial feedback announce types. */
  public enum AnnounceType {
    SILENCE,
    DESCRIPTION,
    DESCRIPTION_AND_HINT,
  }

  /** Selector settings. */
  public enum Setting {
    SPEECH_RATE(
        R.string.pref_selector_speech_rate_key,
        R.string.selector_speech_rate,
        R.bool.pref_selector_speech_rate_default),
    LANGUAGE(
        R.string.pref_selector_language_key,
        R.string.selector_language,
        R.bool.pref_selector_language_default),
    VERBOSITY(
        R.string.pref_selector_verbosity_key,
        R.string.selector_verbosity,
        R.bool.pref_selector_verbosity_default),
    PUNCTUATION(
        R.string.pref_selector_punctuation_key,
        R.string.selector_punctuation,
        R.bool.pref_selector_punctuation_default),
    EMOJI(
        R.string.pref_selector_emoji_key,
        R.string.selector_emoji,
        R.bool.pref_selector_emoji_default),
    KEYBOARD_ECHO_ON_SCREEN(
        R.string.pref_selector_keyboard_echo_on_screen_key,
        R.string.selector_keyboard_echo_on_screen,
        R.bool.pref_selector_keyboard_echo_default),
    KEYBOARD_ECHO_PHYSICAL(
        R.string.pref_selector_keyboard_echo_physical_key,
        R.string.selector_keyboard_echo_physical,
        R.bool.pref_selector_keyboard_echo_default),
    GRANULARITY(
        R.string.pref_selector_granularity_key,
        R.string.selector_granularity,
        R.bool.pref_selector_granularity_default),
    HIDE_SCREEN(
        R.string.pref_selector_hide_screen_key,
        R.string.selector_hide_screen,
        R.bool.pref_selector_hide_screen_default),
    AUDIO_FOCUS(
        R.string.pref_selector_audio_focus_key,
        R.string.selector_audio_focus,
        R.bool.pref_selector_audio_focus_default),
    SCROLLING_SEQUENTIAL(
        R.string.pref_selector_scroll_seq_key,
        R.string.selector_scroll_seq,
        R.bool.pref_selector_scroll_seq_default),
    CHANGE_ACCESSIBILITY_VOLUME(
        R.string.pref_selector_change_a11y_volume_key,
        R.string.selector_a11y_volume_change,
        R.bool.pref_selector_a11y_volume_default),
    SOUND_MODE(
        R.string.pref_selector_sound_mode_key,
        R.string.selector_sound_mode,
        R.bool.pref_selector_sound_mode_default),
    AUTO_TRANSLATION(
        R.string.pref_selector_auto_translation_key,
        R.string.selector_auto_translation,
        R.bool.pref_selector_auto_translation_default),
    CHANGE_BRIGHTNESS(
        R.string.pref_selector_change_brightness_key,
        R.string.selector_brightness_change,
        R.bool.pref_selector_brightness_default),
    CHANGE_LIFT_TO_ACTIVATE(
        R.string.pref_selector_change_lift_to_activate_key,
        R.string.selector_lift_to_activate_change,
        R.bool.pref_selector_lift_to_activate_default),
    WRAP_AROUND(
        R.string.pref_selector_wrap_around_key,
        R.string.selector_wrap_around,
        R.bool.pref_selector_wrap_around_default),
    LIVE_RECOGNITION(
        R.string.pref_selector_live_recognition_key,
        R.string.selector_live_recognition,
        R.bool.pref_selector_live_recognition_default),
    ACTIONS(
        R.string.pref_selector_actions_key,
        R.string.selector_actions,
        R.bool.pref_selector_actions_default),
    COPY_MODE(
        R.string.pref_selector_copy_mode_key,
        R.string.selector_copy_mode_value,
        R.bool.pref_selector_copy_mode_default),
    REPEAT_AND_TRANSLATE(
        R.string.pref_selector_repeat_translate_key,
        R.string.selector_repeat_translate,
        R.bool.pref_selector_repeat_translate_default),
    GRANULARITY_HEADINGS(
        R.string.pref_selector_granularity_headings_key,
        R.string.selector_granularity_headings,
        R.bool.pref_selector_granularity_headings_default),
    GRANULARITY_WORDS(
        R.string.pref_selector_granularity_words_key,
        R.string.selector_granularity_words,
        R.bool.pref_selector_granularity_words_default),
    GRANULARITY_PARAGRAPHS(
        R.string.pref_selector_granularity_paragraphs_key,
        R.string.selector_granularity_paragraphs,
        R.bool.pref_selector_granularity_paragraphs_default),
    GRANULARITY_CHARACTERS(
        R.string.pref_selector_granularity_characters_key,
        R.string.selector_granularity_characters,
        R.bool.pref_selector_granularity_characters_default),
    GRANULARITY_LINES(
        R.string.pref_selector_granularity_lines_key,
        R.string.selector_granularity_lines,
        R.bool.pref_selector_granularity_lines_default),
    GRANULARITY_LINKS(
        R.string.pref_selector_granularity_links_key,
        R.string.selector_granularity_links,
        R.bool.pref_selector_granularity_links_default),
    GRANULARITY_CONTROLS(
        R.string.pref_selector_granularity_controls_key,
        R.string.selector_granularity_controls,
        R.bool.pref_selector_granularity_controls_default),
    GRANULARITY_ROW_COLUMN(
        R.string.pref_selector_granularity_row_column_key,
        R.string.selector_granularity_row_column,
        R.bool.pref_selector_granularity_row_column_default),
    GRANULARITY_ROW(
        R.string.pref_selector_granularity_row_key,
        R.string.selector_granularity_row,
        R.bool.pref_selector_granularity_row_default),
    GRANULARITY_COLUMN(
        R.string.pref_selector_granularity_column_key,
        R.string.selector_granularity_column,
        R.bool.pref_selector_granularity_column_default),
    GRANULARITY_WEB_LANDMARKS(
        R.string.pref_selector_granularity_landmarks_key,
        R.string.selector_granularity_landmarks,
        R.bool.pref_selector_granularity_landmarks_default),
    GRANULARITY_WEB_BUTTONS(
        R.string.pref_selector_granularity_buttons_key,
        R.string.selector_granularity_buttons,
        R.bool.pref_selector_granularity_buttons_default),
    GRANULARITY_WEB_CHECKBOXES(
        R.string.pref_selector_granularity_checkboxes_key,
        R.string.selector_granularity_checkboxes,
        R.bool.pref_selector_granularity_checkboxes_default),
    GRANULARITY_WEB_EDITFIELDS(
        R.string.pref_selector_granularity_editfields_key,
        R.string.selector_granularity_editfields,
        R.bool.pref_selector_granularity_editfields_default),
    GRANULARITY_WEB_FOCUSABLES(
        R.string.pref_selector_granularity_focusables_key,
        R.string.selector_granularity_focusables,
        R.bool.pref_selector_granularity_focusables_default),
    GRANULARITY_WEB_H1(
        R.string.pref_selector_granularity_h1_key,
        R.string.selector_granularity_h1,
        R.bool.pref_selector_granularity_h1_default),
    GRANULARITY_WEB_H2(
        R.string.pref_selector_granularity_h2_key,
        R.string.selector_granularity_h2,
        R.bool.pref_selector_granularity_h2_default),
    GRANULARITY_WEB_H3(
        R.string.pref_selector_granularity_h3_key,
        R.string.selector_granularity_h3,
        R.bool.pref_selector_granularity_h3_default),
    GRANULARITY_WEB_H4(
        R.string.pref_selector_granularity_h4_key,
        R.string.selector_granularity_h4,
        R.bool.pref_selector_granularity_h4_default),
    GRANULARITY_WEB_H5(
        R.string.pref_selector_granularity_h5_key,
        R.string.selector_granularity_h5,
        R.bool.pref_selector_granularity_h5_default),
    GRANULARITY_WEB_H6(
        R.string.pref_selector_granularity_h6_key,
        R.string.selector_granularity_h6,
        R.bool.pref_selector_granularity_h6_default),
    GRANULARITY_WEB_GRAPHICS(
        R.string.pref_selector_granularity_graphics_key,
        R.string.selector_granularity_graphics,
        R.bool.pref_selector_granularity_graphics_default),
    GRANULARITY_WEB_LISTS(
        R.string.pref_selector_granularity_lists_key,
        R.string.selector_granularity_lists,
        R.bool.pref_selector_granularity_lists_default),
    GRANULARITY_WEB_LISTITEMS(
        R.string.pref_selector_granularity_listitems_key,
        R.string.selector_granularity_listitems,
        R.bool.pref_selector_granularity_listitems_default),
    GRANULARITY_WEB_TABLES(
        R.string.pref_selector_granularity_tables_key,
        R.string.selector_granularity_tables,
        R.bool.pref_selector_granularity_tables_default),
    GRANULARITY_WEB_COMBOBOXES(
        R.string.pref_selector_granularity_comboboxes_key,
        R.string.selector_granularity_comboboxes,
        R.bool.pref_selector_granularity_comboboxes_default),
    GRANULARITY_WEB_VISITED_LINKS(
        R.string.pref_selector_granularity_visited_links_key,
        R.string.selector_granularity_visited_links,
        R.bool.pref_selector_granularity_visited_links_default),
    GRANULARITY_WEB_UNVISITED_LINKS(
        R.string.pref_selector_granularity_unvisited_links_key,
        R.string.selector_granularity_unvisited_links,
        R.bool.pref_selector_granularity_unvisited_links_default),
    GRANULARITY_WEB_RADIOS(
        R.string.pref_selector_granularity_radios_key,
        R.string.selector_granularity_radios,
        R.bool.pref_selector_granularity_radios_default),
    GRANULARITY_WINDOWS(
        R.string.pref_selector_granularity_windows_key,
        R.string.selector_granularity_windows,
        R.bool.pref_selector_granularity_windows_default),
    GRANULARITY_CONTAINERS(
        R.string.pref_selector_granularity_containers_key,
        R.string.selector_granularity_containers,
        R.bool.pref_selector_granularity_containers_default),
    GRANULARITY_SEARCH(
        R.string.pref_selector_granularity_search_key,
        R.string.selector_granularity_search,
        R.bool.pref_selector_granularity_search_default),
    GRANULARITY_DEFAULT(
        R.string.pref_selector_granularity_key,
        R.string.granularity_default,
        R.bool.pref_show_navigation_menu_granularity_default),
    ADJUSTABLE_WIDGET(
        R.string.pref_selector_special_widget_key,
        R.string.selector_special_widget,
        R.bool.pref_selector_special_widget_default),
    GRANULARITY_TYPO(
        R.string.pref_selector_granularity_typo_key,
        R.string.selector_granularity_typo,
        R.bool.pref_selector_granularity_typo_default),
    CONTROL_TELLING_TIME(
        R.string.pref_selector_control_telling_time,
        R.string.selector_control_telling_time,
        R.bool.pref_selector_control_telling_time_default),
    FORMATTING(
        R.string.pref_selector_text_formatting_inline_key,
        R.string.title_switch_text_formatting,
        R.bool.pref_selector_text_formatting_inline_default),
    SWITCH_TTS_ENGINE(
        R.string.pref_selector_switch_tts_engine_key,
        R.string.selector_switch_tts_engine,
        R.bool.pref_selector_switch_tts_engine_default),
    VERTICAL_NAVIGATION(
        R.string.pref_selector_vertical_navigation_key,
        R.string.selector_vertical_navigation,
        R.bool.pref_selector_vertical_navigation_default),
    SPEECH_STREAM(
        R.string.pref_selector_speech_stream_key,
        R.string.selector_speech_stream,
        R.bool.pref_selector_speech_stream_default),
    SWITCH_VOICE_PROFILE(
        R.string.pref_selector_voice_profile_key,
        R.string.selector_voice_profile,
        R.bool.pref_selector_voice_profile_default);

    /** The preference key of the filter in the selector settings page. */
    final int prefKeyResId;

    /**
     * When the user select the setting, the value will be saved in the current setting preference.
     */
    final int prefValueResId;

    /** The setting is on or off in default. */
    final int defaultValueResId;

    /** Constructor of a new Setting with the specific preference key and value. */
    Setting(int prefKeyResId, int prefValueResId, int defaultValueResId) {
      this.prefKeyResId = prefKeyResId;
      this.prefValueResId = prefValueResId;
      this.defaultValueResId = defaultValueResId;
    }

    /** Returns a Setting associated with the given preference value. */
    public static @Nullable Setting getSettingFromPrefValue(Context context, String prefValue) {
      for (Setting setting : values()) {
        if (TextUtils.equals(context.getString(setting.prefValueResId), prefValue)) {
          return setting;
        }
      }
      return null;
    }

    /** Class wraps the {@link Setting} description and hint. */
    public static class DescriptionAndHint {
      public final String description;
      public final String hint;

      public DescriptionAndHint(String description, String hint) {
        this.description = description;
        this.hint = hint;
      }
    }
  }

  /** Granularities are provided by the selector. */
  public enum Granularity {
    HEADINGS(Setting.GRANULARITY_HEADINGS, CursorGranularity.HEADING, GRANULARITY_FOR_ALL_NODE),
    WORDS(Setting.GRANULARITY_WORDS, CursorGranularity.WORD, GRANULARITY_FOR_ALL_NODE),
    PARAGRAPHS(
        Setting.GRANULARITY_PARAGRAPHS, CursorGranularity.PARAGRAPH, GRANULARITY_FOR_ALL_NODE),
    CHARACTERS(
        Setting.GRANULARITY_CHARACTERS, CursorGranularity.CHARACTER, GRANULARITY_FOR_ALL_NODE),
    LINES(Setting.GRANULARITY_LINES, CursorGranularity.LINE, GRANULARITY_FOR_ALL_NODE),
    LINKS(Setting.GRANULARITY_LINKS, CursorGranularity.LINK, GRANULARITY_FOR_ALL_NODE),
    CONTROLS(Setting.GRANULARITY_CONTROLS, CursorGranularity.CONTROL, GRANULARITY_FOR_ALL_NODE),
    ROW_COLUMN(
        Setting.GRANULARITY_ROW_COLUMN, CursorGranularity.ROW_COLUMN, GRANULARITY_FOR_ALL_NODE),
    ROW(Setting.GRANULARITY_ROW, CursorGranularity.ROW, GRANULARITY_FOR_ALL_NODE),
    COLUMN(Setting.GRANULARITY_COLUMN, CursorGranularity.COLUMN, GRANULARITY_FOR_ALL_NODE),
    WINDOWS(GRANULARITY_WINDOWS, CursorGranularity.WINDOWS, GRANULARITY_FOR_ALL_NODE),
    CONTAINERS(
        Setting.GRANULARITY_CONTAINERS, CursorGranularity.CONTAINER, GRANULARITY_FOR_ALL_NODE),
    SEARCH(Setting.GRANULARITY_SEARCH, CursorGranularity.SEARCH, GRANULARITY_FOR_ALL_NODE),
    DEFAULT(Setting.GRANULARITY_DEFAULT, CursorGranularity.DEFAULT, GRANULARITY_FOR_ALL_NODE),

    // For WebView.
    WEB_LANDMARKS(
        Setting.GRANULARITY_WEB_LANDMARKS,
        CursorGranularity.WEB_LANDMARK,
        GRANULARITY_FOR_WEB_NODE),
    WEB_BUTTONS(
        Setting.GRANULARITY_WEB_BUTTONS, CursorGranularity.WEB_BUTTON, GRANULARITY_FOR_WEB_NODE),
    WEB_CHECKBOXES(
        Setting.GRANULARITY_WEB_CHECKBOXES,
        CursorGranularity.WEB_CHECKBOX,
        GRANULARITY_FOR_WEB_NODE),
    WEB_EDITFIELDS(
        Setting.GRANULARITY_WEB_EDITFIELDS,
        CursorGranularity.WEB_EDITFIELD,
        GRANULARITY_FOR_WEB_NODE),
    WEB_FOCUSABLES(
        Setting.GRANULARITY_WEB_FOCUSABLES,
        CursorGranularity.WEB_FOCUSABLE,
        GRANULARITY_FOR_WEB_NODE),
    WEB_H1(Setting.GRANULARITY_WEB_H1, CursorGranularity.WEB_H1, GRANULARITY_FOR_WEB_NODE),
    WEB_H2(Setting.GRANULARITY_WEB_H2, CursorGranularity.WEB_H2, GRANULARITY_FOR_WEB_NODE),
    WEB_H3(Setting.GRANULARITY_WEB_H3, CursorGranularity.WEB_H3, GRANULARITY_FOR_WEB_NODE),
    WEB_H4(Setting.GRANULARITY_WEB_H4, CursorGranularity.WEB_H4, GRANULARITY_FOR_WEB_NODE),
    WEB_H5(Setting.GRANULARITY_WEB_H5, CursorGranularity.WEB_H5, GRANULARITY_FOR_WEB_NODE),
    WEB_H6(Setting.GRANULARITY_WEB_H6, CursorGranularity.WEB_H6, GRANULARITY_FOR_WEB_NODE),
    WEB_GRAPHICS(
        Setting.GRANULARITY_WEB_GRAPHICS, CursorGranularity.WEB_GRAPHIC, GRANULARITY_FOR_WEB_NODE),
    WEB_LISTS(Setting.GRANULARITY_WEB_LISTS, CursorGranularity.WEB_LIST, GRANULARITY_FOR_WEB_NODE),
    WEB_LISTITEMS(
        Setting.GRANULARITY_WEB_LISTITEMS,
        CursorGranularity.WEB_LISTITEM,
        GRANULARITY_FOR_WEB_NODE),
    WEB_TABLES(
        Setting.GRANULARITY_WEB_TABLES, CursorGranularity.WEB_TABLE, GRANULARITY_FOR_WEB_NODE),
    WEB_COMBOBOXES(
        Setting.GRANULARITY_WEB_COMBOBOXES,
        CursorGranularity.WEB_COMBOBOX,
        GRANULARITY_FOR_WEB_NODE),
    WEB_VISITED_LINKS(
        Setting.GRANULARITY_WEB_VISITED_LINKS,
        CursorGranularity.WEB_VISITED_LINK,
        GRANULARITY_FOR_WEB_NODE),
    WEB_UNVISITED_LINKS(
        Setting.GRANULARITY_WEB_UNVISITED_LINKS,
        CursorGranularity.WEB_UNVISITED_LINK,
        GRANULARITY_FOR_WEB_NODE),
    WEB_RADIOS(
        Setting.GRANULARITY_WEB_RADIOS, CursorGranularity.WEB_RADIO, GRANULARITY_FOR_WEB_NODE);

    final Setting setting;
    final CursorGranularity cursorGranularity;
    @GranularityType final int granularityType;

    Granularity(
        Setting setting,
        CursorGranularity cursorGranularity,
        @GranularityType int granularityType) {
      this.setting = setting;
      this.cursorGranularity = cursorGranularity;
      this.granularityType = granularityType;
    }

    /**
     * Returns a list of Granularity associated with the given Setting.
     *
     * <p>Related Granularity can have more than one because different Granularity can connect to
     * the same Setting. For example, the Setting of Granularity.HEADINGS and
     * Granularity.WEB_HEADINGS are the same (Setting.GRANULARITY_HEADINGS).
     */
    public static List<Granularity> getFromSetting(Setting setting) {
      List<Granularity> granularities = new ArrayList<>();
      for (Granularity granularity : values()) {
        if (granularity.setting.prefKeyResId == setting.prefKeyResId) {
          granularities.add(granularity);
        }
      }
      return granularities;
    }

    /** Returns a Granularity, which is the supported granularity of the focused node. */
    public static @Nullable Granularity getSupportedGranularity(
        ActorState actorState, Setting setting) {
      List<Granularity> granularities = Granularity.getFromSetting(setting);
      if (granularities.size() == 1) {
        return granularities.get(0);
      }

      for (Granularity granularity : granularities) {
        if (isValid(granularity, actorState)) {
          return granularity;
        }
      }

      return null;
    }

    /** Returns a Setting, which is for setting the given CursorGranularity. */
    public static @Nullable Setting getSettingFromCursorGranularity(
        CursorGranularity cursorGranularity) {
      for (Granularity granularity : values()) {
        if (granularity.cursorGranularity == cursorGranularity) {
          return granularity.setting;
        }
      }
      return null;
    }

    static boolean isValid(Granularity granularity, ActorState actorState) {
      if (granularity.granularityType == GRANULARITY_FOR_ALL_NODE) {
        return true;
      }

      boolean isWebContent = actorState.getDirectionNavigation().hasNavigableWebContent();
      if ((granularity.granularityType == GRANULARITY_FOR_NATIVE_NODE && !isWebContent)
          || (granularity.granularityType == GRANULARITY_FOR_WEB_NODE && isWebContent)) {
        return true;
      }

      return false;
    }
  }

  private final Context context;
  private final AccessibilityFocusMonitor accessibilityFocusMonitor;
  private final Pipeline.FeedbackReturner pipeline;
  private final VerticalNavigator verticalNavigator;
  private final ActorState actorState;
  private final NodeMenuRuleCreator nodeMenuCreator;
  private final TalkBackAnalytics analytics;
  private final SharedPreferences prefs;
  private final GestureShortcutMapping gestureMapping;
  @NonNull private final Compositor.TextComposer compositor;

  /**
   * Keeps gestures to announce the usage hint for how to adjust selected setting (change quick menu
   * item action).
   */
  private @Nullable String cachedAdjustSelectedSettingGestureNames;

  // Flattening granularities into the quick menu for all devices from TalkBack 9.1.
  // This index of GRANULARITY_XXX is used in granularity voice command array.
  public static final ImmutableList<Setting> SELECTOR_SETTINGS =
      ImmutableList.of(
          Setting.GRANULARITY_TYPO,
          Setting.ACTIONS,
          Setting.COPY_MODE,
          Setting.REPEAT_AND_TRANSLATE,
          // Puts table navigation granularities on top of the selector.
          Setting.GRANULARITY_ROW_COLUMN,
          Setting.GRANULARITY_ROW,
          Setting.GRANULARITY_COLUMN,
          Setting.GRANULARITY_CHARACTERS,
          Setting.GRANULARITY_WORDS,
          Setting.GRANULARITY_LINES,
          Setting.GRANULARITY_PARAGRAPHS,
          Setting.GRANULARITY_HEADINGS,
          Setting.GRANULARITY_CONTROLS,
          Setting.GRANULARITY_LINKS,
          Setting.GRANULARITY_WEB_LANDMARKS,
          Setting.GRANULARITY_WEB_BUTTONS,
          Setting.GRANULARITY_WEB_CHECKBOXES,
          Setting.GRANULARITY_WEB_EDITFIELDS,
          Setting.GRANULARITY_WEB_FOCUSABLES,
          Setting.GRANULARITY_WEB_H1,
          Setting.GRANULARITY_WEB_H2,
          Setting.GRANULARITY_WEB_H3,
          Setting.GRANULARITY_WEB_H4,
          Setting.GRANULARITY_WEB_H5,
          Setting.GRANULARITY_WEB_H6,
          Setting.GRANULARITY_WEB_GRAPHICS,
          Setting.GRANULARITY_WEB_LISTS,
          Setting.GRANULARITY_WEB_LISTITEMS,
          Setting.GRANULARITY_WEB_TABLES,
          Setting.GRANULARITY_WEB_COMBOBOXES,
          Setting.GRANULARITY_WEB_VISITED_LINKS,
          Setting.GRANULARITY_WEB_UNVISITED_LINKS,
          Setting.GRANULARITY_WEB_RADIOS,
          Setting.GRANULARITY_WINDOWS,
          Setting.GRANULARITY_CONTAINERS,
          Setting.GRANULARITY_SEARCH,
          Setting.GRANULARITY_DEFAULT,
          Setting.VERTICAL_NAVIGATION,
      …19118 tokens truncated…d, displayText, hint);
    showQuickMenuActionOverlay(eventId, displayText);
  }

  /** Changes to the previous or next installed language. */
  private void changeLanguage(EventId eventId, boolean isNext) {
    // The reading control is hidden while a voice profile is in use, but it stays selected if the
    // profile was chosen in settings.
    if (VoiceProfiles.isProfileActive()) {
      String displayText = context.getString(R.string.spoken_language_unavailable_with_profile);
      announceSetting(eventId, displayText, getSelectSettingGestures());
      showQuickMenuActionOverlay(eventId, displayText);
      return;
    }
    pipeline.returnFeedback(eventId, Feedback.language(isNext ? NEXT_LANGUAGE : PREVIOUS_LANGUAGE));
    announceSetting(
        eventId,
        actorState.getLanguageState().getCurrentLanguageString(),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(eventId, actorState.getLanguageState().getCurrentLanguageString());
  }

  /** Set the current verbosity preset to the previous or next verbosity preset. */
  private void changeVerbosity(EventId eventId, boolean isNext) {
    List<String> verbosities =
        Arrays.asList(context.getResources().getStringArray(R.array.pref_verbosity_preset_values));
    int verbositiesSize = verbosities.size();
    if (verbositiesSize == 0) {
      return;
    }

    // Get the index of the current verbosity preset.
    String verbosityPresetKey = context.getString(R.string.pref_verbosity_preset_key);
    String currentVerbosity =
        prefs.getString(
            verbosityPresetKey, context.getString(R.string.pref_verbosity_preset_value_default));
    int index = verbosities.indexOf(currentVerbosity);

    // Change the verbosity preset.
    if (isNext) {
      index++;
      if (index >= verbositiesSize || index < 0) {
        // User has reached end of list so wrap to start of list.
        index = 0;
      }
    } else { // Choose previous preset.
      index--;
      if (index >= verbositiesSize || index < 0) {
        // User has reached start of list so wrap to end of list.
        index = verbositiesSize - 1;
      }
    }

    String newVerbosity = verbosities.get(index);
    analytics.onManuallyChangeSetting(verbosityPresetKey, TalkBackAnalytics.TYPE_SELECTOR);
    prefs.edit().putString(verbosityPresetKey, newVerbosity).apply();
    // Announce new preset. If the TalkBackVerbosityPreferencesActivity fragment is visible,
    // the fragment's OnSharedPreferenceChangeListener.onSharedPreferenceChanged will also call this
    // method. SpeechController will then deduplicate the announcement event so only one is spoken.
    announceSetting(
        eventId,
        VerbosityPreferences.getVerbosityChangeAnnouncement(newVerbosity, context),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(
        eventId, VerbosityPreferences.verbosityValueToName(newVerbosity, context));
  }

  /** Moves how emoji are read to the next or previous choice of the Emoji setting. */
  private void cycleEmojiSpeech(EventId eventId, boolean isNext) {
    String[] values = context.getResources().getStringArray(R.array.pref_emoji_speech_values);
    String[] entries = context.getResources().getStringArray(R.array.pref_emoji_speech_entries);
    String current =
        SharedPreferencesUtils.getStringPref(
            prefs,
            context.getResources(),
            R.string.pref_emoji_speech_key,
            R.string.pref_emoji_speech_default);
    int index = Math.max(0, Arrays.asList(values).indexOf(current));
    index = (index + (isNext ? 1 : values.length - 1)) % values.length;
    SharedPreferencesUtils.putStringPref(
        prefs, context.getResources(), R.string.pref_emoji_speech_key, values[index]);
    announceSetting(
        eventId,
        context.getString(R.string.emoji_speech_state, entries[index]),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(eventId, entries[index]);
  }

  /** Returns the way of typing whose keyboard echo a keyboard echo reading control changes. */
  private static TypingInput keyboardEchoInput(Setting setting) {
    return switch (setting) {
      case KEYBOARD_ECHO_PHYSICAL -> TypingInput.PHYSICAL_KEYBOARD;
      default -> TypingInput.ON_SCREEN_KEYBOARD;
    };
  }

  /** Moves the keyboard echo for {@code input} to the next or previous choice. */
  private void cycleKeyboardEcho(EventId eventId, TypingInput input, boolean isNext) {
    Resources res = context.getResources();
    int echo = KeyboardEchoSettings.cycle(prefs, res, input, isNext);
    announceSetting(
        eventId, KeyboardEchoSettings.state(res, input, echo), getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(eventId, KeyboardEchoSettings.choiceName(res, echo));
  }

  /** Cycle the punctuation verbosity to the next value */
  public void cycleSpeakPunctuationVerbosity(EventId eventId, int triggerAction) {
    Resources res = context.getResources();
    int punctuationLevel =
        Integer.parseInt(
            SharedPreferencesUtils.getStringPref(
                prefs,
                res,
                R.string.pref_punctuation_verbosity,
                R.string.pref_punctuation_verbosity_default));
    analytics.onManuallyChangeSetting(
        res.getString(R.string.pref_punctuation_verbosity), triggerAction);
    punctuationLevel++;
    punctuationLevel %= 3;
    String[] punctuationValues =
        context.getResources().getStringArray(R.array.pref_punctuation_values);
    String[] punctuationEntries =
        context.getResources().getStringArray(R.array.pref_punctuation_entries);
    SharedPreferencesUtils.putStringPref(
        prefs, res, R.string.pref_punctuation_verbosity, punctuationValues[punctuationLevel]);
    announceSetting(
        eventId,
        context.getString(R.string.punctuation_state, punctuationEntries[punctuationLevel]),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(eventId, punctuationEntries[punctuationLevel]);
  }

  /** Validate the verbosity index and set verbosity string if necessary */
  public void changeVerbosity(EventId eventId, int newIndex) {
    List<String> verbosities =
        Arrays.asList(context.getResources().getStringArray(R.array.pref_verbosity_preset_values));
    if (newIndex >= verbosities.size() || newIndex < 0) {
      return;
    }

    // Get the index of the current verbosity preset.
    String verbosityPresetKey = context.getString(R.string.pref_verbosity_preset_key);
    String currentVerbosity =
        prefs.getString(
            verbosityPresetKey, context.getString(R.string.pref_verbosity_preset_value_default));
    int currentIndex = verbosities.indexOf(currentVerbosity);
    String newVerbosity = verbosities.get(newIndex);
    if (currentIndex != newIndex) {
      prefs.edit().putString(verbosityPresetKey, newVerbosity).apply();
    }
    announceSetting(
        eventId,
        VerbosityPreferences.getVerbosityChangeAnnouncement(newVerbosity, context),
        getAdjustSelectedSettingGestures());
  }

  private void toggleTextFormatting(EventId eventId) {
    Resources res = context.getResources();
    boolean textFormattingOn =
        SharedPreferencesUtils.getBooleanPref(
            prefs, res, R.string.pref_formatting_inline_key, R.bool.pref_formatting_inline_default);
    boolean toggleTextFormatting = !textFormattingOn;
    analytics.onManuallyChangeSetting(
        res.getString(R.string.pref_formatting_inline_key), TalkBackAnalytics.TYPE_SELECTOR);
    SharedPreferencesUtils.putBooleanPref(
        prefs, res, R.string.pref_formatting_inline_key, toggleTextFormatting);
    announceSetting(
        eventId,
        context.getString(
            R.string.text_formatting_state,
            toggleTextFormatting
                ? context.getString(R.string.value_on)
                : context.getString(R.string.value_off)),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(
        eventId, context.getString(toggleTextFormatting ? R.string.value_on : R.string.value_off));
  }

  /** Shows (brightens) or hides (dims) the screen. */
  private void showOrHideScreen(EventId eventId) {
    if (actorState.getDimScreen().isDimmingEnabled()) {
      // Brightens the screen.
      pipeline.returnFeedback(eventId, Feedback.dimScreen(BRIGHTEN));
      announceSetting(
          eventId,
          context.getString(R.string.screen_brightness_restored),
          getAdjustSelectedSettingGestures());
      showQuickMenuActionOverlay(eventId, context.getString(R.string.shortcut_disable_dimming));
    } else {
      // Dims the screen.
      pipeline.returnFeedback(eventId, Feedback.dimScreen(DIM));
      // TODO Speaks the usage hint when dim screen dialog is dismissed.
      showQuickMenuActionOverlay(eventId, context.getString(R.string.shortcut_enable_dimming));
    }
  }

  /** Switch on or off audio ducking. */
  private void switchOnOrOffAudioDucking(EventId eventId) {
    Resources res = context.getResources();
    boolean audioFocusOn =
        SharedPreferencesUtils.getBooleanPref(
            prefs, res, R.string.pref_use_audio_focus_key, R.bool.pref_use_audio_focus_default);
    analytics.onManuallyChangeSetting(
        res.getString(R.string.pref_use_audio_focus_key), TalkBackAnalytics.TYPE_SELECTOR);
    SharedPreferencesUtils.putBooleanPref(
        prefs, res, R.string.pref_use_audio_focus_key, !audioFocusOn);
    announceSetting(
        eventId,
        context.getString(
            R.string.audio_focus_state,
            !audioFocusOn
                ? context.getString(R.string.value_on)
                : context.getString(R.string.value_off)),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(
        eventId, context.getString(!audioFocusOn ? R.string.value_on : R.string.value_off));
  }

  private void switchOnOrOffAutoTranslation(EventId eventId) {
    boolean on = !TranslationSettings.INSTANCE.auto(prefs);
    TranslationSettings.INSTANCE.setAuto(prefs, on);
    String state = context.getString(on ? R.string.value_on : R.string.value_off);
    announceSetting(
        eventId,
        context.getString(R.string.translation_auto_state, state),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(eventId, state);
  }

  /** Perform scrolling to previous/next page. */
  private void scrollForwardBackward(EventId eventId, boolean isNext) {
    pipeline.returnFeedback(eventId, Feedback.focusDirection(isNext ? PREVIOUS_PAGE : NEXT_PAGE));
  }

  private EventId lastChangeAccessibilityEventId;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final VolumeChangedListener a11yVolumeChangedListener =
      new VolumeChangedListener() {

        @Override
        public void onVolumeChanged(int type, int value, int prevValue, int percent) {
          if (type != AudioManager.STREAM_ACCESSIBILITY) {
            return;
          }

          String displayText =
              context.getString(
                  value < prevValue
                      ? R.string.template_accessibility_volume_change_decrease
                      : R.string.template_accessibility_volume_change_increase,
                  percent);
          announceSetting(lastChangeAccessibilityEventId, displayText, getAdjustSelectedSettingGestures());
          TalkBackUI.Item item =
              value < prevValue
                  ? Item.ITEM_ACCESSIBILITY_VOLUME_DECREASE
                  : Item.ITEM_ACCESSIBILITY_VOLUME_INCREASE;
          showQuickMenuActionOverlay(lastChangeAccessibilityEventId, displayText, item);

          handler.post(() -> volumeMonitor.removeVolumeChangedListener(a11yVolumeChangedListener));
        }
      };

  /** Perform Accessibility volume change. */
  public void changeAccessibilityVolume(EventId eventId, boolean decreaseVolume) {
    boolean result =
        pipeline.returnFeedback(
            eventId,
            Feedback.adjustVolume(
                decreaseVolume ? DECREASE_VOLUME : INCREASE_VOLUME, STREAM_TYPE_ACCESSIBILITY));
    if (result) {
      refreshVolumeChangedListener(eventId);
    } else {
      String displayText =
          atLimit(
              decreaseVolume
                  ? R.string.template_volume_change_minimum
                  : R.string.template_volume_change_maximum,
              context.getString(
                  R.string.tb_template_percent, String.valueOf(accessibilityVolumePercent())));
      announceSetting(eventId, displayText, getAdjustSelectedSettingGestures());
      TalkBackUI.Item item =
          decreaseVolume
              ? Item.ITEM_ACCESSIBILITY_VOLUME_MINIMUM
              : Item.ITEM_ACCESSIBILITY_VOLUME_MAXIMUM;
      showQuickMenuActionOverlay(eventId, displayText, item);
    }
  }

  private void changeBrightness(EventId eventId, boolean increase) {
    String displayText;
    if (!ScreenBrightness.canWrite(context)) {
      displayText = context.getString(R.string.brightness_needs_permission);
      ScreenBrightness.requestWritePermission(context);
    } else if (ScreenBrightness.isAdaptive(context)) {
      displayText = context.getString(R.string.brightness_adaptive_on);
    } else if (ScreenBrightness.adjust(context, increase)) {
      displayText =
          context.getString(
              R.string.template_brightness_changed, ScreenBrightness.getPercent(context));
    } else {
      displayText =
          atLimit(
              increase
                  ? R.string.template_volume_change_maximum
                  : R.string.template_volume_change_minimum,
              context.getString(
                  R.string.template_brightness_changed, ScreenBrightness.getPercent(context)));
    }
    announceSetting(eventId, displayText, getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(eventId, displayText);
  }

  private void changeLiftToActivate(EventId eventId, boolean isNext) {
    LiftToActivateMode current =
        LiftToActivateMode.fromPrefValue(
            prefs.getString(context.getString(R.string.pref_lift_to_activate_key), null));
    LiftToActivateMode updated = isNext ? current.next() : current.previous();
    prefs
        .edit()
        .putString(context.getString(R.string.pref_lift_to_activate_key), updated.getPrefValue())
        .apply();

    String modeName =
        context.getResources().getStringArray(R.array.pref_lift_to_activate_entries)[
            updated.ordinal()];
    String displayText = context.getString(R.string.template_lift_to_activate_changed, modeName);
    announceSetting(eventId, displayText, getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(eventId, displayText);
  }

  /**
   * Says that a setting is at its lowest or highest, as {@code limitResId} does, followed by its
   * value, so that each swipe past the end still says what the setting is.
   */
  private String atLimit(@StringRes int limitResId, CharSequence value) {
    return context.getString(R.string.template_setting_limit, context.getString(limitResId), value);
  }

  private int accessibilityVolumePercent() {
    AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    int min =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? audioManager.getStreamMinVolume(AudioManager.STREAM_ACCESSIBILITY)
            : 0;
    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY);
    int volume = audioManager.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY);
    return max > min ? Math.round(100f * (volume - min) / (max - min)) : 100;
  }

  private void announcement(int resourceId) {
    announcement(context.getString(resourceId));
  }

  private void announcement(CharSequence text) {
    if (pipeline == null) {
      return;
    }
    pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.speech(
            text.toString(),
            SpeakOptions.create()
                .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
                .setFlags(
                    FeedbackItem.FLAG_NO_HISTORY
                        | FeedbackItem.FLAG_FORCE_FEEDBACK_ALL
                        | FeedbackItem.FLAG_SKIP_DUPLICATE)));
  }

  private void refreshVolumeChangedListener(EventId eventId) {
    lastChangeAccessibilityEventId = eventId;
    volumeMonitor.removeVolumeChangedListener(a11yVolumeChangedListener);
    volumeMonitor.addVolumeChangedListener(a11yVolumeChangedListener);
  }

  private void switchWrapAroundOnOrOff(EventId eventId) {
    boolean switchedValue =
        !SharedPreferencesUtils.getBooleanPref(
            prefs,
            context.getResources(),
            R.string.pref_wrap_around_key,
            R.bool.pref_wrap_around_default);
    SharedPreferencesUtils.putBooleanPref(
        prefs, context.getResources(), R.string.pref_wrap_around_key, switchedValue);
    announceSetting(
        eventId,
        context.getString(switchedValue ? R.string.wrap_around_on : R.string.wrap_around_off),
        getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(
        eventId, context.getString(switchedValue ? R.string.value_on : R.string.value_off));
  }

  private void switchTellingTimeOnOrOff(EventId eventId) {
    analytics.onManuallyChangeSetting(
        context.getString(R.string.pref_speak_time_key), TalkBackAnalytics.TYPE_SELECTOR);

    boolean switchedValue =
        !SharedPreferencesUtils.getBooleanPref(
            prefs,
            context.getResources(),
            R.string.pref_speak_time_key,
            R.bool.pref_tell_time_default);
    SharedPreferencesUtils.putBooleanPref(
        prefs, context.getResources(), R.string.pref_speak_time_key, switchedValue);
    String announcementText;
    if (switchedValue) {
      announcementText = context.getString(R.string.speak_time_on);
    } else {
      announcementText = context.getString(R.string.speak_time_off);
    }
    announceSetting(eventId, announcementText, getAdjustSelectedSettingGestures());
    showQuickMenuActionOverlay(
        eventId, context.getString(switchedValue ? R.string.value_on : R.string.value_off));
  }

  private void changeAction(EventId eventId, boolean isNext) {
    CharSequence displayText;
    @Nullable AccessibilityNodeInfoCompat node =
        accessibilityFocusMonitor.getAccessibilityFocus(false);
    if (node != null) {
      List<ContextMenuItem> menuItems = new ArrayList<>();
      int currentActionIndex = populateActionItemsForNode(node, menuItems);
      // This could happen when the accessibility focused node changed.
      int actionSize = menuItems.size();
      if (actionSize == 0) {
        announceSetting(
            eventId, context.getString(R.string.no_action_available), getSelectSettingGestures());
        return;
      }
      final ContextMenuItem item;
      if (currentActionIndex == -1) {
        item = menuItems.get(0);
      } else {
        if (isNext) {
          item = menuItems.get((currentActionIndex + 1) % actionSize);
        } else {
          item = menuItems.get((currentActionIndex - 1 + actionSize) % actionSize);
        }
      }
      currentActionId = item.getContextMenuItemId();
      displayText = item.getTitle();

      if (currentActionId.itemId() == AccessibilityNodeInfoCompat.ACTION_CLICK) {
        requestServiceHandlesDoubleTap(eventId, false);
      } else {
        // Actions request FLAG_SERVICE_HANDLES_DOUBLE_TAP to allow triggering by double-tap.
        requestServiceHandlesDoubleTap(eventId, true);
      }

      if (displayText == null) {
        displayText = context.getString(R.string.value_unlabelled);
      }

      announceSetting(
          eventId,
          displayText,
          item.getItemId() == R.id.typo_suggestions_menu
              ? context.getString(R.string.use_spelling_suggestion_hint)
              : context.getString(R.string.use_action_hint));
      showQuickMenuActionOverlay(eventId, displayText);
    }
  }

  /**
   * Populate eligible actions for the node. If this node doesn't have any eligible actions, search
   * it parents until eligible actions are found or until all the parents are searched.
   *
   * @param node current node.
   * @param menuItems list of eligible action.
   * @return the index of the action that has the same id with {@code currentActionIndex}, otherwise
   *     -1
   */
  private int populateActionItemsForNode(
      @Nullable AccessibilityNodeInfoCompat node, List<ContextMenuItem> menuItems) {
    if (node == null) {
      return -1;
    }

    List<ContextMenuItem> actions =
        nodeMenuCreator.getNodeMenuByRule(
            RULE_CUSTOM_ACTION, context, node, /* includeAncestors= */ true);
    if (actions.isEmpty()) {
      return -1;
    }

    // Add default action ACTION_CLICK to the menu.
    menuItems.add(
        ContextMenu.createMenuItem(
            context,
            Menu.NONE,
            AccessibilityNodeInfoCompat.ACTION_CLICK,
            Menu.NONE,
            context.getString(R.string.shortcut_perform_click_action)));

    int currentActionIndex = 0; // Point to the default action "Click".
    for (ContextMenuItem action : actions) {
      if (action.getContextMenuItemId().equals(currentActionId)) {
        currentActionIndex = menuItems.size();
      }
      menuItems.add(action);
    }
    return currentActionIndex;
  }

  private boolean isContextualSetting(Setting setting) {
    if (setting == null) {
      return false;
    }
    return contextualSettings.stream().anyMatch((s) -> s.getSetting() == setting);
  }

  private boolean isContextualSettingNotSupported(
      Setting setting, AccessibilityNodeInfoCompat node) {
    if (setting == null) {
      return false;
    }
    return contextualSettings.stream()
        .anyMatch((s) -> s.getSetting() == setting && !s.isNodeSupportSetting(context, node));
  }

  private void requestServiceHandlesDoubleTap(EventId eventId, boolean enableFlag) {
    if (enableFlag) {
      if (!hasRequestServiceHandlesDoubleTap) {
        pipeline.returnFeedback(
            eventId, Feedback.requestServiceFlag(ENABLE_FLAG, FLAG_SERVICE_HANDLES_DOUBLE_TAP));
        hasRequestServiceHandlesDoubleTap = true;
      }
    } else {
      if (hasRequestServiceHandlesDoubleTap) {
        pipeline.returnFeedback(
            eventId, Feedback.requestServiceFlag(DISABLE_FLAG, FLAG_SERVICE_HANDLES_DOUBLE_TAP));
        hasRequestServiceHandlesDoubleTap = false;
      }
    }
  }

  private Optional<ContextualSetting> findContextualSetting(Setting setting) {
    return contextualSettings.stream().filter((s) -> s.getSetting() == setting).findFirst();
  }

  private Optional<ContextualSetting> getMatchedContextualSettingForActivation(
      Context context, AccessibilityNodeInfoCompat node, List<Setting> hiddenSettings) {
    Optional<ContextualSetting> matchedSetting =
        contextualSettings.stream()
            .filter(
                (s) ->
                    !hiddenSettings.contains(s.getSetting())
                        && s.shouldActivateSetting(context, node)
                        && s.isNodeSupportSetting(context, node))
            .findFirst();
    Optional<ContextualSetting> currentSetting = findContextualSetting(getCurrentSetting(context));
    if (matchedSetting.isPresent()
        && currentSetting.isPresent()
        && currentSetting.get().isNodeSupportSetting(context, node)) {
      int matchedSettingIndex = contextualSettings.indexOf(matchedSetting.get());
      int currentSettingIndex = contextualSettings.indexOf(currentSetting.get());
      if (currentSettingIndex >= 0 && currentSettingIndex < matchedSettingIndex) {
        // The current setting still works and has higher priority than the matched setting, so no
        // need to activate the matched setting.
        LogUtils.w(
            TAG,
            "Return, the current setting %s has higher priority than the matched setting %s.",
            currentSetting,
            matchedSetting);
        return Optional.empty();
      }
    }
    return matchedSetting;
  }

  /**
   * Resets the selector and removes non-useful preferences.
   *
   * <p>Resets the setting to granularity to ensure the behavior is the same as before.
   */
  public static void resetSelectorPreferences(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);

    // Remove legacy preferences
    String hasActivated = context.getString(R.string.pref_selector_first_time_activation_key);
    if (prefs.contains(hasActivated)) {
      prefs.edit().remove(hasActivated).apply();
      removeLegacyPreference(context);
    }
    String selectorActivationKey = context.getString(R.string.pref_selector_activation_key);
    if (prefs.contains(selectorActivationKey)) {
      prefs.edit().remove(selectorActivationKey).apply();
    }

    resetSelectedSetting(context);
    assignSelectorShortcuts(context);
    resetSelectorFilterValue(context);
  }

  /** Resets the selected setting to characters, then allows to read by character. */
  private static void resetSelectedSetting(Context context) {
    Setting defaultGranularitySetting = Setting.GRANULARITY_CHARACTERS;
    SharedPreferencesUtils.getSharedPreferences(context)
        .edit()
        .putString(
            context.getString(R.string.pref_current_selector_setting_key),
            context.getString(defaultGranularitySetting.prefValueResId))
        .putBoolean(context.getString(defaultGranularitySetting.prefKeyResId), true)
        .apply();
  }

  /** Assigns selector shortcuts by default. */
  private static void assignSelectorShortcuts(Context context) {
    String[] initialSelectorGestures =
        context.getResources().getStringArray(R.array.initial_selector_gestures);
    String[] selectorShortcutValues =
        context.getResources().getStringArray(R.array.selector_shortcut_values);

    if (initialSelectorGestures.length != selectorShortcutValues.length) {
      return;
    }

    Editor editor = SharedPreferencesUtils.getSharedPreferences(context).edit();
    for (int i = 0; i < initialSelectorGestures.length; i++) {
      // Assigns selector shortcuts to gestures.
      editor.putString(initialSelectorGestures[i], selectorShortcutValues[i]);
    }
    editor.apply();
  }

  private static void resetSelectorFilterValue(Context context) {
    Editor editor = SharedPreferencesUtils.getSharedPreferences(context).edit();
    for (Setting setting : Setting.values()) {
      editor.putBoolean(
          context.getString(setting.prefKeyResId),
          context.getResources().getBoolean(setting.defaultValueResId));
    }
    editor.apply();
  }

  /**
   * Removes selector/non-selector saved shortcuts.
   *
   * <p>From 9.0, the selector is always on and the backup preferences are unnecessary.
   *
   * <p>The method is for upgrading from a version earlier than 9.0.
   */
  private static void removeLegacyPreference(Context context) {
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    String[] gestureShortcutKeys =
        context.getResources().getStringArray(R.array.pref_shortcut_keys);
    String selectorSavedKeySuffix = context.getString(R.string.pref_selector_saved_gesture_suffix);
    String notSelectorSavedKeySuffix =
        context.getString(R.string.pref_not_selector_saved_gesture_suffix);

    // Iterates through all the gestures and their shortcut assignments.
    for (String gestureShortcutKey : gestureShortcutKeys) {
      SharedPreferencesUtils.remove(
          prefs,
          gestureShortcutKey + selectorSavedKeySuffix,
          gestureShortcutKey + notSelectorSavedKeySuffix);
    }
  }

  private void showQuickMenuOverlay(EventId eventId, @Nullable CharSequence message) {
    String selectPreviousGroup = context.getString(R.string.shortcut_value_select_previous_group);
    String selectNextGroup = context.getString(R.string.shortcut_value_select_next_group);

    if (FormFactorUtils.isAndroidWear()) {
      // Watch never uses multi-finger and won't show surrounding icons.
      pipeline.returnFeedback(
          eventId,
          Feedback.showSelectorUI(
              SELECTOR_MENU_ITEM_OVERLAY_SINGLE_FINGER, message, /* showIcon= */ false));
    } else if (FormFactorUtils.isAndroidXr()) {
      // XR doesn't uses multi-finger gestures and won't show surrounding icons.
      pipeline.returnFeedback(
          eventId,
          Feedback.showSelectorUI(
              SELECTOR_MENU_ITEM_OVERLAY_SINGLE_FINGER, message, /* showIcon= */ false));
    } else if (FeatureSupport.isMultiFingerGestureSupported()
        && selectPreviousGroup.equals(
            prefs.getString(
                context.getString(R.string.pref_shortcut_3finger_swipe_left_key),
                context.getString(R.string.pref_shortcut_3finger_swipe_left_default)))
        && selectNextGroup.equals(
            prefs.getString(
                context.getString(R.string.pref_shortcut_3finger_swipe_right_key),
                context.getString(R.string.pref_shortcut_3finger_swipe_right_default)))) {
      // Shows a selector overlay with 3-finger swipe left/right gesture icons.
      pipeline.returnFeedback(
          eventId,
          Feedback.showSelectorUI(
              SELECTOR_MENU_ITEM_OVERLAY_MULTI_FINGER, message, /* showIcon= */ true));
    } else {
      boolean isSwipeUpDownAndDownUpForSelector =
          selectPreviousSetting.equals(
                  prefs.getString(
                      context.getString(R.string.pref_shortcut_up_and_down_key),
                      context.getString(R.string.pref_shortcut_up_and_down_default)))
              && selectNextSetting.equals(
                  prefs.getString(
                      context.getString(R.string.pref_shortcut_down_and_up_key),
                      context.getString(R.string.pref_shortcut_down_and_up_default)));
      // Shows a selector overlay with swipe up-down/down-up gesture icons if swiping
      // up-down/down-up gestures are for selecting selector items. Otherwise, shows a selector
      // overlay without gesture icons.
      pipeline.returnFeedback(
          eventId,
          Feedback.showSelectorUI(
              SELECTOR_MENU_ITEM_OVERLAY_SINGLE_FINGER,
              message,
              isSwipeUpDownAndDownUpForSelector));
    }
  }

  private void showQuickMenuActionOverlay(EventId eventId, CharSequence message) {
    showQuickMenuActionOverlay(eventId, message, Item.ITEM_UNSPECIFIED);
  }

  private void showQuickMenuActionOverlay(
      EventId eventId, CharSequence message, TalkBackUI.Item item) {
    if (actorState.getDimScreen().isDimmingEnabled()) {
      // No need to show the UI when the screen is dimming.
      return;
    }

    String selectedSettingPreviousAction =
        context.getString(R.string.shortcut_value_selected_setting_previous_action);
    String selectedSettingNextAction =
        context.getString(R.string.shortcut_value_selected_setting_next_action);
    boolean isSwipeUpDownForSelector =
        !FormFactorUtils.isAndroidWear()
            && selectedSettingPreviousAction.equals(
                prefs.getString(
                    context.getString(R.string.pref_shortcut_up_key),
                    context.getString(R.string.pref_shortcut_up_default)))
            && selectedSettingNextAction.equals(
                prefs.getString(
                    context.getString(R.string.pref_shortcut_down_key),
                    context.getString(R.string.pref_shortcut_down_default)));
    // Shows a selector overlay with swipe up/down gesture icons if swiping up/down gestures are
    // for adjusting selected settings. Otherwise, shows a selector overlay without gesture icons.
    pipeline.returnFeedback(
        eventId,
        Feedback.showSelectorUI(
            SELECTOR_ITEM_ACTION_OVERLAY, message, isSwipeUpDownForSelector, item));
  }
}
