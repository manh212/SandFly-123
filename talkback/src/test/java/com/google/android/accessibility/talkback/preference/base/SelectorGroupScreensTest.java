package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.widget.LinearLayout;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "vi")
@LooperMode(LooperMode.Mode.PAUSED)
public class SelectorGroupScreensTest {
  public static class TestActivity extends TalkBackPreferencesActivity {
    @Override
    protected boolean supportHatsSurvey() {
      return false;
    }
  }

  @Test
  public void createButtonIsFirstAndOpensDedicatedScreen() {
    ActivityController<TestActivity> controller = createActivity();
    try {
      TestActivity activity = controller.get();
      SelectorGroupsFragment groups = showGroups(activity);
      Preference create = groups.getPreferenceScreen().getPreference(0);
      assertEquals(activity.getString(R.string.sgm_add_group), create.getTitle().toString());
      assertEquals(1, CreateNewShortcut.actions(groups.getPreferenceScreen()).size());

      create.performClick();
      activity.getSupportFragmentManager().executePendingTransactions();
      shadowOf(Looper.getMainLooper()).idle();

      Fragment current = activity.getSupportFragmentManager().findFragmentById(android.R.id.content);
      assertTrue(current instanceof SelectorGroupCreateFragment);
      LinearLayout layout = (LinearLayout) current.requireView();
      assertTrue(layout.getChildAt(0) instanceof KeyboardSafeEditText);
      SelectorGroupCreateFragment createScreen = (SelectorGroupCreateFragment) current;
      assertNotNull(createScreen.findPreference("sgm_choose"));
      assertNotNull(createScreen.findPreference("sgm_create"));
      assertNotNull(createScreen.findPreference("sgm_cancel"));
    } finally {
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void groupOpensEditorAndItemListsWithoutSeparateSortAction() {
    ActivityController<TestActivity> controller = createActivity();
    try {
      TestActivity activity = controller.get();
      SelectorGroupsFragment groups = showGroups(activity);
      PreferenceCategory category = groups.findPreference("selector_navigation_groups");
      assertNotNull(category);
      assertTrue(category.getPreferenceCount() > 0);

      category.getPreference(0).performClick();
      activity.getSupportFragmentManager().executePendingTransactions();
      shadowOf(Looper.getMainLooper()).idle();

      Fragment current = activity.getSupportFragmentManager().findFragmentById(android.R.id.content);
      assertTrue(current instanceof SelectorGroupEditorFragment);
      SelectorGroupEditorFragment editor = (SelectorGroupEditorFragment) current;
      assertNotNull(editor.findPreference("sgm_rename"));
      assertNotNull(editor.findPreference("sgm_enabled"));
      assertNotNull(editor.findPreference("sgm_delete"));
      assertEquals(4, editor.getPreferenceScreen().getPreferenceCount());

      editor.findPreference("sgm_items").performClick();
      activity.getSupportFragmentManager().executePendingTransactions();
      shadowOf(Looper.getMainLooper()).idle();

      current = activity.getSupportFragmentManager().findFragmentById(android.R.id.content);
      assertTrue(current instanceof SelectorGroupItemsFragment);
      SelectorGroupItemsFragment items = (SelectorGroupItemsFragment) current;
      assertNotNull(items.findPreference("sgm_present"));
      assertNotNull(items.findPreference("sgm_available"));
      assertEquals(2, items.getPreferenceScreen().getPreferenceCount());
    } finally {
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void cancelCreationDoesNotChangeExistingGroups() {
    ActivityController<TestActivity> controller = createActivity();
    try {
      TestActivity activity = controller.get();
      SelectorGroupsFragment groups = showGroups(activity);
      int before = SelectorGroupManager.getGroups(activity).size();

      groups.getPreferenceScreen().getPreference(0).performClick();
      activity.getSupportFragmentManager().executePendingTransactions();
      SelectorGroupCreateFragment createScreen =
          (SelectorGroupCreateFragment)
              activity.getSupportFragmentManager().findFragmentById(android.R.id.content);
      createScreen.findPreference("sgm_cancel").performClick();
      activity.getSupportFragmentManager().executePendingTransactions();

      assertEquals(before, SelectorGroupManager.getGroups(activity).size());
    } finally {
      controller.pause().stop().destroy();
    }
  }

  private ActivityController<TestActivity> createActivity() {
    ActivityController<TestActivity> controller = Robolectric.buildActivity(TestActivity.class);
    TestActivity activity = controller.get();
    activity.setTheme(R.style.TalkBackSettings);
    FormFactorUtils.initialize(activity);
    SharedPreferencesUtils.getSharedPreferences(activity).edit().clear().commit();
    controller.setup();
    activity.getSupportFragmentManager().executePendingTransactions();
    shadowOf(Looper.getMainLooper()).idle();
    return controller;
  }

  private SelectorGroupsFragment showGroups(TestActivity activity) {
    SelectorGroupsFragment groups = new SelectorGroupsFragment();
    activity.getSupportFragmentManager()
        .beginTransaction()
        .replace(android.R.id.content, groups)
        .commitNow();
    shadowOf(Looper.getMainLooper()).idle();
    return groups;
  }
}
