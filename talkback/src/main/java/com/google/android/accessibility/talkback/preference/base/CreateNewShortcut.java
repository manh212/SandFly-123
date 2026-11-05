package com.google.android.accessibility.talkback.preference.base;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import com.google.android.accessibility.talkback.R;
import java.util.ArrayList;
import java.util.List;

public final class CreateNewShortcut {
  private static final String CREATE_ACTION = "meridian_create_action";

  public static <T extends Preference> T mark(T preference) {
    preference.getExtras().putBoolean(CREATE_ACTION, true);
    return preference;
  }

  public static List<Preference> actions(PreferenceGroup group) {
    List<Preference> result = new ArrayList<>();
    collect(group, result);
    return result;
  }

  private CreateNewShortcut() {}

  public static boolean perform(TalkbackBaseFragment fragment) {
    List<Preference> actions = actions(fragment.getPreferenceScreen());
    if (actions.isEmpty()) return false;
    if (actions.size() == 1) {
      invoke(actions.get(0));
    } else {
      CharSequence[] titles = new CharSequence[actions.size()];
      for (int i = 0; i < actions.size(); i++) titles[i] = actions.get(i).getTitle();
      new AlertDialog.Builder(fragment.requireContext())
          .setTitle(R.string.command_create_new)
          .setItems(titles, (dialog, index) -> invoke(actions.get(index)))
          .setNegativeButton(android.R.string.cancel, null)
          .show();
    }
    return true;
  }

  static boolean invoke(Preference preference) {
    if (!preference.isVisible() || !preference.isEnabled()
        || !preference.getExtras().getBoolean(CREATE_ACTION, false)) return false;
    Preference.OnPreferenceClickListener listener = preference.getOnPreferenceClickListener();
    return listener != null && listener.onPreferenceClick(preference);
  }

  private static void collect(PreferenceGroup group, List<Preference> result) {
    if (group == null) return;
    for (int i = 0; i < group.getPreferenceCount(); i++) {
      Preference preference = group.getPreference(i);
      if (!preference.isVisible()) continue;
      if (preference instanceof PreferenceGroup) {
        collect((PreferenceGroup) preference, result);
      } else if (preference.isEnabled() && preference.getExtras().getBoolean(CREATE_ACTION, false)
          && preference.getOnPreferenceClickListener() != null) {
        result.add(preference);
      }
    }
  }
}
