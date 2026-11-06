package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.Group;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.ItemInfo;
import java.util.ArrayList;
import java.util.List;

public final class SelectorGroupItemsFragment extends TalkbackBaseFragment {
  private String groupId;

  @Override
  public CharSequence getTitle() {
    return getText(R.string.sgm_edit_items);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    groupId =
        getArguments() == null ? "" : getArguments().getString(SelectorGroupScreenUi.GROUP_ID, "");
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    refresh();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (currentGroup() == null) {
      requireView().post(
          () -> {
            if (isAdded()) {
              getParentFragmentManager().popBackStack();
            }
          });
    } else {
      refresh();
    }
  }

  private Group currentGroup() {
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
    PreferenceCategory present = new PreferenceCategory(context);
    present.setKey("sgm_present");
    present.setTitle(R.string.sgm_present);
    screen.addPreference(present);

    List<ItemInfo> available = SelectorGroupManager.availableItems(context);
    for (int index = 0; index < group.items.size(); index++) {
      String itemId = group.items.get(index);
      String title = titleFor(available, itemId);
      Preference row = new Preference(context);
      row.setKey("sgm_current_" + itemId);
      row.setTitle(title);
      row.setIconSpaceReserved(false);
      row.setOnPreferenceClickListener(
          preference -> {
            showItemActions(itemId);
            return true;
          });
      present.addPreference(row);
    }

    PreferenceCategory notIncluded = new PreferenceCategory(context);
    notIncluded.setKey("sgm_available");
    notIncluded.setTitle(R.string.sgm_available);
    screen.addPreference(notIncluded);
    int count = 0;
    for (ItemInfo item : available) {
      if (group.items.contains(item.id)) {
        continue;
      }
      count++;
      Preference row = new Preference(context);
      row.setKey("sgm_available_" + item.id);
      row.setTitle(item.title);
      row.setIconSpaceReserved(false);
      row.setOnPreferenceClickListener(
          preference -> {
            if (SelectorGroupManager.addItem(requireContext(), groupId, item.id)) {
              refresh();
              SelectorGroupScreenUi.focus(
                  this, "sgm_current_" + item.id, getString(R.string.sgm_added, item.title));
            } else {
              showMessage(R.string.sgm_save_failed);
            }
            return true;
          });
      notIncluded.addPreference(row);
    }
    if (count == 0) {
      Preference empty = new Preference(context);
      empty.setTitle(R.string.sgm_no_available);
      empty.setSelectable(false);
      notIncluded.addPreference(empty);
    }
  }

  private void showItemActions(String itemId) {
    Group group = currentGroup();
    if (group == null) {
      return;
    }
    List<ItemInfo> items = SelectorGroupManager.availableItems(requireContext());
    int index = group.items.indexOf(itemId);
    if (index < 0) {
      return;
    }
    String title = titleFor(items, itemId);
    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Runnable> actions = new ArrayList<>();
    if (index > 0) {
      labels.add(getString(R.string.sgm_move_up));
      actions.add(() -> moveItem(itemId, -1, title));
    }
    if (index < group.items.size() - 1) {
      labels.add(getString(R.string.sgm_move_down));
      actions.add(() -> moveItem(itemId, 1, title));
    }
    labels.add(getString(R.string.sgm_remove_item));
    actions.add(() -> removeItem(itemId, title, index));

    new AlertDialog.Builder(requireContext())
        .setTitle(title)
        .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
        .setNegativeButton(R.string.sgm_cancel, null)
        .show();
  }

  private void moveItem(String itemId, int direction, String title) {
    if (!SelectorGroupManager.moveItem(requireContext(), groupId, itemId, direction)) {
      showMessage(R.string.sgm_save_failed);
      return;
    }
    Group group = currentGroup();
    if (group == null) {
      return;
    }
    int position = group.items.indexOf(itemId);
    refresh();
    SelectorGroupScreenUi.focus(
        this,
        "sgm_current_" + itemId,
        getString(R.string.sgm_moved, title, position + 1, group.items.size()));
  }

  private void removeItem(String itemId, String title, int oldPosition) {
    Group before = currentGroup();
    if (before == null) {
      return;
    }
    List<Group> groupsBefore = SelectorGroupManager.getGroups(requireContext());
    int groupPosition = 0;
    for (int index = 0; index < groupsBefore.size(); index++) {
      if (groupsBefore.get(index).id.equals(groupId)) {
        groupPosition = index;
        break;
      }
    }
    if (!SelectorGroupManager.removeItem(requireContext(), groupId, itemId)) {
      showMessage(
          SelectorGroupManager.getGroups(requireContext()).size() == 1
              ? R.string.sgm_minimum_group
              : R.string.sgm_minimum_enabled);
      return;
    }
    Group after = currentGroup();
    if (after == null) {
      Bundle result = new Bundle();
      List<Group> remaining = SelectorGroupManager.getGroups(requireContext());
      if (!remaining.isEmpty()) {
        result.putString(
            SelectorGroupScreenUi.FOCUS_ID,
            remaining.get(Math.min(groupPosition, remaining.size() - 1)).id);
      }
      result.putString(SelectorGroupScreenUi.MESSAGE, getString(R.string.sgm_removed, title));
      getParentFragmentManager().setFragmentResult(SelectorGroupScreenUi.RESULT, result);
      getParentFragmentManager().popBackStack();
      return;
    }
    String nextItem = after.items.get(Math.min(oldPosition, after.items.size() - 1));
    refresh();
    SelectorGroupScreenUi.focus(
        this, "sgm_current_" + nextItem, getString(R.string.sgm_removed, title));
  }

  private String titleFor(List<ItemInfo> items, String itemId) {
    for (ItemInfo item : items) {
      if (item.id.equals(itemId)) {
        return item.title.toString();
      }
    }
    return itemId;
  }

  private void showMessage(int resourceId) {
    Toast.makeText(requireContext(), resourceId, Toast.LENGTH_SHORT).show();
  }
}
