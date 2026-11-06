package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.Group;
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText;
import java.util.ArrayList;

public final class SelectorGroupEditorFragment extends TalkbackBaseFragment {
  private String groupId;

  @Override
  public CharSequence getTitle() {
    Group group = currentGroup();
    return group == null ? getText(R.string.selector_groups_title) : group.name;
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    groupId = getArguments() == null ? "" : getArguments().getString(SelectorGroupScreenUi.GROUP_ID, "");
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    refresh();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (currentGroup() == null) {
      requireView().post(() -> {
        if (isAdded()) {
          getParentFragmentManager().popBackStack();
        }
      });
    } else {
      refresh();
    }
  }

  private Group currentGroup() {
    if (groupId == null) {
      return null;
    }
    for (Group group : SelectorGroupManager.getGroups(requireContext())) {
      if (group.id.equals(groupId)) {
        return group;
      }
    }
    return null;
  }

  private void refresh() {
    Context context = requireContext();
    PreferenceScreen screen = getPreferenceScreen();
    screen.removeAll();
    Group group = currentGroup();
    if (group == null) {
      return;
    }

    Preference rename = new Preference(context);
    rename.setKey("sgm_rename");
    rename.setTitle(R.string.sgm_rename);
    rename.setOnPreferenceClickListener(preference -> {
      showRenameDialog();
      return true;
    });
    screen.addPreference(rename);

    Preference items = new Preference(context);
    items.setKey("sgm_items");
    items.setTitle(R.string.sgm_edit_items);
    items.setSummary(getString(R.string.sgm_item_count, group.items.size()));
    items.setOnPreferenceClickListener(preference -> {
      Bundle args = new Bundle();
      args.putString(SelectorGroupScreenUi.GROUP_ID, groupId);
      SelectorGroupScreenUi.open(this, new SelectorGroupItemsFragment(), args);
      return true;
    });
    screen.addPreference(items);

    SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(context);
    enabled.setKey("sgm_enabled");
    enabled.setPersistent(false);
    enabled.setTitle(R.string.sgm_enabled);
    enabled.setChecked(group.enabled);
    enabled.setOnPreferenceChangeListener((preference, value) -> {
      if (!(value instanceof Boolean)) {
        return false;
      }
      boolean newValue = (Boolean) value;
      if (!newValue && !SelectorGroupManager.canDisableGroup(context, groupId)) {
        showMessage(R.string.sgm_minimum_enabled);
        return false;
      }
      ArrayList<Group> updated = copyGroups();
      for (Group target : updated) {
        if (target.id.equals(groupId)) {
          target.enabled = newValue;
        }
      }
      if (!SelectorGroupManager.saveGroups(context, updated)) {
        showMessage(R.string.sgm_save_failed);
        return false;
      }
      return true;
    });
    screen.addPreference(enabled);

    Preference delete = new Preference(context);
    delete.setKey("sgm_delete");
    delete.setTitle(R.string.sgm_delete);
    delete.setOnPreferenceClickListener(preference -> {
      showDeleteDialog();
      return true;
    });
    screen.addPreference(delete);
  }

  private void showRenameDialog() {
    Group group = currentGroup();
    if (group == null) {
      return;
    }
    KeyboardSafeEditText field = new KeyboardSafeEditText(requireContext());
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setSingleLine(true);
    field.setHint(R.string.sgm_group_name);
    field.setText(group.name);
    field.setSelection(field.length());
    AlertDialog dialog =
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.sgm_rename)
            .setView(field)
            .setPositiveButton(R.string.sgm_save, null)
            .setNegativeButton(R.string.sgm_cancel, null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      String name = field.getText().toString().trim();
                      if (name.isEmpty()) {
                        field.setError(getString(R.string.sgm_name_required));
                        return;
                      }
                      if (SelectorGroupManager.isNameTaken(requireContext(), name, groupId)) {
                        field.setError(getString(R.string.sgm_name_exists));
                        return;
                      }
                      ArrayList<Group> updated = copyGroups();
                      for (Group target : updated) {
                        if (target.id.equals(groupId)) {
                          target.name = name;
                        }
                      }
                      if (!SelectorGroupManager.saveGroups(requireContext(), updated)) {
                        showMessage(R.string.sgm_save_failed);
                        return;
                      }
                      dialog.dismiss();
                      refresh();
                      requireActivity().setTitle(name);
                      SelectorGroupScreenUi.focus(this, "sgm_rename", getString(R.string.sgm_renamed));
                    }));
    dialog.show();
  }

  private void showDeleteDialog() {
    Group group = currentGroup();
    if (group == null) {
      return;
    }
    ArrayList<Group> before = copyGroups();
    int position = 0;
    for (int index = 0; index < before.size(); index++) {
      if (before.get(index).id.equals(groupId)) {
        position = index;
        break;
      }
    }
    final int removedPosition = position;
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.sgm_delete)
        .setMessage(getString(R.string.sgm_delete_confirm, group.name))
        .setPositiveButton(
            R.string.sgm_delete,
            (dialog, which) -> {
              if (!SelectorGroupManager.removeGroup(requireContext(), groupId)) {
                showMessage(
                    SelectorGroupManager.getGroups(requireContext()).size() == 1
                        ? R.string.sgm_minimum_group
                        : R.string.sgm_minimum_enabled);
                return;
              }
              Bundle result = new Bundle();
              ArrayList<Group> remaining = copyGroups();
              if (!remaining.isEmpty()) {
                result.putString(
                    SelectorGroupScreenUi.FOCUS_ID,
                    remaining.get(Math.min(removedPosition, remaining.size() - 1)).id);
              }
              result.putString(SelectorGroupScreenUi.MESSAGE, getString(R.string.sgm_deleted));
              getParentFragmentManager().setFragmentResult(SelectorGroupScreenUi.RESULT, result);
              getParentFragmentManager().popBackStack();
            })
        .setNegativeButton(R.string.sgm_cancel, null)
        .show();
  }

  private ArrayList<Group> copyGroups() {
    ArrayList<Group> groups = new ArrayList<>();
    for (Group group : SelectorGroupManager.getGroups(requireContext())) {
      groups.add(group.copy());
    }
    return groups;
  }

  private void showMessage(int stringId) {
    Toast.makeText(requireContext(), stringId, Toast.LENGTH_SHORT).show();
  }
}
