package com.google.android.accessibility.talkback.preference.base;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.Toast;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.google.android.accessibility.material.preference.AccessibilitySuiteListPreference;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.output.FailoverTextToSpeech;
import com.google.android.accessibility.utils.output.VoiceProfiles;
import com.google.android.accessibility.utils.output.VoiceSwitching;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class VoiceSwitchingFragment extends TalkbackBaseFragment {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;
  private String engine = "";
  private List<Voice> voices;
  private Locale language = Locale.getDefault();
  private TextToSpeech tts;
  private int generation;
  private AlertDialog dialog;

  @Override
  public CharSequence getTitle() {
    return getText(R.string.voice_switch_title);
  }

  @Override
  public void onCreatePreferences(Bundle state, String rootKey) {
    getPreferenceManager().setStorageDeviceProtected();
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    prefs = SharedPreferencesUtils.getSharedPreferences(requireContext());
    VoiceProfiles.VoiceProfile profile = VoiceProfiles.readActive(prefs);
    String selected = profile != null && !profile.engine().isEmpty()
        ? profile.engine() : FailoverTextToSpeech.getSelectedEngine(requireContext());
    List<String> installed = FailoverTextToSpeech.getInstalledTtsEngines(requireContext().getPackageManager());
    engine = state == null ? (selected == null ? "" : selected) : state.getString("engine", "");
    if (!installed.contains(engine)) engine = installed.isEmpty() ? "" : installed.get(0);
  }

  @Override
  public void onResume() {
    super.onResume();
    loadVoices();
  }

  @Override
  public void onPause() {
    generation++;
    stopTts();
    if (dialog != null) dialog.dismiss();
    super.onPause();
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    state.putString("engine", engine);
    super.onSaveInstanceState(state);
  }

  private void stopTts() {
    if (tts != null) {
      try {
        tts.shutdown();
      } catch (RuntimeException e) {
        voices = new ArrayList<>();
      }
      tts = null;
    }
  }

  private void loadVoices() {
    int request = ++generation;
    stopTts();
    voices = null;
    render();
    if (engine.isEmpty()) {
      voices = new ArrayList<>();
      render();
      return;
    }
    tts = new TextToSpeech(requireContext().getApplicationContext(),
        status -> handler.post(() -> {
          if (!isResumed() || request != generation || tts == null) return;
          voices = new ArrayList<>();
          if (status == TextToSpeech.SUCCESS) {
            try {
              voices = VoiceSwitching.installed(tts.getVoices());
              Voice current = tts.getVoice();
              if (current != null && current.getLocale() != null) language = current.getLocale();
            } catch (RuntimeException e) {
              voices.clear();
            }
          }
          stopTts();
          render();
        }), engine);
  }

  private Preference row(String key, CharSequence title) {
    Preference row = new AccessibilitySuitePreference(requireContext());
    row.setKey(key);
    row.setTitle(title);
    row.setPersistent(false);
    row.setIconSpaceReserved(false);
    getPreferenceScreen().addPreference(row);
    return row;
  }

  private void render() {
    getPreferenceScreen().removeAll();
    List<String> engines = FailoverTextToSpeech.getInstalledTtsEngines(requireContext().getPackageManager());
    ListPreference engineRow = new AccessibilitySuiteListPreference(requireContext());
    engineRow.setKey("voice_switch_engine");
    engineRow.setTitle(R.string.title_pref_tts_engine);
    engineRow.setPersistent(false);
    engineRow.setEntries(engines.stream().map(name ->
        FailoverTextToSpeech.getEngineDisplayName(requireContext(), name)).toArray(CharSequence[]::new));
    engineRow.setEntryValues(engines.toArray(new CharSequence[0]));
    engineRow.setValue(engine);
    engineRow.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
    engineRow.setOnPreferenceChangeListener((preference, value) -> {
      engine = value.toString();
      loadVoices();
      return true;
    });
    getPreferenceScreen().addPreference(engineRow);
    Preference choose = row("voice_switch_choose", getText(R.string.voice_switch_choose));
    choose.setEnabled(voices != null && !voices.isEmpty());
    if (voices == null) choose.setSummary(R.string.voice_switch_loading);
    else if (voices.isEmpty()) choose.setSummary(R.string.voice_switch_no_voices);
    choose.setOnPreferenceClickListener(preference -> {
      chooseVoices();
      return true;
    });
    List<String> order = VoiceSwitching.readOrder(prefs, engine);
    Preference reset = row("voice_switch_reset", getText(R.string.voice_switch_reset));
    reset.setEnabled(!order.isEmpty());
    reset.setOnPreferenceClickListener(preference -> {
      VoiceSwitching.saveOrder(prefs, engine, new ArrayList<>());
      render();
      return true;
    });
    if (order.isEmpty()) {
      choose.setSummary(voices == null ? R.string.voice_switch_loading
          : voices.isEmpty() ? R.string.voice_switch_no_voices : R.string.voice_switch_default);
    }
    for (int index = 0; index < order.size(); index++) {
      String name = order.get(index);
      row("voice_switch_row_" + name, getString(R.string.voice_switch_ordered_name, index + 1, name))
          .setOnPreferenceClickListener(preference -> {
            moveVoice(name);
            return true;
          });
    }
  }

  private void chooseVoices() {
    if (voices == null || voices.isEmpty()) return;
    List<String> order = VoiceSwitching.readOrder(prefs, engine);
    List<String> names = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (Voice voice : voices) {
      names.add(voice.getName());
      labels.add(voice.getName() + ", " + voice.getLocale().getDisplayName());
    }
    for (String name : order) {
      if (!names.contains(name)) {
        names.add(name);
        labels.add(getString(R.string.voice_profile_not_installed, name));
      }
    }
    List<String> defaults = new ArrayList<>();
    for (Voice voice : VoiceSwitching.candidates(voices, language, order)) defaults.add(voice.getName());
    boolean[] checked = new boolean[names.size()];
    for (int index = 0; index < names.size(); index++) {
      checked[index] = order.isEmpty() ? defaults.contains(names.get(index)) : order.contains(names.get(index));
    }
    dialog = new AlertDialog.Builder(requireContext())
        .setTitle(R.string.voice_switch_choose)
        .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked,
            (clicked, which, enabled) -> checked[which] = enabled)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null).create();
    dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      List<String> selected = new ArrayList<>();
      for (String name : order) {
        if (checked[names.indexOf(name)]) selected.add(name);
      }
      for (int index = 0; index < names.size(); index++) {
        if (checked[index] && !selected.contains(names.get(index))) selected.add(names.get(index));
      }
      if (selected.isEmpty()) {
        Toast.makeText(requireContext(), R.string.voice_switch_choose_one, Toast.LENGTH_SHORT).show();
        return;
      }
      VoiceSwitching.saveOrder(prefs, engine, selected);
      dialog.dismiss();
      render();
    }));
    dialog.show();
  }

  private void moveVoice(String name) {
    List<String> order = VoiceSwitching.readOrder(prefs, engine);
    int position = order.indexOf(name);
    if (position < 0) return;
    List<String> actions = new ArrayList<>();
    List<Integer> shifts = new ArrayList<>();
    if (position > 0) {
      actions.add(getString(R.string.voice_switch_move_up));
      shifts.add(-1);
    }
    if (position < order.size() - 1) {
      actions.add(getString(R.string.voice_switch_move_down));
      shifts.add(1);
    }
    actions.add(getString(R.string.voice_switch_remove));
    shifts.add(0);
    dialog = new AlertDialog.Builder(requireContext()).setTitle(name)
        .setItems(actions.toArray(new CharSequence[0]), (clicked, which) -> {
          int shift = shifts.get(which);
          if (shift == 0) order.remove(position);
          else Collections.swap(order, position, position + shift);
          VoiceSwitching.saveOrder(prefs, engine, order);
          render();
        }).setNegativeButton(android.R.string.cancel, null).create();
    dialog.show();
  }
}
