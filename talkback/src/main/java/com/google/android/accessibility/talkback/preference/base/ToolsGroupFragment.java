package com.google.android.accessibility.talkback.preference.base;

import com.google.android.accessibility.talkback.R;

public class ToolsGroupFragment extends TalkBackPreferenceFragment {
  public ToolsGroupFragment() {
    super(R.xml.settings_group_tools);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_tools);
  }
}
