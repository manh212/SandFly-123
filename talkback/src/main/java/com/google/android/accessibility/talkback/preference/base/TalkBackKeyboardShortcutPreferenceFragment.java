/*
 * Copyright 2015 Google Inc.
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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import androidx.preference.ListPreference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService.TalkbackServiceStateNotifier.TalkBackServiceStateChangeListener;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.commands.CommandBindings;
import com.google.android.accessibility.talkback.commands.Commands;
import com.google.android.accessibility.talkback.keyboard.ArrowKeyNav;
import com.google.android.accessibility.talkback.keyboard.CustomKeymap;
import com.google.android.accessibility.talkback.keyboard.CustomKeymaps;
import com.google.android.accessibility.talkback.keyboard.KeyCombo;
import com.google.android.accessibility.talkback.keyboard.KeyComboManager;
import com.google.android.accessibility.talkback.keyboard.KeyComboModel;
import com.google.android.accessibility.talkback.keyboard.Keymap;
import com.google.android.accessibility.talkback.keyboard.NewKeyComboModel;
import com.google.android.accessibility.talkback.keyboard.SandFlyKey;
import com.google.android.accessibility.talkback.keyboard.TalkBackPhysicalKeyboardShortcut;
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils;
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Panel holding a set of keyboard shortcut preferences. */
public class TalkBackKeyboardShortcutPreferenceFragment extends TalkbackBaseFragment {
  private static final String TAG = "TalkBackKeyboardShortcutPreferenceFragment";
  // android.content.res.Resources.ID_NULL was introduced in API 29, but the min SDK version of
  // TalkBack is API 26. For compatibility, define a constant here instead of using ID_NULL.
  private static final int RESOURCE_ID_NULL = 0;

  private String keymap;
  private SharedPreferences prefs;
  private PreferenceScreen resetKeymapPreference;

  public TalkBackKeyboardShortcutPreferenceFragment() {
    super(R.xml.new_key_combo_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_manage_keyboard_shortcuts);
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    TalkBackService.TalkbackServiceStateNotifier.getInstance()
        .registerTalkBackServiceStateChangeListener(serviceStateChangeListener);
  }

  /** Updates fragment whenever their values change. */
  private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener =
      (prefs, key) -> {
        if (TextUtils.equals(key, getString(R.string.pref_select_keymap_key))
            || TextUtils.equals(
                key, getKeyComboManager().getKeyComboModel().getPreferenceKeyForTriggerModifier())) {
          // Refreshes key combo model after keymap or SandFly key changes.
          KeyComboManager keyComboManager = getKeyComboManager();
          keyComboManager.refreshKeyComboModel();
          keymap = getKeymap();
          updateFragment();
        }
      };

  private final DialogInterface.OnClickListener resetKeymapConfirmDialogPositive =
      (dialogInterface, i) -> {
        resetKeymap();
        dialogInterface.dismiss();

        PreferencesActivityUtils.announceText(
            getString(R.string.keycombo_menu_announce_reset_keymap), getActivity());
      };

  private final Preference.OnPreferenceClickListener resetKeymapPreferenceClickListener =
      (preference) -> {
        // Show confirm dialog.
        A11yAlertDialogWrapper.materialDialogBuilder(getActivity())
            .setTitle(getString(R.string.keycombo_menu_reset_keymap))
            .setMessage(getString(R.string.message_in_reset_keymap_confirm_dialog))
            .setPositiveButton(
                R.string.reset_button_in_reset_keymap_confirm_dialog,
                resetKeymapConfirmDialogPositive)
            .setNegativeButton(
                android.R.string.cancel,
                (DialogInterface dialogInterface, int i) -> dialogInterface.cancel())
            .create()
            .show();
        return true;
      };

  private final Preference.OnPreferenceClickListener
      viewSystemKeyboardShortcutsPreferenceClickListener =
          (preference) -> {
            if (getActivity() != null) {
              getActivity().requestShowKeyboardShortcuts();
            } else {
              LogUtils.w(TAG, "Could not resolve activity to call requestShowKeyboardShortcuts");
            }
            return true;
          };

