package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "vi")
public class SettingsSearchIndexTest {
  @Test
  public void substringSearchAcceptsOneLetterCaseAndVietnameseWithoutAccents() {
    assertTrue(SearchMatcher.matches("M", "Màn hình khóa"));
    assertTrue(SearchMatcher.matches("m", "Âm lượng giọng nói"));
    assertTrue(SearchMatcher.matches("man", "Màn hình chính"));
    assertTrue(SearchMatcher.matches("KHOA man", "Màn hình khóa"));
    assertTrue(SearchMatcher.matches("DIEU KHIEN", "Điều khiển đọc"));
    assertFalse(SearchMatcher.matches("wifi", "Màn hình chính"));
  }

  @Test
  public void everyMatchingResultIsReturnedWithoutAMinimumLengthOrResultLimit() {
    List<SettingsSearchIndex.Entry> entries = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      entries.add(new SettingsSearchIndex.Entry("Mục " + i, "", "", "Screen", "" + i, new Bundle(), false, ""));
    }
    assertEquals(250, SettingsSearchIndex.search(entries, "m").size());
    assertTrue(SettingsSearchIndex.search(entries, "không có").isEmpty());
    assertTrue(SettingsSearchIndex.search(entries, " ").isEmpty());
  }

  @Test
  public void indexCoversNestedSettingsGesturesCommandsKeyboardAndProgrammaticScreens() {
    Context context = RuntimeEnvironment.getApplication();
    List<SettingsSearchIndex.Entry> entries = build(context);
    assertFalse(entries.isEmpty());
    assertTrue(entries.stream().anyMatch(entry -> entry.key.equals("pref_reverse_swipe_gestures")));
    assertTrue(entries.stream().anyMatch(entry -> entry.fragment.endsWith("TalkBackKeyboardShortcutPreferenceFragment") && !entry.key.isEmpty()));
    assertTrue(entries.stream().anyMatch(entry -> entry.title.equals("Cử chỉ") && entry.path.equals("Điều hướng")));
    assertTrue(entries.stream().anyMatch(entry -> entry.title.equals("Phím tắt") && entry.path.equals("Điều hướng")));
    assertFalse(entries.stream().anyMatch(entry -> entry.fragment.endsWith("GesturesShortcutsGroupFragment")));
    assertTrue(entries.stream().anyMatch(entry -> entry.fragment.endsWith("TalkBackGestureShortcutPreferenceFragment") && !entry.actionQuery.isEmpty()));
    assertTrue(entries.stream().anyMatch(entry -> entry.fragment.endsWith("OnDeviceAiFragment")));
    assertTrue(entries.stream().anyMatch(entry -> entry.fragment.endsWith("StatusReadoutFragment") && entry.key.equals("BATTERY")));
    assertTrue(entries.stream().anyMatch(entry -> entry.fragment.endsWith("AiProviderFragment") && entry.arguments.getString("provider", "").equals("openai")));
    assertFalse(SettingsSearchIndex.search(entries, "màn").isEmpty());
    assertTrue(entries.stream().filter(entry -> !entry.actionQuery.isEmpty()).anyMatch(entry -> SearchMatcher.matches("màn hình chính", entry.title)));
  }

  @Test
  public void loggingAndFeedbackAreIndexedUnderTheirOwnSettingsPage() {
    Context context = RuntimeEnvironment.getApplication();
    List<SettingsSearchIndex.Entry> entries = build(context);
    for (String key : new String[]{context.getString(R.string.pref_debug_log_key),
        context.getString(R.string.pref_export_diagnostics_to_downloads_key), "support_compose", "support_latest_log", "support_inbox"}) {
      assertTrue(key, entries.stream().anyMatch(entry -> entry.key.equals(key)
          && entry.fragment.endsWith("LogAndReportsFragment")));
      assertFalse(key, entries.stream().anyMatch(entry -> entry.key.equals(key)
          && entry.fragment.endsWith("TutorialAndHelpFragment")));
    }
  }

  @Test
  public void selectorGroupsAreIndexedAtTheirCanonicalOwnerWhenSupported() {
    Context context = RuntimeEnvironment.getApplication();
    FormFactorUtils.initialize(context);
    List<SettingsSearchIndex.Entry> entries = build(context);
    if (SelectorGroupManager.isSupported()) {
      assertTrue(
          entries.stream()
              .anyMatch(
                  entry ->
                      entry.fragment.endsWith("SelectorGroupsFragment")
                          && entry.title.equals(context.getString(R.string.selector_groups_title))));
      assertFalse(
          entries.stream().anyMatch(entry -> entry.fragment.endsWith("SelectorMenuFragment")));
    }
  }

  @Test
  public void indexingDoesNotChangePreferencesAndKeepsDeveloperNoticeMetadata() {
    Context context = RuntimeEnvironment.getApplication();
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().putString("existing", "kept").commit();
    Map<String, ?> before = prefs.getAll();
    List<SettingsSearchIndex.Entry> entries = build(context);
    assertEquals(before, prefs.getAll());
    assertTrue(entries.stream().anyMatch(entry -> entry.fragment.endsWith("DeveloperPrefFragment") && entry.developer));
  }

  private static List<SettingsSearchIndex.Entry> build(Context context) {
    FormFactorUtils.initialize(context);
    SettingsSearchIndex.Builder builder = new SettingsSearchIndex.Builder(context);
    int pages = 0;
    while (builder.step()) {
      assertTrue("Settings graph must be finite", ++pages < 200);
    }
    return builder.entries();
  }
}

