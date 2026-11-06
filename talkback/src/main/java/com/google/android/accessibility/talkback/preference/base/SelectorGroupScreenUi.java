package com.google.android.accessibility.talkback.preference.base;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.recyclerview.widget.RecyclerView;

final class SelectorGroupScreenUi {
  static final String GROUP_ID = "group_id";
  static final String RESULT = "selector_group_list_changed";
  static final String FOCUS_ID = "focus_id";
  static final String MESSAGE = "message";

  private SelectorGroupScreenUi() {}

  static void open(TalkbackBaseFragment source, TalkbackBaseFragment destination, Bundle args) {
    destination.setArguments(args);
    source.getParentFragmentManager()
        .beginTransaction()
        .replace(source.getId(), destination)
        .addToBackStack(null)
        .commit();
  }

  static void focus(PreferenceFragmentCompat fragment, String key, String announcement) {
    RecyclerView list = fragment.getListView();
    if (list == null) {
      return;
    }
    list.post(
        () -> {
          if (!fragment.isAdded()) {
            return;
          }
          Preference preference = fragment.findPreference(key);
          RecyclerView.Adapter<?> adapter = list.getAdapter();
          if (preference == null || !(adapter instanceof PreferenceGroup.PreferencePositionCallback)) {
            return;
          }
          int position =
              ((PreferenceGroup.PreferencePositionCallback) adapter)
                  .getPreferenceAdapterPosition(preference);
          if (position < 0) {
            return;
          }
          list.scrollToPosition(position);
          list.post(() -> focusRow(list, position, announcement, 3));
        });
  }
  private static void focusRow(
      RecyclerView list, int position, String announcement, int remainingAttempts) {
    RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
    if (holder == null && remainingAttempts > 0) {
      list.post(() -> focusRow(list, position, announcement, remainingAttempts - 1));
      return;
    }
    if (holder != null) {
      holder.itemView.performAccessibilityAction(
          AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }
    if (announcement != null && !announcement.isEmpty()) {
      list.announceForAccessibility(announcement);
    }
  }
}
