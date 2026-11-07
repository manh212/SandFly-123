package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference;

public final class ForumEntryPreference extends AccessibilitySuitePreference {
  public ForumEntryPreference(Context context, AttributeSet attrs) { super(context, attrs); }

  @Override protected void onClick() {
    super.onClick();
    getContext().startActivity(new Intent(getContext(), ForumActivity.class));
  }
}
