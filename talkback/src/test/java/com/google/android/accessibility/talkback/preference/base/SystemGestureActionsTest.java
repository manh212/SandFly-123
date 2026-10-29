package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import com.google.android.accessibility.talkback.R;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SystemGestureActionsTest {
  @Test
  public void defaultResourcesKeepRequiredSystemActionsAssignable() {
    assertSystemActions();
  }

  @Test
  @Config(qualifiers = "vi")
  public void vietnameseResourcesKeepRequiredSystemActionsAssignable() {
    assertSystemActions();
    assertEquals(
        "Mở trung tâm điều khiển hệ thống",
        RuntimeEnvironment.getApplication().getString(R.string.shortcut_system_quick_settings));
  }

  private static void assertSystemActions() {
    Resources resources = RuntimeEnvironment.getApplication().getResources();
    String[] labels = resources.getStringArray(R.array.shortcut_system_actions);
    List<String> values =
        Arrays.asList(resources.getStringArray(R.array.shortcut_value_system_actions));
    assertEquals(labels.length, values.size());
    assertEquals(values.size(), new HashSet<>(values).size());
    int[][] requiredActions = {
      {R.string.shortcut_home, R.string.shortcut_value_home},
      {R.string.shortcut_back, R.string.shortcut_value_back},
      {R.string.shortcut_overview, R.string.shortcut_value_overview},
      {R.string.shortcut_notifications, R.string.shortcut_value_notifications},
      {R.string.shortcut_system_notifications, R.string.shortcut_value_system_notifications},
      {R.string.shortcut_quick_settings, R.string.shortcut_value_quick_settings},
      {R.string.shortcut_system_quick_settings, R.string.shortcut_value_system_quick_settings},
      {R.string.shortcut_lock_screen, R.string.shortcut_value_lock_screen},
      {R.string.shortcut_power_menu, R.string.shortcut_value_power_menu},
      {R.string.shortcut_take_screenshot, R.string.shortcut_value_take_screenshot}
    };
    for (int[] action : requiredActions) {
      String value = resources.getString(action[1]);
      int index = values.indexOf(value);
      assertTrue(value, index >= 0);
      assertEquals(value, resources.getString(action[0]), labels[index]);
      assertFalse(labels[index].trim().isEmpty());
    }
  }
}
