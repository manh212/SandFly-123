package com.google.android.accessibility.talkback.preference.base;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.gesture.SmartContextGestures;
import com.google.android.accessibility.talkback.gesture.SmartContextGestures.Rule;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.MaterialComponentUtils;
import com.google.android.accessibility.utils.widget.DialogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SmartContextGestureSettingsFragment extends TalkbackBaseFragment {
  private static final String[] MATCH_TYPES =
      {"exact", "contains", "starts", "ends", "before", "after", "prefix", "suffix"};

  @Override
  public CharSequence getTitle() {
    return getString(R.string.smart_context_title);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    migrateLegacySettings(requireContext());
    getPreferenceManager().setStorageDeviceProtected();
    refresh();
  }

  static void migrateLegacySettings(Context context) {
    SharedPreferences target = SharedPreferencesUtils.getSharedPreferences(context);
    String marker = "smart_context_device_storage_migrated";
    if (target.getBoolean(marker, false)) return;
    SharedPreferences original = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
    SharedPreferences.Editor editor = target.edit();
    for (String builtin : SmartContextGestures.BUILTINS) {
      String key = "smart_context_builtin_" + builtin;
      if (original.contains(key) && !target.contains(key)) {
        editor.putBoolean(key, original.getBoolean(key, false));
      }
    }
    String selector = context.getString(R.string.pref_selector_smart_context_key);
    if (original.contains(selector) && !target.contains(selector)) {
      editor.putBoolean(selector, original.getBoolean(selector, false));
    }
    String maxScroll = SmartContextGestures.PREF_MAX_SCROLL;
    if (original.contains(maxScroll) && !target.contains(maxScroll)) {
      editor.putString(maxScroll, original.getString(maxScroll, "20"));
    }
    editor.putBoolean(marker, true).apply();
  }

  private void refresh() {
    Context context = requireContext();
    PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
    setPreferenceScreen(screen);

    SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(context);
    enabled.setKey(getString(R.string.pref_selector_smart_context_key));
    enabled.setTitle(R.string.smart_context_enable);
    enabled.setDefaultValue(false);
    screen.addPreference(enabled);

    SwitchPreferenceCompat autoSwitch = new SwitchPreferenceCompat(context);
    autoSwitch.setKey(SmartContextGestures.PREF_AUTO_SWITCH);
    autoSwitch.setTitle(R.string.smart_context_auto_switch);
    autoSwitch.setSummary(R.string.smart_context_auto_switch_summary);
    autoSwitch.setDefaultValue(false);
    screen.addPreference(autoSwitch);

    PreferenceCategory common = category(screen, R.string.smart_context_common);
    action(common, R.string.smart_context_up, ruleSummary(context, "", false),
        () -> showRules(context, "", false, this::refresh));
    action(common, R.string.smart_context_down, ruleSummary(context, "", true),
        () -> showRules(context, "", true, this::refresh));

    PreferenceCategory applications = category(screen, R.string.smart_context_applications);
    TalkBackService service = TalkBackService.getInstance();
    String current = service == null ? "" : SmartContextGestures.currentPackage(service);
    if (!current.isEmpty() && !current.equals(context.getPackageName())) {
      action(applications, R.string.smart_context_current_app, current,
          () -> showApplication(context, current, this::refresh));
    }
    for (String name : applicationNames(context)) {
      if (name.equals(current)) continue;
      Preference row = new Preference(context);
      row.setTitle(name);
      row.setOnPreferenceClickListener(preference -> {
        showApplication(context, name, this::refresh);
        return true;
      });
      applications.addPreference(row);
    }

    PreferenceCategory builtins = category(screen, R.string.smart_context_integrations);
    for (String name : SmartContextGestures.priorities(context)) {
      SwitchPreferenceCompat toggle = new SwitchPreferenceCompat(context);
      toggle.setKey("smart_context_builtin_" + name);
      toggle.setTitle(SmartContextGestures.builtinTitle(name));
      toggle.setDefaultValue(false);
      builtins.addPreference(toggle);
    }
    SwitchPreferenceCompat textRules = new SwitchPreferenceCompat(context);
    textRules.setKey(SmartContextGestures.PREF_TEXT_RULES_ENABLED);
    textRules.setTitle(R.string.smart_context_text_rules_enabled);
    textRules.setSummary(R.string.smart_context_text_rules_summary);
    textRules.setDefaultValue(true);
    builtins.addPreference(textRules);
    action(builtins, R.string.smart_context_priority, null, () -> showPriorities(context));

    PreferenceCategory options = category(screen, R.string.smart_context_search);
    ListPreference limit = new ListPreference(context);
    limit.setKey(SmartContextGestures.PREF_MAX_SCROLL);
    limit.setTitle(R.string.smart_context_scroll_limit);
    limit.setEntries(new String[] {"5", "10", "20", "50", getString(R.string.smart_context_unlimited)});
    limit.setEntryValues(new String[] {"5", "10", "20", "50", "-1"});
    limit.setDefaultValue("20");
    limit.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    options.addPreference(limit);
  }

  private static Set<String> applicationNames(Context context) {
    Set<String> names = new LinkedHashSet<>();
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    for (String key : prefs.getAll().keySet()) {
      if (key.startsWith("smart_context_mode_")) {
        String name = key.substring("smart_context_mode_".length());
        if (name.endsWith("_up")) name = name.substring(0, name.length() - 3);
        else if (name.endsWith("_down")) name = name.substring(0, name.length() - 5);
        if (!name.isEmpty()) names.add(name);
      } else if (key.startsWith("smart_context_rules_app_")) {
        String name = key.substring("smart_context_rules_app_".length());
        if (name.endsWith("_up")) name = name.substring(0, name.length() - 3);
        else if (name.endsWith("_down")) name = name.substring(0, name.length() - 5);
        if (!name.isEmpty()) names.add(name);
      }
    }
    return names;
  }

  private static String ruleSummary(Context context, String packageName, boolean down) {
    List<Rule> rules = SmartContextGestures.getRules(context, packageName, down);
    if (rules.isEmpty()) return context.getString(R.string.smart_context_empty);
    StringBuilder result = new StringBuilder();
    for (Rule rule : rules) {
      if (result.length() > 0) result.append(", ");
      result.append(rule.text);
    }
    return result.toString();
  }

  private static PreferenceCategory category(PreferenceScreen screen, int title) {
    PreferenceCategory category = new PreferenceCategory(screen.getContext());
    category.setTitle(title);
    screen.addPreference(category);
    return category;
  }

  private static void action(PreferenceCategory parent, int title, String summary, Runnable handler) {
    Preference item = new Preference(parent.getContext());
    item.setTitle(title);
    if (summary != null) item.setSummary(summary);
    item.setOnPreferenceClickListener(preference -> {
      handler.run();
      return true;
    });
    parent.addPreference(item);
  }

  private void showPriorities(Context context) {
    List<String> items = SmartContextGestures.priorities(context);
    String[] labels = new String[items.size()];
    for (int i = 0; i < items.size(); i++)
      labels[i] = (i + 1) + ". " + context.getString(SmartContextGestures.builtinTitle(items.get(i)));
    display(context, MaterialComponentUtils.alertDialogBuilder(context)
        .setTitle(R.string.smart_context_priority)
        .setItems(labels, (dialog, choice) -> {
          display(context, MaterialComponentUtils.alertDialogBuilder(context)
              .setTitle(labels[choice])
              .setItems(new String[] {
                  context.getString(R.string.smart_context_move_up),
                  context.getString(R.string.smart_context_move_down)},
                  (d, direction) -> {
                    int target = choice + (direction == 0 ? -1 : 1);
                    if (target >= 0 && target < items.size()) {
                      Collections.swap(items, choice, target);
                      SmartContextGestures.setPriorities(context, items);
                      refresh();
                    }
                    showPriorities(context);
                  }));
        }));
  }

  private static void showApplication(Context context, String packageName, Runnable refresh) {
    String[] labels = {
        context.getString(R.string.smart_context_up) + ": "
            + modeLabel(context, SmartContextGestures.getMode(context, packageName, false)),
        context.getString(R.string.smart_context_down) + ": "
            + modeLabel(context, SmartContextGestures.getMode(context, packageName, true))
    };
    display(context, MaterialComponentUtils.alertDialogBuilder(context)
        .setTitle(packageName)
        .setItems(labels, (dialog, index) ->
            showApplicationDirection(context, packageName, index == 1, refresh)));
  }

  private static String modeLabel(Context context, String mode) {
    return context.getString(
        SmartContextGestures.MODE_OWN.equals(mode) ? R.string.smart_context_mode_own :
            SmartContextGestures.MODE_OFF.equals(mode) ? R.string.smart_context_mode_off :
                R.string.smart_context_mode_inherit);
  }

  private static void showApplicationDirection(
      Context context, String packageName, boolean down, Runnable refresh) {
    String mode = SmartContextGestures.getMode(context, packageName, down);
    String[] modes = {
        context.getString(R.string.smart_context_mode_inherit),
        context.getString(R.string.smart_context_mode_own),
        context.getString(R.string.smart_context_mode_off)
    };
    int selected = SmartContextGestures.MODE_OWN.equals(mode) ? 1 :
        SmartContextGestures.MODE_OFF.equals(mode) ? 2 : 0;
    display(context, MaterialComponentUtils.alertDialogBuilder(context)
        .setTitle(down ? R.string.smart_context_down : R.string.smart_context_up)
        .setSingleChoiceItems(modes, selected, (dialog, selection) -> {
          SmartContextGestures.setMode(context, packageName, down,
              selection == 0 ? SmartContextGestures.MODE_INHERIT :
                  selection == 1 ? SmartContextGestures.MODE_OWN : SmartContextGestures.MODE_OFF);
          dialog.dismiss();
          refresh.run();
          if (selection == 1) showRules(context, packageName, down, refresh);
        })
        .setNeutralButton(R.string.smart_context_edit_rules,
            (dialog, selection) -> showRules(context, packageName, down, refresh)));
  }

  private static void showRules(Context context, String packageName, boolean down, Runnable refresh) {
    List<Rule> rules = SmartContextGestures.getRules(context, packageName, down);
    String[] items = new String[rules.size() + 1];
    for (int i = 0; i < rules.size(); i++) {
      Rule rule = rules.get(i);
      items[i] = rule.text + (rule.enabled ? "" : " (" + context.getString(R.string.smart_context_disabled) + ")");
    }
    items[rules.size()] = context.getString(R.string.smart_context_add);
    display(context, MaterialComponentUtils.alertDialogBuilder(context)
        .setTitle(down ? R.string.smart_context_down : R.string.smart_context_up)
        .setItems(items, (dialog, index) ->
            editRule(context, packageName, down,
                index < rules.size() ? rules.get(index) : null,
                () -> { refresh.run(); showRules(context, packageName, down, refresh); },
                "")));
  }

  public static void quickAdd(TalkBackService service) {
    String originalApp = SmartContextGestures.currentPackage(service);
    String text = SmartContextGestures.focusedText(service);
    String[] choices = {
        service.getString(R.string.smart_context_common),
        service.getString(R.string.smart_context_current_app)
    };
    display(service, MaterialComponentUtils.alertDialogBuilder(service)
        .setTitle(R.string.smart_context_add)
        .setItems(choices, (dialog, choice) ->
            editRule(service, choice == 0 ? "" : originalApp, true, null, () -> {}, text)));
  }

  public static void editRule(
      Context context, String packageName, boolean defaultDown, Rule previous,
      Runnable saved, String initialText) {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (context.getResources().getDisplayMetrics().density * 16);
    layout.setPadding(pad, pad, pad, pad);

    EditText name = new EditText(context);
    name.setSingleLine(false);
    name.setHint(R.string.smart_context_text);
    name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    name.setText(previous == null ? initialText : previous.text);
    layout.addView(name);

    label(layout, R.string.smart_context_direction);
    Spinner direction = spinner(context, new String[] {
        context.getString(R.string.smart_context_up),
        context.getString(R.string.smart_context_down)
    });
    direction.setSelection(defaultDown ? 1 : 0);
    layout.addView(direction);

    label(layout, R.string.smart_context_match);
    Spinner match = spinner(context, new String[] {
        context.getString(R.string.smart_context_match_exact),
        context.getString(R.string.smart_context_match_contains),
        context.getString(R.string.smart_context_match_starts),
        context.getString(R.string.smart_context_match_ends),
        context.getString(R.string.smart_context_match_before),
        context.getString(R.string.smart_context_match_after),
        context.getString(R.string.smart_context_match_prefix),
        context.getString(R.string.smart_context_match_suffix)
    });
    String initialMatch = previous == null ? "exact" : previous.match;
    int matchIndex = Arrays.asList(MATCH_TYPES).indexOf(initialMatch);
    match.setSelection(Math.max(0, matchIndex));
    layout.addView(match);

    EditText other = new EditText(context);
    other.setHint(R.string.smart_context_other_text);
    other.setText(previous == null ? "" : previous.other);
    layout.addView(other);

    CheckBox caseSensitive = new CheckBox(context);
    caseSensitive.setText(R.string.smart_context_case_sensitive);
    caseSensitive.setChecked(previous != null && previous.caseSensitive);
    layout.addView(caseSensitive);

    CheckBox enabled = new CheckBox(context);
    enabled.setText(R.string.smart_context_rule_enabled);
    enabled.setChecked(previous == null || previous.enabled);
    layout.addView(enabled);

    ScrollView scroll = new ScrollView(context);
    scroll.addView(layout);

    AlertDialog.Builder builder = MaterialComponentUtils.alertDialogBuilder(context)
        .setTitle(R.string.smart_context_add)
        .setView(scroll)
        .setPositiveButton(R.string.smart_context_save, null)
        .setNegativeButton(android.R.string.cancel, null);
    if (previous != null) {
      builder.setNeutralButton(R.string.smart_context_delete, (dialog, which) -> {
        List<Rule> rules = SmartContextGestures.getRules(context, packageName, defaultDown);
        rules.removeIf(rule -> rule == previous || rule.toJson().toString().equals(previous.toJson().toString()));
        SmartContextGestures.saveRules(context, packageName, defaultDown, rules);
        saved.run();
      });
    }
    AlertDialog dialog = display(context, builder);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      String value = name.getText().toString().trim();
      if (value.isEmpty()) {
        name.setError(context.getString(R.string.smart_context_text_required));
        return;
      }
      int selected = match.getSelectedItemPosition();
      String comparison = other.getText().toString().trim();
      if ((selected == 4 || selected == 5) && comparison.isEmpty()) {
        other.setError(context.getString(R.string.smart_context_text_required));
        return;
      }
      boolean down = direction.getSelectedItemPosition() == 1;
      if (previous != null) {
        List<Rule> rules = SmartContextGestures.getRules(context, packageName, defaultDown);
        rules.removeIf(rule -> rule.toJson().toString().equals(previous.toJson().toString()));
        SmartContextGestures.saveRules(context, packageName, defaultDown, rules);
      }
      SmartContextGestures.addRule(context, packageName, down,
          new Rule(value, MATCH_TYPES[Math.max(0, selected)], comparison,
              caseSensitive.isChecked(), enabled.isChecked()));
      dialog.dismiss();
      saved.run();
    });
  }

  private static Spinner spinner(Context context, String[] values) {
    Spinner result = new Spinner(context);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(
        context, android.R.layout.simple_spinner_item, values);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    result.setAdapter(adapter);
    return result;
  }

  private static void label(LinearLayout parent, int title) {
    TextView text = new TextView(parent.getContext());
    text.setText(title);
    parent.addView(text);
  }

  private static AlertDialog display(Context context, AlertDialog.Builder builder) {
    AlertDialog dialog = builder.create();
    if (context instanceof AccessibilityService) {
      DialogUtils.setWindowTypeToDialog(dialog.getWindow());
    }
    dialog.show();
    return dialog;
  }
}
