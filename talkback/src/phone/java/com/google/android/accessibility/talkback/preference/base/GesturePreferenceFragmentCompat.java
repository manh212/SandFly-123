/*
 * Copyright (C) 2020 Google Inc.
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

import static com.google.android.accessibility.talkback.preference.base.GestureListPreference.TYPE_ACTION_ITEM;
import static com.google.android.accessibility.talkback.preference.base.GestureListPreference.TYPE_TITLE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SearchView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.RecyclerView.Recycler;
import androidx.recyclerview.widget.RecyclerView.State;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory;
import com.google.android.accessibility.material.preference.AccessibilitySuiteRadioButtonPreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.preference.base.GestureListPreference.ActionItem;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A fragment contains a customized list view for TalkBack supported actions.
 *
 * <p>Note: This class is only for Phone.
 */
public class GesturePreferenceFragmentCompat extends TalkbackBaseFragment {
  private static final String TAG = "GesturePreferenceFragmentCompat";

  private static final String SEARCH_QUERY = "SEARCH_QUERY";
  private static final String GESTURE_KEYS = "GESTURE_KEYS";
  private static final String GESTURE_LABELS = "GESTURE_LABELS";

  private String searchQuery = "";
  private String[] gestureKeys;
  private CharSequence[] gestureLabels;
  private AlertDialog assignmentDialog;

  private static final String GESTURE_TITLE = "GESTURE_TITLE";
  private static final String GESTURE_INITIAL_VALUE = "GESTURE_INITIAL_VALUE";
  private static final String GESTURE_ACTION_ITEMS = "GESTURE_ACTION_ITEMS";

  private Parcelable[] items;
  private CharSequence title;
  private String initialValue;
  private String preferenceKey;

  private final OnPreferenceClickListener onPreferenceClickListener =
      new OnPreferenceClickListener() {
        @Override
        public boolean onPreferenceClick(Preference preference) {

          ActionItem item = (ActionItem) items[preference.getOrder()];
          if (gestureKeys != null && gestureLabels != null) {
            showGestureAssignment(item);
            return true;
          }
          boolean checked = TextUtils.equals(item.value, initialValue);
          if (checked) {
            // The selected action is clicked again and it is going to be unchecked. We should
            // return false to ignore it and don't set the value to targetGestureListPreference.
            popBackStack();
            return false;
          }

          SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(getContext());
          prefs.edit().putString(preferenceKey, item.value).apply();
          popBackStack();
          return true;
        }
      };

  private int selectedPosition;

  /** Creates the fragment from given {@link GestureListPreference}. */
  @Nullable
  public static GesturePreferenceFragmentCompat create(GestureListPreference preference) {
    // Do nothing.
    return null;
  }

  public static Bundle createBundleForFragmentArguments(GestureListPreference preference) {
    Bundle bundle = new Bundle();
    bundle.putString(ARG_PREFERENCE_ROOT, preference.getKey());
    bundle.putCharSequence(GESTURE_TITLE, preference.getTitle());
    bundle.putString(GESTURE_INITIAL_VALUE, preference.getCurrentValue());
    bundle.putParcelableArray(GESTURE_ACTION_ITEMS, preference.getActionItems());
    return bundle;
  }

  public static Bundle createBundleForGestureSearch(List<GestureListPreference> gestures) {
    Bundle bundle = createBundleForFragmentArguments(gestures.get(0));
    String[] keys = new String[gestures.size()];
    CharSequence[] labels = new CharSequence[gestures.size()];
    for (int i = 0; i < gestures.size(); i++) {
      keys[i] = gestures.get(i).getKey();
      labels[i] = gestures.get(i).getTitle();
    }
    bundle.putStringArray(GESTURE_KEYS, keys);
    bundle.putCharSequenceArray(GESTURE_LABELS, labels);
    bundle.putCharSequence(
        GESTURE_TITLE, gestures.get(0).getContext().getString(R.string.gesture_search_actions));
    bundle.putString(GESTURE_INITIAL_VALUE, "");
    return bundle;
  }