  private final OnPreferenceChangeListener preferenceChangeListener =
      (preference, newValue) -> {
        String preferenceKeyForTriggerModifier =
            getKeyComboManager().getKeyComboModel().getPreferenceKeyForTriggerModifier();
        if (preference instanceof KeyboardShortcutDialogPreference keyboardPreference
            && newValue instanceof KeyCombo customizedKeyCombo) {
          keyboardPreference.setKeyCombo(customizedKeyCombo);
          keyboardPreference.notifyChanged();
        } else if (preference.getKey() != null
            && preference.getKey().equals(getString(R.string.pref_select_keymap_key))
            && newValue instanceof String chosenKeymap) {
          // Do nothing if keymap is the same.
          if (keymap.equals(chosenKeymap)) {
            return false;
          }

          // Announce new keymap.
          PreferencesActivityUtils.announceText(
              String.format(
                  getString(R.string.keycombo_menu_announce_active_keymap),
                  getKeymapName(chosenKeymap)),
              getActivity());
        } else if (preference.getKey() != null
            && preference.getKey().equals(preferenceKeyForTriggerModifier)
            && newValue instanceof String chosenTriggerModifier) {
          ListPreference listPreference = (ListPreference) preference;
          if (listPreference.getValue().equals(chosenTriggerModifier)) {
            return false;
          }
          PreferencesActivityUtils.announceText(
              getString(
                  R.string.keycombo_menu_announce_new_trigger_modifier,
                  listPreference.getEntries()[
                      listPreference.findIndexOfValue(chosenTriggerModifier)]),
              getActivity());
          return true;
        }
        return true;
      };

  private void resetKeymap() {
    KeyComboModel keyComboModel = getKeyComboManager().getKeyComboModel();
    for (String key : keyComboModel.getKeyComboMap().keySet()) {
      KeyCombo defaultKeyCombo = keyComboModel.getDefaultKeyCombo(key);
      // Do nothing if key combo code is not changed from default one.
      if (defaultKeyCombo.equals(keyComboModel.getKeyComboForKey(key))) {
        continue;
      }
      // Save with default key combo.
      keyComboModel.saveKeyCombo(key, defaultKeyCombo);

      // Update UI.
      KeyboardShortcutDialogPreference keyboardShortcutDialogPreference =
          (KeyboardShortcutDialogPreference) findPreference(key);
      keyboardShortcutDialogPreference.setKeyCombo(defaultKeyCombo);
      keyboardShortcutDialogPreference.notifyChanged();
    }

    ArrowKeyNav.reset(getContext());
    for (ArrowKeyNavCommand command : ArrowKeyNavCommand.values()) {
      if (findPreference(command.getPrefKey()) instanceof ArrowKeyNavKeyPreference preference) {
        preference.refresh();
      }
    }
  }

  private KeyComboManager getKeyComboManager() {
    TalkBackService talkBackService = TalkBackService.getInstance();

    return talkBackService == null
        ? KeyComboManager.create(getActivity())
        : talkBackService.getKeyComboManager();
  }

  private String getKeymapName(String keymap) {
    @Nullable CustomKeymap custom = CustomKeymaps.of(getContext(), keymap);
    return custom != null ? custom.getName() : getString(Keymap.of(getContext(), keymap).getNameRes());
  }

  private String getKeymap() {
    KeyComboManager keyComboManager = getKeyComboManager();
    return keyComboManager.getKeymap();
  }

  private int getPreferenceResourceId() {
    return R.xml.new_key_combo_preferences;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    prefs.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    TalkBackService.TalkbackServiceStateNotifier.getInstance()
        .unregisterTalkBackServiceStateChangeListener(serviceStateChangeListener);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);

    keymap = getKeymap();
    int preferenceResourceId = getPreferenceResourceId();
    if (preferenceResourceId == RESOURCE_ID_NULL) {
      LogUtils.w(TAG, "Preference resource id is null, can't create preferences.");
      return;
    }
    setPreferencesFromResource(preferenceResourceId, rootKey);

