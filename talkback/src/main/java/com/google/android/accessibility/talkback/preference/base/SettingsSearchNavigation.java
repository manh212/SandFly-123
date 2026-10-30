package com.google.android.accessibility.talkback.preference.base;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory;

public final class SettingsSearchNavigation {
  public static final String TARGET_KEY = "settings_search_target_key";

  public static void focusTarget(TalkbackBaseFragment fragment) {
    Bundle arguments = fragment.getArguments();
    if (arguments == null) {
      return;
    }
    String key = arguments.getString(TARGET_KEY, "");
    if (key.isEmpty()) {
      return;
    }
    Preference preference = fragment.findPreference(key);
    if (preference == null) {
      return;
    }
    if (preference instanceof AccessibilitySuitePreferenceCategory category) {
      category.setCollapsed(false);
    }
    for (PreferenceGroup parent = preference.getParent(); parent != null; parent = parent.getParent()) {
      if (parent instanceof AccessibilitySuitePreferenceCategory category) {
        category.setCollapsed(false);
      }
    }
    fragment.scrollToPreference(preference);
    RecyclerView list = fragment.getListView();
    if (list != null) {
      list.post(() -> focusRow(list, preference, 0));
    }
    arguments.remove(TARGET_KEY);
  }

  @SuppressLint("RestrictedApi")
  private static void focusRow(RecyclerView list, Preference preference, int attempt) {
    if (!list.isAttachedToWindow()) {
      return;
    }
    if (list.getAdapter() instanceof PreferenceGroupAdapter adapter) {
      int position = adapter.getPreferenceAdapterPosition(preference);
      RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
      if (holder != null) {
        View row = holder.itemView;
        row.requestFocus();
        row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        return;
      }
    }
    if (attempt < 3) {
      list.postDelayed(() -> focusRow(list, preference, attempt + 1), 100);
    }
  }

  private SettingsSearchNavigation() {}
}
