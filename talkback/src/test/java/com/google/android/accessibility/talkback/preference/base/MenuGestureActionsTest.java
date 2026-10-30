package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.*;

import android.content.Context;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.contextmenu.TalkbackMenuProcessor;
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping;
import com.google.android.accessibility.talkback.gesture.MenuGestureActions;
import com.google.android.accessibility.utils.FormFactorUtils;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "vi")
public class MenuGestureActionsTest {
  private Context context;
  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    FormFactorUtils.initialize(context);
  }

  @Test public void everyConfiguredMenuItemHasAnAssignableGestureAction() {
    Set<Integer> registered = new HashSet<>();
    for (MenuGestureActions.Entry entry : MenuGestureActions.ENTRIES) registered.add(entry.menuId);
    assertTrue(registered.containsAll(TalkbackMenuProcessor.menuItemIds()));
    Set<String> choices = new HashSet<>();
    for (GestureListPreference.ActionItem action : new GestureListPreference(context, null).getActionItems()) {
      if (action.viewType == GestureListPreference.TYPE_ACTION_ITEM) choices.add(action.value);
    }
    for (MenuGestureActions.Entry entry : MenuGestureActions.ENTRIES) {
      assertTrue(context.getString(entry.label), choices.contains(entry.value(context)));
      assertFalse(GestureShortcutMapping.getActionString(context, entry.value(context)).isBlank());
    }
  }

  @Test public void clipboardAndSpeechHistoryAreListedOnceAndKeepTheirExistingValues() {
    String clipboard = context.getString(R.string.shortcut_value_clipboard_history);
    String speech = context.getString(R.string.shortcut_value_speech_history);
    int clipboardCount = 0, speechCount = 0;
    for (GestureListPreference.ActionItem action : new GestureListPreference(context, null).getActionItems()) {
      if (clipboard.equals(action.value)) clipboardCount++;
      if (speech.equals(action.value)) speechCount++;
    }
    assertEquals(1, clipboardCount);
    assertEquals(1, speechCount);
    assertEquals("Lịch sử bộ nhớ tạm", GestureShortcutMapping.getActionString(context, clipboard));
  }

  @Test public void menuRoutesUseStableNamesRatherThanGeneratedResourceNumbers() {
    String value = "MENU_ACTION:audio_ducking";
    assertEquals(R.id.audio_ducking, MenuGestureActions.menuId(context, value));
    assertEquals(0, MenuGestureActions.menuId(context, "MENU_ACTION:unknown"));
    assertEquals(0, MenuGestureActions.menuId(context, context.getString(R.string.shortcut_value_copy_focused)));
  }
}
