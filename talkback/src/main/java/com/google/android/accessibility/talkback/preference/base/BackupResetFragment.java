package com.google.android.accessibility.talkback.preference.base;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;

public class BackupResetFragment extends TalkBackPreferenceFragment {
  public BackupResetFragment() {
    super(R.xml.backup_reset_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_backup_reset);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    ResponseSettingsReset.bind(this);
    Preference reset = findPreference("pref_reset_application");
    if (reset != null) {
      reset.setOnPreferenceClickListener(
          preference -> {
            Context context = requireContext();
            A11yAlertDialogWrapper.materialDialogBuilder(context, getParentFragmentManager())
                .setTitle(R.string.title_pref_reset_application)
                .setMessage(R.string.message_reset_application)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    R.string.reset_application_confirm,
                    (dialog, which) -> resetApplication(context))
                .create()
                .show();
            return true;
          });
    }
  }

  private static void resetApplication(Context context) {
    ActivityManager manager = context.getSystemService(ActivityManager.class);
    boolean cleared;
    try {
      cleared = manager != null && manager.clearApplicationUserData();
    } catch (SecurityException exception) {
      cleared = false;
    }
    if (!cleared) {
      Toast.makeText(context, R.string.reset_application_failed, Toast.LENGTH_LONG).show();
    }
  }
}
