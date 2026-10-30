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

public final class CustomMenusFragment extends TalkbackBaseFragment {
  @Override
  public CharSequence getTitle() {
    return getText(R.string.custom_menus_title);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    getPreferenceManager().setStorageDeviceProtected();
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    refresh();
  }

  @Override
  public void onResume() {
    super.onResume();
    refresh();
  }

  private void refresh() {
    Context context = requireContext();
    PreferenceScreen screen = getPreferenceScreen();
    screen.removeAll();
    for (CustomMenus.Menu menu : CustomMenus.menus(context)) {
      Preference row = new Preference(context);
      row.setTitle(menu.name);
      row.setKey("custom_menu_" + menu.id);
      row.setSummary(getString(R.string.custom_menu_item_count, menu.items.size()));
      row.setFragment(CustomMenuEditorFragment.class.getName());
      row.getExtras().putString(CustomMenuEditorFragment.ARG_MENU_ID, menu.id);
      screen.addPreference(row);
    }
    Preference add = new Preference(context);
    add.setTitle(R.string.custom_menu_add);
    add.setKey("custom_menu_add");
    add.setOnPreferenceClickListener(
        preference -> {
          askForNewName();
          return true;
        });
    screen.addPreference(add);
  }

  private void askForNewName() {
    Context context = requireContext();
    KeyboardSafeEditText field = new KeyboardSafeEditText(context);
    field.setHint(R.string.custom_menu_name);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setSingleLine(true);
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(60)});
    AlertDialog dialog = new AlertDialog.Builder(context)
        .setTitle(R.string.custom_menu_add)
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
          if (CustomMenus.nameTaken(context, "", name)) {
            field.setError(getString(R.string.custom_menu_name_taken));
            return;
          }
          if (CustomMenus.add(context, name) != null) {
            dialog.dismiss();
            refresh();
          }
        }));
    dialog.show();
  }
}