    prefs = SharedPreferencesUtils.getSharedPreferences(getContext());
    prefs.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);

    updatePreference();
  }

  /** Updates Fragment when KeyComboModel changes or resets keymap. */
  private void updateFragment() {
    PreferenceSettingsUtils.setPreferencesFromResource(this, getPreferenceResourceId(), null);
    updatePreference();
  }

  /** Updates preference, including UI, by KeyComboModel. */
  private void updatePreference() {
    // By default, the preference filter is applied in TalkbackBaseFragment which is only performed
    // 1st time the fragment created. Keyboard shortcut preference group can change between default
    // key map and new key map, so need to redo the filter when the preference changed.
    TalkBackPreferenceFilter talkBackPreferenceFilter =
        new TalkBackPreferenceFilter(getActivity().getApplicationContext());
    talkBackPreferenceFilter.filterPreferences(getPreferenceScreen());

    updateSelectKeymapPreferenceWithAvailableKeymaps();
    // The keymap's actions come after its other settings, which the SandFly key goes between.
    addCustomKeymapActions();
    addSandFlyKeyPreference();

    resetKeymapPreference = findPreference(getString(R.string.pref_reset_keymap_key));
    resetKeymapPreference.setOnPreferenceClickListener(resetKeymapPreferenceClickListener);
    Preference viewSystemKeyboardShortcutsPreference =
        findPreference(getString(R.string.pref_view_system_keyboard_shortcuts_key));
    if (viewSystemKeyboardShortcutsPreference != null) {
      viewSystemKeyboardShortcutsPreference.setOnPreferenceClickListener(
          viewSystemKeyboardShortcutsPreferenceClickListener);
    }

    updateDialogAndResetKeymapPreference();

    initPreferenceUIs(getPreferenceScreen());
    if (FeatureFlagReader.enableNewKeymap(getContext())
        && keymap.equals(getString(R.string.new_keymap_entry_value))) {
      updateDialogPreferencesTitle();
    }
  }

  private void updateDialogPreferencesTitle() {
    Set<String> keySet = getKeyComboManager().getKeyComboModel().getKeyComboMap().keySet();
    String browseModeCommandTitleTemplate =
        getString(R.string.template_keycombo_menu_browse_mode_command);
    for (String key : keySet) {
      KeyboardShortcutDialogPreference preference = findPreference(key);
      if (preference != null
          && preference.getTitle().toString().equals(browseModeCommandTitleTemplate)) {
        TalkBackPhysicalKeyboardShortcut action =
            TalkBackPhysicalKeyboardShortcut.getActionFromKey(getContext().getResources(), key);
        if (action == null || action.getKeyStrRes() == -1) {
          continue;
        }
        String keyCommandDescription = action.getDescription(getContext().getResources());
        preference.setTitle(String.format(browseModeCommandTitleTemplate, keyCommandDescription));
      }
    }
  }

  /**
   * Adds Choose SandFly key for the keymap in use, after Choose a keymap: each keymap has its own,
   * offering the keys that keep all of its shortcuts, and a custom keymap any key.
   */
  private void addSandFlyKeyPreference() {
    PreferenceCategory category = findPreference(getString(R.string.pref_keymap_category_key));
    Context context = getContext();
    if (category == null || context == null) {
      return;
    }
    @Nullable CustomKeymap custom = CustomKeymaps.of(context, keymap);
    Keymap builtIn = Keymap.of(context, keymap);
    List<SandFlyKey> sandFlyKeys =
        custom != null ? Arrays.asList(SandFlyKey.values()) : builtIn.getSandFlyKeys();
    SandFlyKey used = custom != null ? custom.sandFlyKey(context) : builtIn.sandFlyKey(context);
    List<CharSequence> entries = new ArrayList<>();
    List<CharSequence> entryValues = new ArrayList<>();
    for (SandFlyKey sandFlyKey : sandFlyKeys) {
      entries.add(getString(sandFlyKey.getNameRes()));
      entryValues.add(getString(sandFlyKey.getValueRes()));
    }
    // Choose a keymap first, then this, then the rest in their order.
    for (int i = 0; i < category.getPreferenceCount(); i++) {
      category.getPreference(i).setOrder(i == 0 ? 0 : i * 2 + 2);
    }
    ListPreference preference = new ListPreference(context);
    preference.setKey(getKeyComboManager().getKeyComboModel().getPreferenceKeyForTriggerModifier());
    preference.setPersistent(true);
    preference.setTitle(R.string.keycombo_menu_choose_trigger_modifier);
    preference.setDialogTitle(R.string.keycombo_menu_choose_trigger_modifier);
    preference.setEntries(entries.toArray(new CharSequence[0]));
    preference.setEntryValues(entryValues.toArray(new CharSequence[0]));
    preference.setSummary("%s");
    preference.setOrder(1);
    preference.setIconSpaceReserved(false);
    category.addPreference(preference);
    // Shows the key in use where none, or one this keymap doesn't offer, was saved.
    String usedValue = getString(used.getValueRes());
    if (!usedValue.equals(preference.getValue())) {
      preference.setValue(usedValue);
    }
  }

  /**
   * Adds New keymap, which copies the keymap in use under a name, and for a custom keymap, Rename
   * keymap and Delete keymap.
   */
  private void addCustomKeymapActions() {
    PreferenceCategory category = findPreference(getString(R.string.pref_keymap_category_key));
    Context context = getContext();
    if (category == null || context == null) {
      return;
    }
    Preference create = new Preference(context);
    create.setKey("pref_new_keymap");
    create.setTitle(R.string.keycombo_menu_new_keymap);
    create.setIconSpaceReserved(false);
    create.setOnPreferenceClickListener(
        preference -> {
          askForKeymapName(
              R.string.keycombo_menu_new_keymap,
              "",
              getString(R.string.keycombo_new_keymap_default_name, getKeymapName(keymap)),
              this::createKeymap);
          return true;
        });
    category.addPreference(create);
    @Nullable CustomKeymap custom = CustomKeymaps.of(context, keymap);
    if (custom == null) {
      return;
    }
    Preference rename = new Preference(context);
    rename.setKey("pref_rename_keymap");
    rename.setTitle(R.string.keycombo_menu_rename_keymap);
    rename.setIconSpaceReserved(false);
    rename.setOnPreferenceClickListener(
        preference -> {
          askForKeymapName(
              R.string.keycombo_menu_rename_keymap,
              custom.getName(),
              custom.getName(),
              name -> {
                CustomKeymaps.rename(getContext(), custom, name);
                updateFragment();
              });
          return true;
        });
    category.addPreference(rename);
    Preference delete = new Preference(context);
    delete.setKey("pref_delete_keymap");
    delete.setTitle(R.string.keycombo_menu_delete_keymap);
    delete.setIconSpaceReserved(false);
    delete.setOnPreferenceClickListener(
        preference -> {
          A11yAlertDialogWrapper.materialDialogBuilder(getActivity())
              .setTitle(getString(R.string.keycombo_delete_keymap_title, custom.getName()))
              .setPositiveButton(
                  R.string.keycombo_delete_keymap_button, (dialog, which) -> deleteKeymap(custom))
              .setNegativeButton(android.R.string.cancel, null)
              .create()
              .show();
          return true;
        });
    category.addPreference(delete);
  }

  /** Asks for a keymap's name, starting from [current], or suggesting [suggested] when empty. */
  private void askForKeymapName(
      int titleRes, String current, String suggested, Consumer<String> onName) {
    Context context = getContext();
    if (context == null) {
      return;
    }
    EditText field = new KeyboardSafeEditText(context);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setSingleLine(true);
    if (current.isEmpty()) {
      field.setHint(suggested);
    } else {
      field.setText(current);
      field.setSelection(field.length());
    }
    new AlertDialog.Builder(context)
        .setTitle(titleRes)
        .setView(VoiceProfilesFragment.padded(context, field))
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              String name = field.getText().toString().trim();
              onName.accept(name.isEmpty() ? suggested : name);
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
    field.requestFocus();
  }

  /** Makes a keymap named [name] from the shortcuts of the keymap in use, and uses it. */
  private void createKeymap(String name) {
    KeyComboModel model = getKeyComboManager().getKeyComboModel();
    boolean usesSequences =
        model instanceof NewKeyComboModel newModel && newModel.usesSequences();
    // The copy keeps the keymap's SandFly key, which its shortcuts are pressed with.
    @Nullable SandFlyKey sandFlyKey = getKeyComboManager().getSandFlyKey();
    CustomKeymap custom =
        CustomKeymaps.create(
            getContext(),
            name,
            usesSequences,
            sandFlyKey != null ? sandFlyKey : SandFlyKey.META,
            model.getKeyComboMap());
    prefs.edit().putString(getString(R.string.pref_select_keymap_key), custom.getValue()).apply();
    PreferencesActivityUtils.announceText(
        getString(R.string.keycombo_menu_announce_active_keymap, name), getActivity());
  }

  /** Forgets [custom] and uses the SandFly keymap where it was in use. */
  private void deleteKeymap(CustomKeymap custom) {
    CustomKeymaps.delete(getContext(), custom);
    prefs
        .edit()
        .putString(
            getString(R.string.pref_select_keymap_key), getString(Keymap.SANDFLY.getValueRes()))
        .apply();
    PreferencesActivityUtils.announceText(
        getString(R.string.keycombo_menu_announce_active_keymap, getString(Keymap.SANDFLY.getNameRes())),
        getActivity());
  }

  /** Updates select keymap preference with available keymaps. */
  private void updateSelectKeymapPreferenceWithAvailableKeymaps() {
    ListPreference keymapPreference = findPreference(getString(R.string.pref_select_keymap_key));
    List<CharSequence> entries = new ArrayList<>();
    List<CharSequence> entryValues = new ArrayList<>();
    for (Keymap available : Keymap.values()) {
      entries.add(getString(available.getNameRes()));
      entryValues.add(getString(available.getValueRes()));
    }
    for (CustomKeymap custom : CustomKeymaps.all(getContext())) {
      entries.add(custom.getName());
      entryValues.add(custom.getValue());
    }
    keymapPreference.setEntries(entries.toArray(new CharSequence[entries.size()]));
    keymapPreference.setEntryValues(entryValues.toArray(new CharSequence[entryValues.size()]));
  }

  /**
   * Initialize preference UIs.
   *
   * @param root Root element of preference UIs.
   */
  private void initPreferenceUIs(PreferenceGroup root) {
    if (root == null) {
      return;
    }

    final KeyComboModel keyComboModel = getKeyComboManager().getKeyComboModel();
    final String preferenceKeyForTriggerModifier =
        keyComboModel.getPreferenceKeyForTriggerModifier();
    final Map<String, KeyCombo> keyComboMap = keyComboModel.getKeyComboMap();
    for (int i = root.getPreferenceCount() - 1; i >= 0; i--) {
      final Preference preference = root.getPreference(i);
      final String key = preference.getKey();
      if (key != null && preference instanceof KeyboardShortcutDialogPreference) {
        if (!keyComboMap.containsKey(key)) {
          // Hide preference of unavailable key combo on this device.
          root.removePreference(preference);
          if (root.getPreferenceCount() == 0) {
            getPreferenceScreen().removePreference(root);
          }
        } else if (key.equals(getString(R.string.keycombo_shortcut_other_toggle_browse_mode))) {
          // Disable the preference to make it not customizable.
          KeyboardShortcutDialogPreference keyShortcutPreference =
              (KeyboardShortcutDialogPreference) preference;
          keyShortcutPreference.setEnabled(false);
          keyShortcutPreference.setSummary(
              getString(
                  R.string.shortcut_not_customizable,
                  keyShortcutPreference.getSummary().toString()));
        } else {
          // Set onPreferenceChangeListener.
          preference.setOnPreferenceChangeListener(preferenceChangeListener);
        }
      } else if ((key != null && key.equals(getString(R.string.pref_select_keymap_key)))
          || (key != null && key.equals(preferenceKeyForTriggerModifier))) {
        // Set onPreferenceChangeListener.
        preference.setOnPreferenceChangeListener(preferenceChangeListener);
      } else if (preference instanceof PreferenceGroup) {
        initPreferenceUIs((PreferenceGroup) preference);
      }
    }
  }

  private void setUpDialogPreference(Consumer<KeyboardShortcutDialogPreference> consumer) {
    final Set<String> keySet = getKeyComboManager().getKeyComboModel().getKeyComboMap().keySet();
    for (String key : keySet) {
      KeyboardShortcutDialogPreference preference = findPreference(key);
      if (preference != null) {
        consumer.accept(preference);
      }
    }
  }

  private void updateDialogAndResetKeymapPreference() {
    resetKeymapPreference.setEnabled(isServiceActive());
    setUpDialogPreference(
        preference -> {
          preference.setEnabled(isServiceActive());
        });
  }

  private boolean isServiceActive() {
    return TalkBackService.isServiceActive();
  }

  /** Enabled or disabled the dialog preference if the state of TalkbackService is changed. */
  TalkBackService.TalkbackServiceStateNotifier.TalkBackServiceStateChangeListener
      serviceStateChangeListener =
          new TalkBackServiceStateChangeListener() {
            @Override
            public void onServiceStateChange(boolean isServiceActive) {
              resetKeymapPreference.setEnabled(isServiceActive);
              setUpDialogPreference(
                  preference -> {
                    preference.setEnabled(isServiceActive);
                    if (isServiceActive) {
                      preference.updateKeyComboManager();
                      preference.onTriggerModifierChanged();
                    }
                  });
            }
          };
}
