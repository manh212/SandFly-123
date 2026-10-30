package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.CustomMenus;
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText;
import java.util.ArrayList;
import java.util.List;

public final class CustomMenuEditorFragment extends TalkbackBaseFragment {
  public static final String ARG_MENU_ID = "menu_id";
  private String menuId;

  @Override
  public CharSequence getTitle() {
    CustomMenus.Menu menu = CustomMenus.find(requireContext(), menuId);
    return menu == null ? getText(R.string.custom_menus_title) : menu.name;
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    getPreferenceManager().setStorageDeviceProtected();
    menuId = getArguments() == null ? "" : getArguments().getString(ARG_MENU_ID, "");
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    refresh();
  }

  private void refresh() {
    Context context = requireContext();
    CustomMenus.Menu menu = CustomMenus.find(context, menuId);
    PreferenceScreen screen = getPreferenceScreen();
    screen.removeAll();
    if (menu == null) return;
    for (int i = 0; i < menu.items.size(); i++) {
      CustomMenus.Item item = menu.items.get(i);
      int index = i;
      Preference row = new Preference(context);
      row.setTitle(item.label);
      row.setKey("custom_menu_item_" + i);
      row.setOnPreferenceClickListener(
          preference -> {
            itemOptions(index);
            return true;
          });
      screen.addPreference(row);
    }
    Preference add = new Preference(context);
    add.setTitle(R.string.custom_menu_add_action);
    add.setOnPreferenceClickListener(preference -> {
      chooseAction();
      return true;
    });
    screen.addPreference(add);

    Preference rename = new Preference(context);
    rename.setTitle(R.string.custom_menu_rename);
    rename.setOnPreferenceClickListener(preference -> {
      rename();
      return true;
    });
    screen.addPreference(rename);

    Preference remove = new Preference(context);
    remove.setTitle(R.string.custom_menu_delete);
    remove.setOnPreferenceClickListener(preference -> {
      confirmDelete();
      return true;
    });
    screen.addPreference(remove);
  }

  private void chooseAction() {
    Context context = requireContext();
    GestureListPreference picker = new GestureListPreference(context, null);
    List<GestureListPreference.ActionItem> actions = new ArrayList<>();
    for (GestureListPreference.ActionItem item : picker.getActionItems()) {
      if (item.viewType == GestureListPreference.TYPE_ACTION_ITEM
          && !item.value.isEmpty()
          && !item.value.equals(getString(R.string.shortcut_value_unassigned))
          && !CustomMenus.isMenuAction(item.value)) {
        actions.add(item);
      }
    }
    CharSequence[] names = new CharSequence[actions.size()];
    for (int i = 0; i < actions.size(); i++) names[i] = actions.get(i).text;
    new AlertDialog.Builder(context)
        .setTitle(R.string.custom_menu_add_action)
        .setItems(names, (dialog, selected) -> {
          GestureListPreference.ActionItem choice = actions.get(selected);
          if (CustomMenus.addItem(context, menuId, choice.text, choice.value)) refresh();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void itemOptions(int index) {
    Context context = requireContext();
    CustomMenus.Menu menu = CustomMenus.find(context, menuId);
    if (menu == null || index < 0 || index >= menu.items.size()) return;
    CharSequence[] options = {
      getString(R.string.custom_menu_move_up),
      getString(R.string.custom_menu_move_down),
      getString(R.string.custom_menu_remove_item)
    };
    new AlertDialog.Builder(context)
        .setTitle(menu.items.get(index).label)
        .setItems(options, (dialog, option) -> {
          if (option == 0) CustomMenus.moveItem(context, menuId, index, -1);
          if (option == 1) CustomMenus.moveItem(context, menuId, index, 1);
          if (option == 2) CustomMenus.removeItem(context, menuId, index);
          refresh();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void rename() {
    Context context = requireContext();
    CustomMenus.Menu menu = CustomMenus.find(context, menuId);
    if (menu == null) return;
    KeyboardSafeEditText field = new KeyboardSafeEditText(context);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setSingleLine(true);
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(60)});
    field.setText(menu.name);
    field.setSelection(field.length());
    AlertDialog dialog = new AlertDialog.Builder(context)
        .setTitle(R.string.custom_menu_rename)
        .setView(field)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create();
    dialog.setOnShowListener(shown ->
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
          String name = field.getText().toString().trim();
          if (name.isEmpty()) {
            field.setError(getString(R.string.custom_menu_name_required));
            return;
          }
          if (CustomMenus.nameTaken(context, menuId, name)) {
            field.setError(getString(R.string.custom_menu_name_taken));
            return;
          }
          if (CustomMenus.rename(context, menuId, name)) {
            dialog.dismiss();
            refresh();
          }
        }));
    dialog.show();
  }

  private void confirmDelete() {
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.custom_menu_delete)
        .setMessage(R.string.custom_menu_delete_confirm)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
          CustomMenus.delete(requireContext(), menuId);
          requireActivity().getOnBackPressedDispatcher().onBackPressed();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }
}
