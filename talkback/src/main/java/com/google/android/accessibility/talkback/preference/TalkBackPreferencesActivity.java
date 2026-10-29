/*
 * Copyright 2010 Google Inc.
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

package com.google.android.accessibility.talkback.preference;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentOnAttachListener;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.accessibility.talkback.HatsSurveyRequester;
import com.google.android.accessibility.talkback.migration.MigrationFiles;
import com.google.android.accessibility.talkback.preference.base.DeveloperPrefFragment;
import com.google.android.accessibility.talkback.preference.base.TalkBackPreferenceFragment;
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment;
import com.google.android.accessibility.talkback.preference.search.TalkBackSearchIndexablesProvider;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.PackageManagerUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;
import com.google.android.accessibility.utils.preference.PreferencesActivity;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Activity used to set TalkBack's service preferences.
 *
 * <p>Never change preference types. This is because of AndroidManifest.xml setting
 * android:restoreAnyVersion="true", which supports restoring preferences from a new play-store
 * installed talkback onto a clean device with older bundled talkback.
 * REFERTO
 */
public class TalkBackPreferencesActivity extends PreferencesActivity
    implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
        FragmentOnAttachListener {

  private static final String TAG = "PreferencesActivity";
  private static final String KEY_DEVELOPER_NOTICE_ACCEPTED = "developer_notice_accepted";

  private HatsSurveyRequester hatsSurveyRequester;

  private void assignSearchFragment(Intent intent) {
    if (TalkBackSearchIndexablesProvider.isFromSearchIndexablesContract(intent)) {
      TalkBackSearchIndexablesProvider.assignToFragmentFromSearch(intent);
    }
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    // Must be called before super.onCreate
    assignSearchFragment(getIntent());
    fillDefaultFragmentNameIfNecessary();
    getSupportFragmentManager().addFragmentOnAttachListener(this);
    super.onCreate(savedInstanceState);

    // Check RTL.
    boolean isLocaleRTL =
        TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL;
    boolean isRTL =
        getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

    if (isLocaleRTL && !isRTL) {
      getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }

    // Request the HaTS.
    if (supportHatsSurvey()) {
      hatsSurveyRequester = new HatsSurveyRequester(this);
      hatsSurveyRequester.requestSurvey();

      HatsRequesterViewModel viewModel =
          new ViewModelProvider(this).get(HatsRequesterViewModel.class);
      viewModel.setHatsSurveyRequester(hatsSurveyRequester);
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    getSupportFragmentManager().removeFragmentOnAttachListener(this);
  }

  @Override
  public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
    if (DeveloperPrefFragment.class.getName().equals(pref.getFragment())) {
      openDeveloperSettings(pref);
      return true;
    }

    // Fall back to the default implementation in PreferenceFragmentCompat for other form factors.
    if (!FormFactorUtils.isAndroidWear()) {
      return false;
    }

    if (pref.getFragment() == null) {
      return false;
    }
    final Fragment fragment =
        getSupportFragmentManager()
            .getFragmentFactory()
            .instantiate(getClassLoader(), pref.getFragment());

    // WearPreferenceFragment couldn't be restored correctly, so we start a new Activity to
    // prevent the failure of UI restoration.
    if (fragment instanceof TalkbackBaseFragment) {
      Intent intent = new Intent(this, TalkBackSubSettings.class);
      intent.putExtra(FRAGMENT_NAME, pref.getFragment());
      intent.putExtra(FRAGMENT_ARGS, pref.getExtras());
      startActivity(intent);
      return true;
    }
    return false;
  }

  private void openDeveloperSettings(Preference preference) {
    SharedPreferences appState =
        SharedPreferencesUtils.getSharedPreferences(this, MigrationFiles.STATE_PREFS);
    if (appState.getBoolean(KEY_DEVELOPER_NOTICE_ACCEPTED, false)) {
      navigateToDeveloperSettings(preference);
      return;
    }
    A11yAlertDialogWrapper.materialDialogBuilder(this, getSupportFragmentManager())
        .setTitle(R.string.title_developer_settings_notice)
        .setMessage(R.string.message_developer_settings_notice)
        .setNegativeButton(R.string.action_developer_settings_not_now, null)
        .setPositiveButton(
            R.string.action_developer_settings_continue,
            (dialog, which) -> {
              appState.edit().putBoolean(KEY_DEVELOPER_NOTICE_ACCEPTED, true).apply();
              navigateToDeveloperSettings(preference);
            })
        .create()
        .show();
  }

  private void navigateToDeveloperSettings(Preference preference) {
    if (FormFactorUtils.isAndroidWear()) {
      Intent intent = new Intent(this, TalkBackSubSettings.class);
      intent.putExtra(FRAGMENT_NAME, preference.getFragment());
      intent.putExtra(FRAGMENT_ARGS, preference.getExtras());
      startActivity(intent);
      return;
    }
    PreferenceFragmentCompat fragment = getFragmentByName(preference.getFragment());
    fragment.setArguments(preference.getExtras());
    FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
    transaction
        .replace(getContainerId(), fragment, getFragmentTag())
        .addToBackStack(null)
        .commit();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent keyEvent) {
    if ((keyEvent.getKeyCode() == KeyEvent.KEYCODE_BACK)
        && (keyEvent.getAction() == KeyEvent.ACTION_UP)
        && (hatsSurveyRequester != null)
        && (hatsSurveyRequester.handleBackKeyPress())) {
      return false;
    }
    return super.dispatchKeyEvent(keyEvent);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    assignSearchFragment(getIntent());

    String fragmentName = intent.getStringExtra(FRAGMENT_NAME);
    PreferenceFragmentCompat fragment = getFragmentByName(fragmentName);
    fragment.setArguments(intent.getBundleExtra(FRAGMENT_ARGS));
    LogUtils.e(TAG, "onNewIntent/getContainerId()= %s", getContainerId());
    getSupportFragmentManager()
        .beginTransaction()
        .replace(getContainerId(), fragment, getFragmentTag())
        // Add root page to back-history
        .addToBackStack(/* name= */ null)
        .commit();
  }

  @Override
  protected PreferenceFragmentCompat createPreferenceFragment() {
    Intent intent = getIntent();
    String fragmentName = null;
    Bundle bundle = null;
    if (intent != null) {
      fragmentName = intent.getStringExtra(FRAGMENT_NAME);
      bundle = intent.getBundleExtra(FRAGMENT_ARGS);
    }
    PreferenceFragmentCompat fragment = getFragmentByName(fragmentName);
    fragment.setArguments(bundle);
    return fragment;
  }

  @Override
  public void onAttachFragment(FragmentManager fragmentManager, Fragment fragment) {
    if ((fragment instanceof TalkbackBaseFragment)
        && (!(fragment instanceof TalkBackPreferenceFragment))) {
      dismissHatsSurvey();
    }
  }

  private static PreferenceFragmentCompat getFragmentByName(@Nullable String fragmentName) {
    if (TextUtils.isEmpty(fragmentName)) {
      return new TalkBackPreferenceFragment();
    }

    try {
      Class<?> fragmentClass =
          Class.forName(
              fragmentName,
              false,
              TalkBackPreferencesActivity.class.getClassLoader());
      if (PreferenceFragmentCompat.class.isAssignableFrom(fragmentClass)) {
        return (PreferenceFragmentCompat) fragmentClass.getDeclaredConstructor().newInstance();
      }
      LogUtils.w(TAG, "Not a settings screen: %s", fragmentName);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
      LogUtils.w(TAG, "Failed to load class: %s", fragmentName);
    }
    return new TalkBackPreferenceFragment();
  }

  // We try to fill the default fragment name for WearPreferenceActivity to initiate it for Wear.
  private void fillDefaultFragmentNameIfNecessary() {
    if (!FormFactorUtils.isAndroidWear()) {
      return;
    }
    Intent intent = getIntent();
    if (intent == null) {
      return;
    }
    String fragmentName = intent.getStringExtra(FRAGMENT_NAME);
    if (TextUtils.isEmpty(fragmentName)) {
      intent.putExtra(FRAGMENT_NAME, TalkBackPreferenceFragment.class.getCanonicalName());
    }
  }

  @Override
  protected boolean supportHatsSurvey() {
    // Platform should support Hats if GMS core is available.
    return PackageManagerUtils.hasGmsCorePackage(this);
  }

  /** Dismisses Hats survey. */
  private void dismissHatsSurvey() {
    if (hatsSurveyRequester != null) {
      hatsSurveyRequester.dismissSurvey();
      hatsSurveyRequester = null;
    }
  }

  /**
   * A {@link ViewModel} which encapsulates {@link HatsSurveyRequester} for use in TalkBack setting
   * fragments.
   */
  public static class HatsRequesterViewModel extends ViewModel {
    private HatsSurveyRequester hatsSurveyRequester;

    public HatsSurveyRequester getHatsSurveyRequester() {
      return hatsSurveyRequester;
    }

    public void setHatsSurveyRequester(HatsSurveyRequester hatsSurveyRequester) {
      this.hatsSurveyRequester = hatsSurveyRequester;
    }
  }

  /** Activity to launch TalkBack settings fragment. It is used only for wear. */
  public static class TalkBackSubSettings extends TalkBackPreferencesActivity {}
}
