package com.google.android.accessibility.talkback.preference.base;

import com.google.android.accessibility.talkback.R;

public class NavigationFeedbackFragment extends VerbosityPrefFragment {
  public NavigationFeedbackFragment() {
    super(R.xml.navigation_feedback_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_navigation_spoken_feedback);
  }
}
