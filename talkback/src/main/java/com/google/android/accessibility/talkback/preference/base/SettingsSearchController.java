package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.SearchView;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity;
import java.util.List;

final class SettingsSearchController {
  private final TalkBackPreferenceFragment fragment;
  private final PreferenceScreen original;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final SettingsSearchIndex.Builder builder;
  private List<SettingsSearchIndex.Entry> entries;
  private String query;
  private SearchView search;
  private boolean stopped;

  SettingsSearchController(TalkBackPreferenceFragment fragment, String query) {
    this.fragment = fragment;
    this.query = query;
    original = fragment.getPreferenceScreen();
    builder = new SettingsSearchIndex.Builder(fragment.requireContext());
  }

  View wrap(View preferences) {
    Context context = fragment.requireContext();
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    search = new SearchView(context);
    search.setIconifiedByDefault(false);
    search.setQueryHint(context.getString(R.string.settings_search_title));
    search.setQuery(query, false);
    search.clearFocus();
    search.setOnQueryTextListener(
        new SearchView.OnQueryTextListener() {
          @Override
          public boolean onQueryTextSubmit(String text) {
            dismissKeyboard();
            return true;
          }

          @Override
          public boolean onQueryTextChange(String text) {
            query = text;
            showResults();
            return true;
          }
        });
    layout.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    layout.addView(preferences, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    handler.post(this::indexNext);
    showResults();
    return layout;
  }

  String query() {
    return query;
  }

  void stop() {
    stopped = true;
    handler.removeCallbacksAndMessages(null);
  }

  private void indexNext() {
    if (stopped || !fragment.isAdded()) {
      return;
    }
    if (builder.step()) {
      handler.post(this::indexNext);
    } else {
      entries = builder.entries();
      showResults();
    }
  }

  private void showResults() {
    if (stopped) {
      return;
    }
    if (SearchMatcher.normalize(query).isEmpty()) {
      if (fragment.getPreferenceScreen() != original) {
        fragment.setPreferenceScreen(original);
      }
      return;
    }
    Context context = fragment.requireContext();
    PreferenceScreen screen = fragment.getPreferenceManager().createPreferenceScreen(context);
    if (entries == null) {
      addMessage(screen, R.string.settings_search_loading);
    } else {
      for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.search(entries, query)) {
        Preference row = new Preference(context);
        row.setTitle(entry.title);
        row.setSingleLineTitle(false);
        row.setIconSpaceReserved(false);
        if (!entry.path.isEmpty()) {
          row.setSummary(entry.path);
        }
        row.setOnPreferenceClickListener(
            preference -> {
              dismissKeyboard();
              ((TalkBackPreferencesActivity) fragment.requireActivity())
                  .openSearchResult(entry);
              return true;
            });
        screen.addPreference(row);
      }
      if (screen.getPreferenceCount() == 0) {
        addMessage(screen, R.string.settings_search_empty);
      }
    }
    fragment.setPreferenceScreen(screen);
  }

  private void addMessage(PreferenceScreen screen, int title) {
    Preference message = new Preference(fragment.requireContext());
    message.setTitle(title);
    message.setSelectable(false);
    message.setIconSpaceReserved(false);
    screen.addPreference(message);
  }

  private void dismissKeyboard() {
    search.clearFocus();
    InputMethodManager keyboard =
        (InputMethodManager) fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (keyboard != null) {
      keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }
  }
}
