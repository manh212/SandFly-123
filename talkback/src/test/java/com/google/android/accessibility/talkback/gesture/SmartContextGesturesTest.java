package com.google.android.accessibility.talkback.gesture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.android.accessibility.talkback.gesture.SmartContextGestures.Rule;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Collections;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SmartContextGesturesTest {
  @Test
  public void exactMatchingIgnoresCaseButPreservesVietnameseAccents() {
    Rule rule = new Rule("Chương tiếp theo", "exact", "", false, true);
    assertTrue(rule.matches("CHƯƠNG TIẾP THEO"));
    assertFalse(rule.matches("Chuong tiep theo"));
    assertFalse(rule.matches("Đọc chương tiếp theo"));
  }

  @Test
  public void caseSensitiveMatchingRequiresSameCase() {
    Rule rule = new Rule("Next", "exact", "", true, true);
    assertTrue(rule.matches("Next"));
    assertFalse(rule.matches("NEXT"));
  }

  @Test
  public void flexibleMatchingSupportsContainsStartsAndEnds() {
    assertTrue(new Rule("tiếp theo", "contains", "", false, true)
        .matches("Đọc chương tiếp theo"));
    assertTrue(new Rule("Chương", "starts", "", false, true)
        .matches("Chương tiếp theo"));
    assertTrue(new Rule("tiếp theo", "ends", "", false, true)
        .matches("Chương tiếp theo"));
  }

  @Test
  public void relativeMatchingUsesSecondPhrase() {
    assertTrue(new Rule("Chương", "before", "tiếp theo", false, true)
        .matches("Đọc chương tiếp theo"));
    assertTrue(new Rule("tiếp theo", "after", "Chương", false, true)
        .matches("Đọc chương tiếp theo"));
    assertFalse(new Rule("Chương", "before", "tiếp theo", false, true)
        .matches("Đọc tiếp theo chương"));
  }

  @Test
  public void prefixSuffixModesRequireOtherText() {
    assertTrue(new Rule("tiếp theo", "prefix", "", false, true)
        .matches("Chương tiếp theo"));
    assertTrue(new Rule("Chương", "suffix", "", false, true)
        .matches("Chương tiếp theo"));
    assertFalse(new Rule("Chương", "prefix", "", false, true).matches("Chương"));
    assertFalse(new Rule("Chương", "suffix", "", false, true).matches("Chương"));
  }

  @Test
  public void disabledRulesNeverMatch() {
    assertFalse(new Rule("Next", "exact", "", false, false).matches("Next"));
  }
  @Test
  public void inCallButtonsMatchLabelsWithStateDescriptions() {
    assertTrue(SmartContextGestures.matchesBuiltinLabel(
        "tắt, Tắt tiếng, Nút chuyển", "Tắt tiếng"));
    assertTrue(SmartContextGestures.matchesBuiltinLabel(
        "tắt, Kết thúc, Nút chuyển", "Kết thúc"));
    assertTrue(SmartContextGestures.matchesBuiltinLabel(
        "End call, button", "End call"));
  }

  @Test
  public void builtinLabelsRequireWordBoundaries() {
    assertTrue(SmartContextGestures.matchesBuiltinLabel("OK, Nút", "OK"));
    assertFalse(SmartContextGestures.matchesBuiltinLabel("notification", "No"));
    assertFalse(SmartContextGestures.matchesBuiltinLabel("Muted", "Mute"));
    assertFalse(SmartContextGestures.matchesBuiltinLabel("Unmute", "Mute"));
  }

  @Test
  public void callScreenIdentificationAcceptsInCallUiButNotOrdinaryApplications() {
    assertTrue(SmartContextGestures.isCallUiPackage("com.android.incallui"));
    assertTrue(SmartContextGestures.isCallUiPackage("com.google.android.dialer"));
    assertFalse(SmartContextGestures.isCallUiPackage("com.ss.android.ugc.trill"));
    assertFalse(SmartContextGestures.isCallUiPackage("com.android.contacts"));
  }
  public static class TargetService extends TalkBackService {
    AccessibilityNodeInfo root;
    @Override public AccessibilityNodeInfo getRootInActiveWindow() { return root; }
    @Override public void onCreate() {}
  }

  @Test
  public void configuredRecordingRuleNeverFallsBackToPlaybackButton() {
    TargetService service = Robolectric.buildService(TargetService.class).get();
    android.content.SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    prefs.edit().clear().putBoolean("smart_context_builtin_media", true)
        .putString(SmartContextGestures.PREF_MAX_SCROLL, "0").commit();
    service.root = AccessibilityNodeInfo.obtain();
    service.root.setPackageName("com.facebook.orca");
    service.root.setVisibleToUser(true);
    AccessibilityNodeInfo play = AccessibilityNodeInfo.obtain();
    play.setPackageName("com.facebook.orca");
    play.setText("Phát tin nhắn thoại");
    play.setEnabled(true);
    play.setVisibleToUser(true);
    play.setClickable(true);
    play.setBoundsInScreen(new Rect(0, 100, 100, 150));
    Shadows.shadowOf(service.root).addChild(play);
    int[] clicks = {0};
    Shadows.shadowOf(play).setOnPerformActionListener((action, args) -> {
      if (action == AccessibilityNodeInfo.ACTION_CLICK) clicks[0]++;
      return true;
    });
    ReflectionHelpers.callInstanceMethod(service.root, "setSealed", ClassParameter.from(boolean.class, true));
    ReflectionHelpers.callInstanceMethod(play, "setSealed", ClassParameter.from(boolean.class, true));
    SmartContextGestures.addRule(service, "com.facebook.orca", true,
        new Rule("Ghi âm tin nhắn thoại", "exact", "", false, true));
    try {
      SmartContextGestures.perform(service, true);
      assertEquals(0, clicks[0]);
    } finally {
      SmartContextGestures.cancel();
      prefs.edit().clear().commit();
    }
  }

  @Test
  public void recordingRuleRequiresEveryWordAndNoAdditionalWords() {
    Rule rule = new Rule("Ghi âm tin nhắn thoại", "exact", "", false, true);
    assertTrue(rule.matches("Ghi âm tin nhắn thoại"));
    assertFalse(rule.matches("Tin nhắn thoại"));
    assertFalse(rule.matches("Phát tin nhắn thoại"));
    assertFalse(rule.matches("Ghi âm tin nhắn thoại mới"));
    assertFalse(rule.matches("Ghi âm thoại"));
  }

  @Test
  public void textRuleSwitchKeepsSavedRulesButDisablesTheirActions() {
    android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
    android.content.SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    String packageName = "com.example.smartcontext";
    SmartContextGestures.addRule(context, packageName, true,
        new Rule("Tiếp theo", "exact", "", false, true));
    try {
      prefs.edit().putBoolean(SmartContextGestures.PREF_TEXT_RULES_ENABLED, false).commit();
      assertTrue(SmartContextGestures.getEffectiveRules(context, packageName, true).isEmpty());
      assertEquals(1, SmartContextGestures.getRules(context, packageName, true).size());
      prefs.edit().putBoolean(SmartContextGestures.PREF_TEXT_RULES_ENABLED, true).commit();
      assertEquals(1, SmartContextGestures.getEffectiveRules(context, packageName, true).size());
    } finally {
      prefs.edit().remove(SmartContextGestures.PREF_TEXT_RULES_ENABLED)
          .remove("smart_context_rules_app_" + packageName + "_down")
          .remove("smart_context_mode_" + packageName + "_down").commit();
    }
  }

  @Test
  public void shortVideoIsRecognizedOnlyWhenItsIntegrationIsEnabled() {
    TargetService service = Robolectric.buildService(TargetService.class).get();
    android.content.SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(service);
    service.root = AccessibilityNodeInfo.obtain();
    service.root.setPackageName("com.zhiliaoapp.musically");
    service.root.setVisibleToUser(true);
    try {
      prefs.edit().putBoolean("smart_context_builtin_short_video", false).commit();
      assertFalse(SmartContextGestures.hasMatchingContext(service));
      prefs.edit().putBoolean("smart_context_builtin_short_video", true).commit();
      assertTrue(SmartContextGestures.hasMatchingContext(service));
    } finally {
      prefs.edit().remove("smart_context_builtin_short_video").commit();
    }
  }

  @Test
  public void speakerLabelsIncludeVietnameseAndEnglishAccessibilityDescriptions() {
    assertTrue(SmartContextGestures.matchesBuiltinLabel("Bật, Loa ngoài, Nút", "Loa ngoài"));
    assertTrue(SmartContextGestures.matchesBuiltinLabel("Speakerphone off", "Speakerphone"));
    assertFalse(SmartContextGestures.matchesBuiltinLabel("Speak now", "Speaker"));
  }

}
