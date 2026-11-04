package com.google.android.accessibility.talkback.preference.base;

import android.content.Intent;
import android.os.Bundle;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.licensing.SupportActivity;

public final class LogAndReportsFragment extends TalkbackBaseFragment {
  public LogAndReportsFragment() { super(R.xml.log_and_reports_preferences); }
  @Override public CharSequence getTitle() { return getText(R.string.support_title); }
  @Override public void onCreatePreferences(Bundle state, String key) {
    super.onCreatePreferences(state, key);
    DiagnosticsPreferenceActions.bind(this);
    bind("support_compose", true, false);
    bind("support_latest_log", true, true);
    bind("support_inbox", false, false);
  }
  private void bind(String key, boolean compose, boolean log) {
    Preference preference = findPreference(key);
    if (preference != null) preference.setIntent(new Intent(requireContext(), SupportActivity.class)
        .putExtra("compose", compose).putExtra("latestLog", log));
  }
}
