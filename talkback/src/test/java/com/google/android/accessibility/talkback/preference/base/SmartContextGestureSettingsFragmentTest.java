package com.google.android.accessibility.talkback.preference.base;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.SmartContextGestures;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SmartContextGestureSettingsFragmentTest {
  @Test
  public void migrationRestoresEnabledIntegrationsFromOldPreferenceStorage() {
    Context context = org.robolectric.RuntimeEnvironment.getApplication();
    SharedPreferences device = SharedPreferencesUtils.getSharedPreferences(context);
    SharedPreferences legacy = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
    device.edit().remove("smart_context_device_storage_migrated")
        .remove("smart_context_builtin_dialog").remove("smart_context_builtin_short_video").commit();
    legacy.edit().putBoolean("smart_context_builtin_dialog", true)
        .putBoolean("smart_context_builtin_short_video", true).commit();

    SmartContextGestureSettingsFragment.migrateLegacySettings(context);

    assertTrue(SmartContextGestures.isBuiltinEnabled(context, "dialog"));
    assertTrue(SmartContextGestures.isBuiltinEnabled(context, "short_video"));
  }

  @Test
  public void dynamicallyCreatedSwitchesPersistInServicePreferenceStorage() {
    ActivityController<FragmentActivity> controller =
        Robolectric.buildActivity(FragmentActivity.class);
    FragmentActivity activity = controller.get();
    activity.setTheme(R.style.TalkBackSettings);
    FormFactorUtils.initialize(activity);
    SharedPreferences device = SharedPreferencesUtils.getSharedPreferences(activity);
    device.edit().remove("smart_context_device_storage_migrated")
        .remove("smart_context_builtin_dialog").commit();
    controller.setup();
    SmartContextGestureSettingsFragment fragment = new SmartContextGestureSettingsFragment();
    activity.getSupportFragmentManager().beginTransaction()
        .replace(android.R.id.content, fragment).commitNow();

    SwitchPreferenceCompat dialog = fragment.findPreference("smart_context_builtin_dialog");
    assertTrue(dialog != null);
    dialog.setChecked(true);
    assertTrue(SmartContextGestures.isBuiltinEnabled(activity, "dialog"));
    dialog.setChecked(false);
    assertFalse(SmartContextGestures.isBuiltinEnabled(activity, "dialog"));

    controller.pause().stop().destroy();
  }

  @Test
  public void migrationNeverOverwritesAlreadySavedSettings() {
    Context context = org.robolectric.RuntimeEnvironment.getApplication();
    SharedPreferences device = SharedPreferencesUtils.getSharedPreferences(context);
    SharedPreferences legacy = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
    device.edit().remove("smart_context_device_storage_migrated")
        .putBoolean("smart_context_builtin_dialog", false).commit();
    legacy.edit().putBoolean("smart_context_builtin_dialog", true).commit();

    SmartContextGestureSettingsFragment.migrateLegacySettings(context);

    assertFalse(SmartContextGestures.isBuiltinEnabled(context, "dialog"));
  }
}