  private void showGestureAssignment(ActionItem item) {
    assignmentDialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.gesture_assign_action, item.text))
            .setItems(
                gestureLabels,
                (dialog, which) -> {
                  SharedPreferencesUtils.getSharedPreferences(requireContext())
                      .edit()
                      .putString(gestureKeys[which], item.value)
                      .apply();
                  popBackStack();
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View preferences = super.onCreateView(inflater, container, savedInstanceState);
    LinearLayout layout = new LinearLayout(requireContext());
    layout.setOrientation(LinearLayout.VERTICAL);
    SearchView search = new SearchView(requireContext());
    search.setIconifiedByDefault(false);
    search.setQueryHint(getString(R.string.gesture_search_actions));
    search.setQuery(searchQuery, false);
    search.clearFocus();
    search.setOnQueryTextListener(
        new SearchView.OnQueryTextListener() {
          @Override
          public boolean onQueryTextSubmit(String query) {
            search.clearFocus();
            return true;
          }

          @Override
          public boolean onQueryTextChange(String query) {
            searchQuery = query;
            setPreferenceScreen(createPreferenceScreen());
            return true;
          }
        });
    layout.addView(
        search,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    layout.addView(
        preferences, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    return layout;
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(SEARCH_QUERY, searchQuery);
  }

  @Override
  public void onDestroyView() {
    if (assignmentDialog != null) {
      assignmentDialog.dismiss();
      assignmentDialog = null;
    }
    super.onDestroyView();
  }

  private static String normalizeSearch(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replace('đ', 'd');
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    if (savedInstanceState == null) {
      // We post a runnable to scroll to the selected position in the 1st time.
      scrollToSelectedPosition();
    }
  }

  @NonNull
  @Override
  public LayoutManager onCreateLayoutManager() {
    return new LinearLayoutManager(requireContext()) {
      @Override
      public int getSelectionModeForAccessibility(
          @NonNull Recycler recycler, @NonNull State state) {
        return CollectionInfoCompat.SELECTION_MODE_SINGLE;
      }
    };
  }

  /** Pops back the fragment and restores the a11y importance attribute for the parent fragment. */
  private void popBackStack() {
    FragmentActivity activity = getActivity();
    if (activity == null) {
      return;
    }
    FragmentManager fragmentManager = getParentFragmentManager();
    fragmentManager.popBackStackImmediate();
    if (fragmentManager.getBackStackEntryCount() == 0) {
      activity.finish();
    }
  }

  @Override
  protected CharSequence getTitle() {
    return title;
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    Bundle bundle = getArguments();
    if (bundle == null) {
      throw new IllegalArgumentException();
    }
    searchQuery = savedInstanceState == null ? "" : savedInstanceState.getString(SEARCH_QUERY, "");
    gestureKeys = bundle.getStringArray(GESTURE_KEYS);
    gestureLabels = bundle.getCharSequenceArray(GESTURE_LABELS);
    title = bundle.getCharSequence(GESTURE_TITLE);
    initialValue = bundle.getString(GESTURE_INITIAL_VALUE);
    items = bundle.getParcelableArray(GESTURE_ACTION_ITEMS);
    preferenceKey = rootKey;

    PreferenceSettingsUtils.setPreferenceScreen(this, createPreferenceScreen());
  }

  private PreferenceScreen createPreferenceScreen() {
    PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(getContext());
    Context context = getContext();

    AccessibilitySuitePreferenceCategory category = null;
    @Nullable AccessibilitySuitePreferenceCategory selectedCategory = null;
    List<AccessibilitySuitePreferenceCategory> titledCategories = new ArrayList<>();
    String query = normalizeSearch(searchQuery.trim());
    ActionItem item;
    for (int order = 0; order < items.length; order++) {
      item = (ActionItem) items[order];
      switch (item.viewType) {
        case TYPE_TITLE -> {
          category = new AccessibilitySuitePreferenceCategory(getContext());
          category.setTitle(item.text);
          preferenceScreen.addPreference(category);
          titledCategories.add(category);
        }
        case TYPE_ACTION_ITEM -> {
          String label = normalizeSearch(item.text);
          boolean matches = true;
          for (String word : query.split("\\s+")) {
            if (!label.contains(word)) {
              matches = false;
              break;
            }
          }
          if (!matches) {
            continue;
          }
          AccessibilitySuiteRadioButtonPreference radioButtonPreference =
              new AccessibilitySuiteRadioButtonPreference(context);
          radioButtonPreference.setTitle(item.text);
          boolean checked = TextUtils.equals(item.value, initialValue);
          radioButtonPreference.setChecked(checked);
          radioButtonPreference.setOnPreferenceClickListener(onPreferenceClickListener);
          radioButtonPreference.setOrder(order);
          radioButtonPreference.setSingleLineTitle(false);
          if (category == null) {
            // We create a category without title to add the beginning preference (e.g., "Tap to
            // assign").
            category = new AccessibilitySuitePreferenceCategory(getContext());
            preferenceScreen.addPreference(category);
          }
          radioButtonPreference.setKey(item.value);
          category.addPreference(radioButtonPreference);
          if (checked) {
            selectedCategory = category;
          }
          radioButtonPreference.setRequestInitialAccessibilityFocus(checked && query.isEmpty());
        }
        default -> {}
      }
    }

    for (int i = preferenceScreen.getPreferenceCount() - 1; i >= 0; i--) {
      Preference preference = preferenceScreen.getPreference(i);
      if (preference instanceof PreferenceGroup group && group.getPreferenceCount() == 0) {
        preferenceScreen.removePreference(preference);
      }
    }
    if (preferenceScreen.getPreferenceCount() == 0) {
      Preference empty = new Preference(context);
      empty.setTitle(R.string.gesture_search_no_results);
      empty.setSelectable(false);
      preferenceScreen.addPreference(empty);
    }

    for (AccessibilitySuitePreferenceCategory titled : titledCategories) {
      titled.setCollapsible(
          context.getString(R.string.category_collapsed),
          context.getString(R.string.category_expanded));
      if (titled == selectedCategory || !query.isEmpty()) {
        titled.setCollapsed(false);
      }
    }
    selectedPosition = findSelectedPosition(preferenceScreen);

    return preferenceScreen;
  }

  /** Returns the position in the list of the action in use, among the rows shown, or 0. */
  private static int findSelectedPosition(PreferenceScreen preferenceScreen) {
    int position = 0;
    for (int i = 0; i < preferenceScreen.getPreferenceCount(); i++) {
      Preference category = preferenceScreen.getPreference(i);
      position++;
      if (!(category instanceof PreferenceGroup group)) {
        continue;
      }
      for (int j = 0; j < group.getPreferenceCount(); j++) {
        Preference preference = group.getPreference(j);
        if (!preference.isVisible()) {
          continue;
        }
        if (preference instanceof AccessibilitySuiteRadioButtonPreference radioButton
            && radioButton.isChecked()) {
          return position;
        }
        position++;
      }
    }
    return 0;
  }

  private int getSelectedPositionInAdapter() {
    return selectedPosition;
  }

  private void scrollToSelectedPosition() {
    RecyclerView recyclerView = getListView();
    if (recyclerView == null) {
      LogUtils.w(TAG, "RecyclerView has been cleared.");
      return;
    }

    LinearLayoutManager linearLayoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
    if (linearLayoutManager != null) {
      linearLayoutManager.scrollToPositionWithOffset(getSelectedPositionInAdapter(), 0);
    }
  }
}
