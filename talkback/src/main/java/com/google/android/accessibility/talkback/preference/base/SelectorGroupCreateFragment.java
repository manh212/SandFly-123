package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.Group;
import com.google.android.accessibility.talkback.selector.SelectorGroupManager.ItemInfo;
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class SelectorGroupCreateFragment extends TalkbackBaseFragment {
  private final LinkedHashSet<String> selectedItems = new LinkedHashSet<>();
  private KeyboardSafeEditText nameField;
  private Preference chooseItems;
  private String savedName = "";

  @Override
  public CharSequence getTitle() {
    return getText(R.string.sgm_create_title);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    if (savedInstanceState != null) {
      savedName = savedInstanceState.getString("sgm_draft_name", "");
      ArrayList<String> ids = savedInstanceState.getStringArrayList("sgm_draft_items");
      if (ids != null) {
        selectedItems.addAll(ids);
      }
    }
    Context context = requireContext();
    PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
    setPreferenceScreen(screen);

    chooseItems = new Preference(context);
    chooseItems.setKey("sgm_choose");
    chooseItems.setTitle(R.string.sgm_choose_items);
    chooseItems.setOnPreferenceClickListener(preference -> {
      chooseItems();
      return true;
    });
    screen.addPreference(chooseItems);
    updateCount();

    Preference create = new Preference(context);
    create.setKey("sgm_create");
    create.setTitle(R.string.sgm_create);
    create.setOnPreferenceClickListener(preference -> {
      createGroup();
      return true;
    });
    screen.addPreference(create);

    Preference cancel = new Preference(context);
    cancel.setKey("sgm_cancel");
    cancel.setTitle(R.string.sgm_cancel);
    cancel.setOnPreferenceClickListener(preference -> {
      getParentFragmentManager().popBackStack();
      return true;
    });
    screen.addPreference(cancel);
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View list = super.onCreateView(inflater, container, savedInstanceState);
    Context context = requireContext();
    LinearLayout wrapper = new LinearLayout(context);
    wrapper.setOrientation(LinearLayout.VERTICAL);

    nameField = new KeyboardSafeEditText(context);
    nameField.setHint(R.string.sgm_group_name);
    nameField.setSingleLine(true);
    nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    nameField.setText(savedName);
    int inset = (int) (16 * getResources().getDisplayMetrics().density);
    nameField.setPadding(inset, inset, inset, inset);
    wrapper.addView(
        nameField,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    wrapper.addView(
        list,
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    return wrapper;
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("sgm_draft_name", nameField == null ? savedName : nameField.getText().toString());
    state.putStringArrayList("sgm_draft_items", new ArrayList<>(selectedItems));
  }

  @Override
  public void onDestroyView() {
    nameField = null;
    super.onDestroyView();
  }

  private void chooseItems() {
    List<ItemInfo> available = SelectorGroupManager.availableItems(requireContext());
    CharSequence[] labels = new CharSequence[available.size()];
    boolean[] checked = new boolean[available.size()];
    LinkedHashSet<String> draft = new LinkedHashSet<>(selectedItems);
    for (int index = 0; index < available.size(); index++) {
      ItemInfo item = available.get(index);
      labels[index] = item.title;
      checked[index] = draft.contains(item.id);
    }
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.sgm_choose_items)
        .setMultiChoiceItems(
            labels,
            checked,
            (dialog, position, selected) -> {
              String id = available.get(position).id;
              if (selected) {
                draft.add(id);
              } else {
                draft.remove(id);
              }
            })
        .setPositiveButton(
            R.string.sgm_done,
            (dialog, which) -> {
              selectedItems.clear();
              selectedItems.addAll(draft);
              updateCount();
              SelectorGroupScreenUi.focus(this, "sgm_choose", null);
            })
        .setNegativeButton(R.string.sgm_cancel, null)
        .show();
  }

  private void updateCount() {
    if (chooseItems != null) {
      chooseItems.setSummary(getString(R.string.sgm_selected_count, selectedItems.size()));
    }
  }

  private void createGroup() {
    if (nameField == null) {
      return;
    }
    String name = nameField.getText().toString().trim();
    if (name.isEmpty()) {
      nameField.setError(getString(R.string.sgm_name_required));
      return;
    }
    if (selectedItems.isEmpty()) {
      Toast.makeText(requireContext(), R.string.sgm_items_required, Toast.LENGTH_SHORT).show();
      SelectorGroupScreenUi.focus(this, "sgm_choose", null);
      return;
    }
    if (SelectorGroupManager.isNameTaken(requireContext(), name, "")) {
      nameField.setError(getString(R.string.sgm_name_exists));
      return;
    }
    if (!SelectorGroupManager.addGroup(
        requireContext(), name, new ArrayList<>(selectedItems))) {
      Toast.makeText(requireContext(), R.string.sgm_save_failed, Toast.LENGTH_SHORT).show();
      return;
    }
    List<Group> groups = SelectorGroupManager.getGroups(requireContext());
    Group created = groups.get(groups.size() - 1);
    Bundle result = new Bundle();
    result.putString(SelectorGroupScreenUi.FOCUS_ID, created.id);
    result.putString(SelectorGroupScreenUi.MESSAGE, getString(R.string.sgm_created, name));
    getParentFragmentManager().setFragmentResult(SelectorGroupScreenUi.RESULT, result);
    getParentFragmentManager().popBackStack();
  }
}
