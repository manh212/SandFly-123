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
import android.view.View;
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
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
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
    for (int index = 0; index < groups.size(); index++) {
      Group group = groups.get(index);
      ActionPreference groupPreference = new ActionPreference(context);
      groupPreference.setKey("selector_group_" + group.id);
      groupPreference.setTitle(group.name);
      groupPreference.setSummary(
          getString(
              R.string.selector_group_summary,
              group.items.size(),
              getString(
                  group.enabled
                      ? R.string.selector_group_status_enabled
                      : R.string.selector_group_status_disabled)));
      groupPreference.setOnPreferenceClickListener(
          preference -> {
            editGroup(group.id);
            return true;
          });
      groupPreference.addAccessibilityAction(
          R.id.selector_group_action_add,
          getString(R.string.selector_groups_add),
          () -> {
            addGroup();
            return true;
          });
      groupPreference.addAccessibilityAction(
          R.id.selector_group_action_edit,
          getString(R.string.selector_group_edit, group.name),
          () -> {
            editGroup(group.id);
            return true;
          });
      groupPreference.addAccessibilityAction(
          R.id.selector_group_action_edit_items,
          getString(R.string.selector_group_edit_items),
          () -> {
            editItems(group.id);
            return true;
          });
      groupPreference.addAccessibilityAction(
          R.id.selector_group_action_sort_items,
          getString(R.string.selector_group_sort_items),
          () -> {
            sortItems(group.id);
            return true;
          });
      groupPreference.addAccessibilityAction(
          R.id.selector_group_action_toggle,
          getString(R.string.selector_group_toggle_title, group.name),
          () -> toggleGroup(group.id));
      if (index > 0) {
        groupPreference.addAccessibilityAction(
            R.id.selector_group_action_move_up,
            getString(R.string.selector_group_move_up, group.name),
            () -> {
              if (SelectorGroupManager.moveGroup(context, group.id, -1)) {
                refresh();
              }
              return true;
            });
      }
      if (index < groups.size() - 1) {
        groupPreference.addAccessibilityAction(
            R.id.selector_group_action_move_down,
            getString(R.string.selector_group_move_down, group.name),
            () -> {
              if (SelectorGroupManager.moveGroup(context, group.id, 1)) {
                refresh();
              }
              return true;
            });
      }
      groupPreference.addAccessibilityAction(
          R.id.selector_group_action_delete,
          getString(R.string.selector_group_delete),
          () -> {
            confirmDelete(group.id);
            return true;
          });
      groupsCategory.addPreference(groupPreference);
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

  private boolean toggleGroup(String groupId) {
    Context context = requireContext();
    Group group = findGroup(SelectorGroupManager.getGroups(context), groupId);
    if (group == null) {
      return false;
    }
    if (group.enabled && !SelectorGroupManager.canDisableGroup(context, groupId)) {
      showMessage(R.string.selector_group_minimum_enabled_error);
      return false;
    }
    ArrayList<Group> updated = mutableGroups();
    Group updatedGroup = findGroup(updated, groupId);
    if (updatedGroup == null) {
      return false;
    }
    updatedGroup.enabled = !updatedGroup.enabled;
    if (SelectorGroupManager.saveGroups(context, updated)) {
      refresh();
    }
    return true;
  }

  private interface AccessibilityActionHandler {
    boolean perform();
  }

  private static final class ScreenReaderAction {
    final int actionId;
    final CharSequence label;
    final AccessibilityActionHandler handler;

    ScreenReaderAction(
        int actionId, CharSequence label, AccessibilityActionHandler handler) {
      this.actionId = actionId;
      this.label = label;
      this.handler = handler;
    }
  }

  private static void addAccessibilityAction(
      View view, int actionId, CharSequence label, AccessibilityActionHandler handler) {
    AccessibilityNodeInfoCompat.AccessibilityActionCompat compatAction =
        new AccessibilityNodeInfoCompat.AccessibilityActionCompat(actionId, label);
    ViewCompat.replaceAccessibilityAction(
        view, compatAction, label, (commandView, arguments) -> handler.perform());
  }

  private static void bindScreenReaderActions(View view, List<ScreenReaderAction> actions) {
    int[] actionIds = {
      R.id.selector_group_action_add,
      R.id.selector_group_action_edit,
      R.id.selector_group_action_edit_items,
      R.id.selector_group_action_sort_items,
      R.id.selector_group_action_toggle,
      R.id.selector_group_action_move_up,
      R.id.selector_group_action_move_down,
      R.id.selector_group_action_delete,
      R.id.selector_group_action_move_item_up,
      R.id.selector_group_action_move_item_down,
      R.id.selector_group_action_add_item,
      R.id.selector_group_action_remove_item
    };
    for (int actionId : actionIds) {
      ViewCompat.removeAccessibilityAction(view, actionId);
    }
    for (ScreenReaderAction action : actions) {
      addAccessibilityAction(view, action.actionId, action.label, action.handler);
    }
  }

  private static final class ActionPreference extends Preference {
    private final ArrayList<ScreenReaderAction> actions = new ArrayList<>();

    ActionPreference(Context context) {
      super(context);
    }

    void addAccessibilityAction(
        int actionId, CharSequence label, AccessibilityActionHandler handler) {
      actions.add(new ScreenReaderAction(actionId, label, handler));
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
      super.onBindViewHolder(holder);
      bindScreenReaderActions(holder.itemView, actions);
    }
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
    ListView list = new ListView(context);
    list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    int listHeight = (int) (280 * context.getResources().getDisplayMetrics().density);
    list.setLayoutParams(
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
    ArrayAdapter<String> adapter =
        createItemPickerAdapter(context, items, list, selectedItems, null, selectionSummary);
    list.setAdapter(adapter);
    for (int index = 0; index < items.size(); index++) {
      list.setItemChecked(index, selectedItems.contains(items.get(index).id));
    }
    list.setOnItemClickListener(
        (parent, view, index, id) -> {
          ItemInfo item = items.get(index);
          if (list.isItemChecked(index)) {
            selectedItems.add(item.id);
          } else {
            selectedItems.remove(item.id);
          }
          updateSelectionSummary(selectionSummary, selectedItems.size());
          adapter.notifyDataSetChanged();
        });
    new AlertDialog.Builder(context)
        .setTitle(R.string.selector_group_choose_items)
        .setView(list)
        .setPositiveButton(R.string.selector_group_done, null)
        .show();
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
    LinearLayout layout = verticalLayout(context);
    layout.addView(name, matchWrap());
    layout.addView(enabled, matchWrap());
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(getString(R.string.selector_group_edit, group.name))
            .setView(layout)
            .setPositiveButton(R.string.selector_group_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
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
    ListView list = new ListView(context);
    list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    int listHeight = (int) (280 * context.getResources().getDisplayMetrics().density);
    list.setLayoutParams(
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
    ArrayAdapter<String> adapter =
        createItemPickerAdapter(context, items, list, null, groupId, null);
    list.setAdapter(adapter);
    for (int index = 0; index < items.size(); index++) {
      list.setItemChecked(index, target.items.contains(items.get(index).id));
    }
    list.setOnItemClickListener(
        (parent, view, index, id) ->
            onItemMembershipChanged(
                adapter,
                list,
                groupId,
                items.get(index).id,
                index,
                list.isItemChecked(index)));
    new AlertDialog.Builder(context)
        .setTitle(getString(R.string.selector_group_edit, target.name))
        .setView(list)
        .setPositiveButton(R.string.selector_group_done, null)
        .show();
  }

  private ArrayAdapter<String> createItemPickerAdapter(
      Context context,
      List<ItemInfo> items,
      ListView list,
      Set<String> selectedItems,
      String groupId,
      TextView selectionSummary) {
    ArrayList<String> titles = new ArrayList<>();
    String[] itemLabels =
        itemTitles(items, SelectorGroupManager.getGroups(context), groupId);
    for (String label : itemLabels) {
      titles.add(label);
    }
    return new ArrayAdapter<String>(
        context, android.R.layout.simple_list_item_multiple_choice, titles) {
      @Override
      public View getView(int position, View convertView, ViewGroup parent) {
        View row = super.getView(position, convertView, parent);
        ViewCompat.removeAccessibilityAction(row, R.id.selector_group_action_add_item);
        ViewCompat.removeAccessibilityAction(row, R.id.selector_group_action_remove_item);
        ItemInfo item = items.get(position);
        boolean selected =
            groupId == null
                ? selectedItems.contains(item.id)
                : isItemInGroup(groupId, item.id);
        if (selected) {
          addAccessibilityAction(
              row,
              R.id.selector_group_action_remove_item,
              getString(R.string.selector_group_remove_item_action),
              () -> {
                if (groupId == null) {
                  selectedItems.remove(item.id);
                  list.setItemChecked(position, false);
                  updateSelectionSummary(selectionSummary, selectedItems.size());
                  notifyDataSetChanged();
                } else {
                  onItemMembershipChanged(this, list, groupId, item.id, position, false);
                }
                return true;
              });
        } else {
          addAccessibilityAction(
              row,
              R.id.selector_group_action_add_item,
              getString(R.string.selector_group_add_item_action),
              () -> {
                if (groupId == null) {
                  selectedItems.add(item.id);
                  list.setItemChecked(position, true);
                  updateSelectionSummary(selectionSummary, selectedItems.size());
                  notifyDataSetChanged();
                } else {
                  onItemMembershipChanged(this, list, groupId, item.id, position, true);
                }
                return true;
              });
        }
        return row;
      }
    };
  }

  private boolean isItemInGroup(String groupId, String itemId) {
    Group group = findGroup(SelectorGroupManager.getGroups(requireContext()), groupId);
    return group != null && group.items.contains(itemId);
  }

  private void onItemMembershipChanged(
      ArrayAdapter<String> adapter,
      ListView list,
      String groupId,
      String itemId,
      int index,
      boolean isChecked) {
    Context context = requireContext();
    Group target = findGroup(SelectorGroupManager.getGroups(context), groupId);
    if (target == null) {
      list.setItemChecked(index, !isChecked);
      adapter.notifyDataSetChanged();
      return;
    }
    List<Group> groups = SelectorGroupManager.getGroups(context);
    if (isChecked) {
      Group source = null;
      for (Group group : groups) {
        if (!group.id.equals(groupId) && group.items.contains(itemId)) {
          source = group;
          break;
        }
      }
      if (source == null) {
        if (!SelectorGroupManager.addItem(context, groupId, itemId)) {
          list.setItemChecked(index, false);
        }
        adapter.notifyDataSetChanged();
        return;
      }
      new AlertDialog.Builder(context)
          .setTitle(R.string.selector_group_edit_items)
          .setMessage(
              getString(R.string.selector_group_item_move_confirm, source.name, target.name))
          .setPositiveButton(
              R.string.selector_group_move_confirm,
              (confirmDialog, which) -> {
                if (!SelectorGroupManager.addItem(context, groupId, itemId)) {
                  list.setItemChecked(index, false);
                  showMessage(R.string.selector_group_move_last_item_error);
                } else {
                  list.setItemChecked(index, true);
                }
                adapter.notifyDataSetChanged();
              })
          .setNegativeButton(
              android.R.string.cancel,
              (confirmDialog, which) -> {
                list.setItemChecked(index, false);
                adapter.notifyDataSetChanged();
              })
          .show();
      return;
    }
    if (!SelectorGroupManager.removeItem(context, groupId, itemId)) {
      list.setItemChecked(index, true);
      showMessage(R.string.selector_group_minimum_item_error);
    }
    adapter.notifyDataSetChanged();
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
    final int[] selectedIndex = {itemIds.isEmpty() ? -1 : 0};
    ArrayAdapter<String> adapter =
        new ArrayAdapter<String>(
            context, android.R.layout.simple_list_item_single_choice, labels) {
          @Override
          public View getView(int position, View convertView, ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            ViewCompat.removeAccessibilityAction(row, R.id.selector_group_action_move_item_up);
            ViewCompat.removeAccessibilityAction(row, R.id.selector_group_action_move_item_down);
            if (position > 0) {
              addAccessibilityAction(
                  row,
                  R.id.selector_group_action_move_item_up,
                  getString(R.string.selector_group_item_move_up),
                  () -> {
                    selectedIndex[0] = position;
                    list.setItemChecked(position, true);
                    moveSelectedItem(
                        groupId, itemIds, labels, this, list, selectedIndex, -1);
                    return true;
                  });
            }
            if (position < itemIds.size() - 1) {
              addAccessibilityAction(
                  row,
                  R.id.selector_group_action_move_item_down,
                  getString(R.string.selector_group_item_move_down),
                  () -> {
                    selectedIndex[0] = position;
                    list.setItemChecked(position, true);
                    moveSelectedItem(
                        groupId, itemIds, labels, this, list, selectedIndex, 1);
                    return true;
                  });
            }
            return row;
          }
        };
    list.setAdapter(adapter);
    if (!itemIds.isEmpty()) {
      list.setItemChecked(0, true);
    }
    int listHeight = (int) (280 * context.getResources().getDisplayMetrics().density);
    layout.addView(
        list,
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
    list.setOnItemClickListener(
        (parent, view, position, id) -> selectedIndex[0] = position);
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(R.string.selector_group_sort_items)
            .setView(layout)
            .setPositiveButton(R.string.selector_group_done, null)
            .create();
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
    list.announceForAccessibility(labels.get(to));
  }

  private void confirmDelete(String groupId) {
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
      if (groups != null) {
        for (Group group : groups) {
          if (!group.id.equals(targetGroupId) && group.items.contains(item.id)) {
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
