package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.MenuGestureActions;
import com.google.android.accessibility.utils.FormFactorUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "vi")
public class GestureActionCategoryTest {
  private Context context;
  private GestureListPreference.ActionItem[] actions;
  private Map<String, String> categoryByAction;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    FormFactorUtils.initialize(context);
    actions = new GestureListPreference(context, null).getActionItems();
    categoryByAction = new HashMap<>();
    String category = "";
    for (GestureListPreference.ActionItem action : actions) {
      if (action.viewType == GestureListPreference.TYPE_TITLE) {
        category = action.text;
      } else {
        categoryByAction.put(action.value, category);
      }
    }
  }

  @Test
  public void specialActionsHaveFocusedCategories() {
    assertCategory(R.string.shortcut_value_copy_focused, R.string.gesture_group_clipboard);
    assertCategory(R.string.shortcut_value_copy_more, R.string.gesture_group_clipboard);
    assertCategory(R.string.shortcut_value_clear_all_text, R.string.shortcut_title_text_editing);
    assertCategory(R.string.shortcut_value_show_custom_actions, R.string.shortcut_title_menu_control);
    assertCategory(R.string.shortcut_value_favorites, R.string.gesture_group_menu_text);
    assertCategory(R.string.shortcut_value_screen_recognition, R.string.gesture_group_screen_recognition);
    assertCategory(R.string.shortcut_value_subtitles, R.string.gesture_group_screen_recognition);
    assertCategory(R.string.shortcut_value_increase_volume, R.string.gesture_group_audio_media);
    assertCategory(R.string.shortcut_value_media_next, R.string.gesture_group_audio_media);
    assertCategory(R.string.shortcut_value_read_notifications, R.string.gesture_group_notifications_status);
    assertCategory(R.string.shortcut_value_monitor_focus, R.string.gesture_group_touch_accessibility);
    assertCategory(R.string.shortcut_value_task_assistant, R.string.gesture_group_utilities);
  }

  @Test
  public void menuActionsAreAssignedToSmallerGroupsWithoutLosingTheirValues() {
    String navigation = context.getString(R.string.gesture_group_menu_navigation);
    String feedback = context.getString(R.string.gesture_group_menu_speech);
    assertEquals(navigation, categoryByAction.get("MENU_ACTION:typo_suggestions_menu"));
    assertEquals(feedback, categoryByAction.get("MENU_ACTION:audio_ducking"));
    for (MenuGestureActions.Entry entry : MenuGestureActions.ENTRIES) {
      assertNotNull(context.getString(entry.label), categoryByAction.get(entry.value(context)));
    }
  }

  @Test
  public void otherIsSmallAndGroupLabelsAreUnique() {
    String other = context.getString(R.string.shortcut_title_special_features);
    int otherCount = 0;
    Set<String> titles = new HashSet<>();
    for (GestureListPreference.ActionItem action : actions) {
      if (action.viewType == GestureListPreference.TYPE_TITLE) {
        assertTrue(action.text, titles.add(action.text));
      }
      if (other.equals(categoryByAction.get(action.value))
          && action.viewType == GestureListPreference.TYPE_ACTION_ITEM) {
        otherCount++;
      }
    }
    assertTrue("Other has " + otherCount + " actions", otherCount <= 4);
  }

  private void assertCategory(int value, int category) {
    assertEquals(context.getString(category), categoryByAction.get(context.getString(value)));
  }
}
