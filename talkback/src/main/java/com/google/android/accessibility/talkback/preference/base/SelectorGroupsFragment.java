/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.Group;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.ItemInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    PreferenceCategory groupsCategory = new PreferenceCategory(context);
    groupsCategory.setTitle(R.string.selector_groups_title);
    groupsCategory.setKey("selector_navigation_groups");
    screen.addPreference(groupsCategory);
    List<Group> groups = SelectorGroupManager.getGroups(context);
    for (Group group : groups) {
      SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(context);
      enabled.setKey("selector_group_enabled_" + group.id);
      enabled.setPersistent(false);
      enabled.setTitle(getString(R.string.selector_group_toggle_title, group.name));
      enabled.setSummary(getString(R.string.selector_group_item_count, group.items.size()));
      enabled.setChecked(group.enabled);
      enabled.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            boolean isEnabled = (Boolean) newValue;
            if (!isEnabled && !SelectorGroupManager.canDisableGroup(context, group.id)) {
              showMessage(R.string.selector_group_minimum_enabled_error);
              return false;
            }
            ArrayList<Group> updated = mutableGroups();
            Group updatedGroup = findGroup(updated, group.id);
            if (updatedGroup == null) {
              return false;
            }
            updatedGroup.enabled = isEnabled;
            if (SelectorGroupManager.saveGroups(context, updated)) {
              refresh();
            }
            return false;
          });
      groupsCategory.addPreference(enabled);

      Preference edit = new Preference(context);
      edit.setKey("selector_group_edit_" + group.id);
      edit.setTitle(getString(R.string.selector_group_edit, group.name));
      edit.setSummary(getString(R.string.selector_group_item_count, group.items.size()));
      edit.setOnPreferenceClickListener(
          preference -> {
            editGroup(group.id);
            return true;
          });
      groupsCategory.addPreference(edit);

      Preference moveUp = new Preference(context);
      moveUp.setKey("selector_group_move_up_" + group.id);
      moveUp.setTitle(getString(R.string.selector_group_move_up, group.name));
      moveUp.setEnabled(groups.indexOf(group) > 0);
      moveUp.setOnPreferenceClickListener(
          preference -> {
            SelectorGroupManager.moveGroup(context, group.id, -1);
            refresh();
            return true;
          });
      groupsCategory.addPreference(moveUp);

      Preference moveDown = new Preference(context);
      moveDown.setKey("selector_group_move_down_" + group.id);
      moveDown.setTitle(getString(R.string.selector_group_move_down, group.name));
      moveDown.setEnabled(groups.indexOf(group) < groups.size() - 1);
      moveDown.setOnPreferenceClickListener(
          preference -> {
            SelectorGroupManager.moveGroup(context, group.id, 1);
            refresh();
            return true;
          });
      groupsCategory.addPreference(moveDown);
    }

    Preference add = new Preference(context);
    add.setKey("selector_group_add");
    add.setTitle(R.string.selector_groups_add);
    add.setOnPreferenceClickListener(
        preference -> {
          addGroup();
          return true;
        });
    screen.addPreference(add);

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

  private void addGroup() {
    Context context = requireContext();
    EditText name = createNameField(context, "");
    Set<String> selectedItems = new LinkedHashSet<>();
    Button chooseItems = createButton(context, R.string.selector_group_choose_items);
    TextView selectionSummary = new TextView(context);
    selectionSummary.setText(R.string.selector_group_no_items_selected);
    chooseItems.setOnClickListener(
        view -> showNewGroupItemPicker(selectedItems, selectionSummary));
    LinearLayout layout = verticalLayout(context);
    layout.addView(name, matchWrap());
    layout.addView(chooseItems, matchWrap());
    layout.addView(selectionSummary, matchWrap());
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(R.string.selector_groups_add_title)
            .setView(layout)
            .setPositiveButton(R.string.selector_group_create_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    dialog.setOnShowListener(
        ignored ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      String groupName = name.getText().toString().trim();
                      if (groupName.isEmpty() || selectedItems.isEmpty()) {
                        showMessage(R.string.selector_group_empty_error);
                        return;
                      }
                      if (!SelectorGroupManager.addGroup(
                          context, groupName, new ArrayList<>(selectedItems))) {
                        showMessage(R.string.selector_group_move_last_item_error);
                        return;
                      }
                      dialog.dismiss();
                      refresh();
                    }));
    dialog.show();
  }

  private void showNewGroupItemPicker(Set<String> selectedItems, TextView selectionSummary) {
    Context context = requireContext();
    List<ItemInfo> items = SelectorGroupManager.availableItems(context);
    String[] titles = itemTitles(items, SelectorGroupManager.getGroups(context), null);
    boolean[] checked = new boolean[items.size()];
    for (int index = 0; index < items.size(); index++) {
      checked[index] = selectedItems.contains(items.get(index).id);
    }
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.selector_group_choose_items)
        .setMultiChoiceItems(
            titles,
            checked,
            (dialog, index, isChecked) -> {
              if (isChecked) {
                selectedItems.add(items.get(index).id);
              } else {
                selectedItems.remove(items.get(index).id);
              }
              updateSelectionSummary(selectionSummary, selectedItems.size());
            })
        .setPositiveButton(R.string.selector_group_done, null)
        .show();
    updateSelectionSummary(selectionSummary, selectedItems.size());
  }

  private void editGroup(String groupId) {
    Context context = requireContext();
    Group group = findGroup(SelectorGroupManager.getGroups(context), groupId);
    if (group == null) {
      return;
    }
    EditText name = createNameField(context, group.name);
    SwitchCompat enabled = new SwitchCompat(context);
    enabled.setText(R.string.selector_group_enabled);
    enabled.setChecked(group.enabled);
    Button editItems = createButton(context, R.string.selector_group_edit_items);
    Button sortItems = createButton(context, R.string.selector_group_sort_items);
    Button delete = createButton(context, R.string.selector_group_delete);
    LinearLayout layout = verticalLayout(context);
    layout.addView(name, matchWrap());
    layout.addView(enabled, matchWrap());
    layout.addView(editItems, matchWrap());
    layout.addView(sortItems, matchWrap());
    layout.addView(delete, matchWrap());
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(getString(R.string.selector_group_edit, group.name))
            .setView(layout)
            .setPositiveButton(R.string.selector_group_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    editItems.setOnClickListener(view -> editItems(groupId));
    sortItems.setOnClickListener(view -> sortItems(groupId));
    delete.setOnClickListener(view -> confirmDelete(groupId, dialog));
    dialog.setOnShowListener(
        ignored ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      String groupName = name.getText().toString().trim();
                      if (groupName.isEmpty()) {
                        showMessage(R.string.selector_group_empty_error);
                        return;
                      }
                      if (!enabled.isChecked()
                          && !SelectorGroupManager.canDisableGroup(context, groupId)) {
                        showMessage(R.string.selector_group_minimum_enabled_error);
                        return;
                      }
                      ArrayList<Group> updated = mutableGroups();
                      Group edited = findGroup(updated, groupId);
                      if (edited == null) {
                        return;
                      }
                      edited.name = groupName;
                      edited.enabled = enabled.isChecked();
                      if (!SelectorGroupManager.saveGroups(context, updated)) {
                        showMessage(R.string.selector_group_empty_error);
                        return;
                      }
                      dialog.dismiss();
                      refresh();
                    }));
    dialog.show();
  }

  private void editItems(String groupId) {
    Context context = requireContext();
    Group target = findGroup(SelectorGroupManager.getGroups(context), groupId);
    if (target == null) {
      return;
    }
    List<ItemInfo> items = SelectorGroupManager.availableItems(context);
    String[] titles = itemTitles(items, SelectorGroupManager.getGroups(context), groupId);
    boolean[] checked = new boolean[items.size()];
    for (int index = 0; index < items.size(); index++) {
      checked[index] = target.items.contains(items.get(index).id);
    }
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(getString(R.string.selector_group_edit, target.name))
            .setMultiChoiceItems(
                titles,
                checked,
                (multiChoiceDialog, index, isChecked) ->
                    onItemMembershipChanged(
                        (AlertDialog) multiChoiceDialog,
                        groupId,
                        items.get(index).id,
                        index,
                        isChecked))
            .setPositiveButton(R.string.selector_group_done, null)
            .create();
    dialog.show();
  }

  private void onItemMembershipChanged(
      AlertDialog dialog, String groupId, String itemId, int index, boolean isChecked) {
    Context context = requireContext();
    Group target = findGroup(SelectorGroupManager.getGroups(context), groupId);
    if (target == null) {
      return;
    }
    List<Group> groups = SelectorGroupManager.getGroups(context);
    if (isChecked) {
      Group source = null;
      if (!SelectorGroupManager.USER_GROUP_ID.equals(groupId)) {
        for (Group group : groups) {
          if (!SelectorGroupManager.USER_GROUP_ID.equals(group.id)
              && !group.id.equals(groupId)
              && group.items.contains(itemId)) {
            source = group;
            break;
          }
        }
      }
      if (source != null && source.items.size() <= 1) {
        dialog.getListView().setItemChecked(index, false);
        showMessage(R.string.selector_group_move_last_item_error);
        return;
      }
      if (source == null) {
        if (!SelectorGroupManager.addItem(context, groupId, itemId)) {
          dialog.getListView().setItemChecked(index, false);
        }
        return;
      }
      new AlertDialog.Builder(context)
          .setTitle(R.string.selector_group_edit_items)
          .setMessage(getString(R.string.selector_group_item_move_confirm, source.name, target.name))
          .setPositiveButton(
              R.string.selector_group_move_confirm,
              (confirmDialog, which) -> {
                if (!SelectorGroupManager.addItem(context, groupId, itemId)) {
                  dialog.getListView().setItemChecked(index, false);
                  showMessage(R.string.selector_group_move_last_item_error);
                }
              })
          .setNegativeButton(
              android.R.string.cancel,
              (confirmDialog, which) -> dialog.getListView().setItemChecked(index, false))
          .show();
      return;
    }
    if (!SelectorGroupManager.removeItem(context, groupId, itemId)) {
      dialog.getListView().setItemChecked(index, true);
      showMessage(R.string.selector_group_minimum_item_error);
    }
  }

  private void sortItems(String groupId) {
    Context context = requireContext();
    Group group = findGroup(SelectorGroupManager.getGroups(context), groupId);
    if (group == null) {
      return;
    }
    LinearLayout layout = verticalLayout(context);
    ListView list = new ListView(context);
    list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
    ArrayList<String> itemIds = new ArrayList<>(group.items);
    ArrayList<String> labels = itemTitlesForIds(context, itemIds);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(context, android.R.layout.simple_list_item_single_choice, labels);
    list.setAdapter(adapter);
    if (!itemIds.isEmpty()) {
      list.setItemChecked(0, true);
    }
    int listHeight = (int) (280 * context.getResources().getDisplayMetrics().density);
    layout.addView(
        list,
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
    LinearLayout actions = new LinearLayout(context);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setGravity(Gravity.CENTER);
    Button up = createButton(context, R.string.selector_group_item_move_up);
    Button down = createButton(context, R.string.selector_group_item_move_down);
    actions.addView(up, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    actions.addView(down, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    layout.addView(actions, matchWrap());
    final int[] selectedIndex = {itemIds.isEmpty() ? -1 : 0};
    list.setOnItemClickListener(
        (parent, view, position, id) -> selectedIndex[0] = position);
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(R.string.selector_group_sort_items)
            .setView(layout)
            .setPositiveButton(R.string.selector_group_done, null)
            .create();
    up.setOnClickListener(view -> moveSelectedItem(groupId, itemIds, labels, adapter, list, selectedIndex, -1));
    down.setOnClickListener(view -> moveSelectedItem(groupId, itemIds, labels, adapter, list, selectedIndex, 1));
    dialog.show();
  }

  private void moveSelectedItem(
      String groupId,
      ArrayList<String> itemIds,
      ArrayList<String> labels,
      ArrayAdapter<String> adapter,
      ListView list,
      int[] selectedIndex,
      int offset) {
    int from = selectedIndex[0];
    int to = from + offset;
    if (from < 0 || to < 0 || to >= itemIds.size()) {
      return;
    }
    if (!SelectorGroupManager.moveItem(requireContext(), groupId, itemIds.get(from), offset)) {
      return;
    }
    java.util.Collections.swap(itemIds, from, to);
    java.util.Collections.swap(labels, from, to);
    selectedIndex[0] = to;
    adapter.notifyDataSetChanged();
    list.setItemChecked(to, true);
  }

  private void confirmDelete(String groupId, AlertDialog editorDialog) {
    Context context = requireContext();
    Group group = findGroup(SelectorGroupManager.getGroups(context), groupId);
    if (group == null) {
      return;
    }
    new AlertDialog.Builder(context)
        .setTitle(R.string.selector_group_delete)
        .setMessage(getString(R.string.selector_group_delete_confirm, group.name))
        .setPositiveButton(
            R.string.selector_group_delete,
            (dialog, which) -> {
              if (!SelectorGroupManager.removeGroup(context, groupId)) {
                showMessage(R.string.selector_group_minimum_group_error);
                return;
              }
              editorDialog.dismiss();
              refresh();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private String[] itemTitles(List<ItemInfo> items, List<Group> groups, String targetGroupId) {
    String[] titles = new String[items.size()];
    for (int index = 0; index < items.size(); index++) {
      ItemInfo item = items.get(index);
      Group source = null;
      if (groups != null && !SelectorGroupManager.USER_GROUP_ID.equals(targetGroupId)) {
        for (Group group : groups) {
          if (!SelectorGroupManager.USER_GROUP_ID.equals(group.id)
              && !group.id.equals(targetGroupId)
              && group.items.contains(item.id)) {
            source = group;
            break;
          }
        }
      }
      titles[index] = source == null ? item.title.toString() : item.title + " — " + source.name;
    }
    return titles;
  }

  private ArrayList<String> itemTitlesForIds(Context context, List<String> itemIds) {
    ArrayList<String> titles = new ArrayList<>();
    List<ItemInfo> available = SelectorGroupManager.availableItems(context);
    for (String itemId : itemIds) {
      String title = itemId;
      for (ItemInfo item : available) {
        if (item.id.equals(itemId)) {
          title = item.title.toString();
          break;
        }
      }
      titles.add(title);
    }
    return titles;
  }

  private void updateSelectionSummary(TextView summary, int count) {
    summary.setText(
        count == 0
            ? getString(R.string.selector_group_no_items_selected)
            : getString(R.string.selector_group_items_selected, count));
  }

  private EditText createNameField(Context context, String value) {
    EditText name = new EditText(context);
    name.setSingleLine(true);
    name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    name.setHint(R.string.selector_group_name);
    name.setContentDescription(getString(R.string.selector_group_name));
    name.setText(value);
    return name;
  }

  private Button createButton(Context context, int titleResId) {
    Button button = new Button(context);
    button.setText(titleResId);
    return button;
  }

  private LinearLayout verticalLayout(Context context) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    int inset = (int) (24 * context.getResources().getDisplayMetrics().density);
    layout.setPadding(inset, inset / 2, inset, inset / 2);
    return layout;
  }

  private LinearLayout.LayoutParams matchWrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private void showMessage(int messageResId) {
    Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show();
  }

  private ArrayList<Group> mutableGroups() {
    ArrayList<Group> groups = new ArrayList<>();
    for (Group group : SelectorGroupManager.getGroups(requireContext())) {
      groups.add(group.copy());
    }
    return groups;
  }

  private Group findGroup(List<Group> groups, String groupId) {
    for (Group group : groups) {
      if (group.id.equals(groupId)) {
        return group;
      }
    }
    return null;
  }
}
