package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Bundle;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.Group;

public final class SelectorGroupsFragment extends TalkbackBaseFragment {
  @Override
  public CharSequence getTitle() {
    return getText(R.string.selector_groups_title);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    SelectorGroupManager.ensureInitialized(requireContext());
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    getParentFragmentManager()
        .setFragmentResultListener(
            SelectorGroupScreenUi.RESULT,
            this,
            (requestKey, result) -> {
              refresh();
              String groupId = result.getString(SelectorGroupScreenUi.FOCUS_ID, "");
              String target = groupId.isEmpty() ? "selector_group_create" : "selector_group_" + groupId;
              SelectorGroupScreenUi.focus(
                  this, target, result.getString(SelectorGroupScreenUi.MESSAGE));
            });
    refresh();
  }

  @Override
  public void onResume() {
    super.onResume();
    SelectorGroupManager.ensureInitialized(requireContext());
    refresh();
  }

  private void refresh() {
    Context context = requireContext();
    PreferenceScreen screen = getPreferenceScreen();
    screen.removeAll();

    Preference add = new Preference(context);
    add.setKey("selector_group_create");
    add.setTitle(R.string.sgm_add_group);
    add.setIconSpaceReserved(false);
    add.setOnPreferenceClickListener(preference -> {
      SelectorGroupScreenUi.open(this, new SelectorGroupCreateFragment(), new Bundle());
      return true;
    });
    screen.addPreference(CreateNewShortcut.mark(add));

    PreferenceCategory groupsCategory = new PreferenceCategory(context);
    groupsCategory.setKey("selector_navigation_groups");
    groupsCategory.setTitle(R.string.selector_groups_title);
    screen.addPreference(groupsCategory);

    for (Group group : SelectorGroupManager.getGroups(context)) {
      Preference row = new Preference(context);
      row.setKey("selector_group_" + group.id);
      row.setTitle(group.name);
      row.setSummary(
          getString(
              R.string.sgm_group_summary,
              group.items.size(),
              getString(
                  group.enabled
                      ? R.string.sgm_on
                      : R.string.sgm_off)));
      row.setIconSpaceReserved(false);
      row.setOnPreferenceClickListener(preference -> {
        Bundle args = new Bundle();
        args.putString(SelectorGroupScreenUi.GROUP_ID, group.id);
        SelectorGroupScreenUi.open(this, new SelectorGroupEditorFragment(), args);
        return true;
      });
      groupsCategory.addPreference(row);
    }

    PreferenceCategory options = new PreferenceCategory(context);
    options.setKey("selector_group_options");
    options.setTitle(R.string.selector_group_options);
    screen.addPreference(options);
    addExistingSwitch(
        options,
        R.string.pref_selector_auto_switch_key,
        R.bool.pref_selector_auto_switch_default,
        R.string.title_pref_selector_auto_switch,
        0);
    addExistingSwitch(
        options,
        R.string.pref_selection_mode_reading_control_key,
        R.bool.pref_selection_mode_reading_control_default,
        R.string.title_pref_selection_mode_reading_control,
        R.string.summary_pref_selection_mode_reading_control);
    addExistingSwitch(
        options,
        R.string.pref_selector_links_controls_web_only_key,
        R.bool.pref_selector_links_controls_web_only_default,
        R.string.title_pref_selector_links_controls_web_only,
        0);
  }

  private void addExistingSwitch(
      PreferenceCategory category,
      int keyResId,
      int defaultResId,
      int titleResId,
      int summaryResId) {
    Context context = requireContext();
    SwitchPreferenceCompat preference = new SwitchPreferenceCompat(context);
    preference.setKey(getString(keyResId));
    preference.setDefaultValue(context.getResources().getBoolean(defaultResId));
    preference.setTitle(titleResId);
    if (summaryResId != 0) {
      preference.setSummary(summaryResId);
    }
    category.addPreference(preference);
  }
}
