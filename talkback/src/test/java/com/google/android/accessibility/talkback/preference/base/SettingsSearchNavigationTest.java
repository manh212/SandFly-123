package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.SearchView;
import androidx.fragment.app.Fragment;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
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
public class SettingsSearchNavigationTest {
  public static class TestActivity extends TalkBackPreferencesActivity {
    @Override protected boolean supportHatsSurvey() { return false; }
  }

  @Test
  public void searchIsFirstAndReturningKeepsQueryWhileClearingRestoresSettings() {
    ActivityController<TestActivity> controller = Robolectric.buildActivity(TestActivity.class);
    TestActivity activity = controller.get();
    activity.setTheme(R.style.TalkBackSettings);
    FormFactorUtils.initialize(activity);
    SharedPreferencesUtils.getSharedPreferences(activity).edit().clear().commit();
    controller.setup();
    try {
      shadowOf(Looper.getMainLooper()).idle();
      TalkBackPreferenceFragment main = (TalkBackPreferenceFragment) current(activity);
      LinearLayout layout = (LinearLayout) main.requireView();
      assertTrue(layout.getChildAt(0) instanceof SearchView);
      SearchView search = (SearchView) layout.getChildAt(0);
      int originalCount = main.getPreferenceScreen().getPreferenceCount();
      search.setQuery("dao nguoc", false);
      shadowOf(Looper.getMainLooper()).idle();
      assertTrue(main.getPreferenceScreen().getPreferenceCount() > 0);
      main.getPreferenceScreen().getPreference(0).performClick();
      activity.getSupportFragmentManager().executePendingTransactions();
      shadowOf(Looper.getMainLooper()).idle();
      assertTrue(current(activity) instanceof NavigationPrefFragment);
      assertNotNull(((NavigationPrefFragment) current(activity)).findPreference("pref_reverse_swipe_gestures"));
      activity.getSupportFragmentManager().popBackStackImmediate();
      shadowOf(Looper.getMainLooper()).idle();
      main = (TalkBackPreferenceFragment) current(activity);
      search = (SearchView) ((LinearLayout) main.requireView()).getChildAt(0);
      assertEquals("dao nguoc", search.getQuery().toString());
      search.setQuery("", false);
      assertEquals(originalCount, main.getPreferenceScreen().getPreferenceCount());
    } finally {
      controller.pause().stop().destroy();
    }
  }

  private static Fragment current(TestActivity activity) {
    return activity.getSupportFragmentManager().getFragments().stream()
        .filter(fragment -> fragment.isAdded() && fragment.getView() != null).findFirst().orElseThrow();
  }
}
