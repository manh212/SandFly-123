/*
 * Copyright 2018 Google Inc.
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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.TwoStatePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.report.DiagnosticsExport;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.AccessibilityMessageFormat;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Fragment holding a set of developer preferences. */
public class DeveloperPrefFragment extends TalkbackBaseFragment {

  private static final String TAG = "DeveloperPrefFragment";
  private String versionInfo;

  /** Preferences managed by this activity. */
  private SharedPreferences prefs;

  private Context context;
  private FragmentManager fragmentManager;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  /** AlertDialog to ask if user really wants to disable explore by touch. */
  private A11yAlertDialogWrapper exploreByTouchDialog;

  /** Flag whether content-observer is watching system touch-explore setting. */
  private boolean contentObserverRegistered = false;

  public DeveloperPrefFragment() {
    super(R.xml.developer_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_category_developer_settings);
  }

  @Override
  public @Nullable CharSequence getSubTitle() {
    return shouldShowVersionInSubtitle() ? versionInfo : null;
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    context = getContext();
    fragmentManager = getActivity().getSupportFragmentManager();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);

    initVersionInfo();

    // Remove preferences for features that are not supported by device.
    initTouchExplorationPreference();
    initLatencyPreparationPreferences();

    final @Nullable Preference prefVersion =
        findPreference(getString(R.string.pref_developer_version_code_key));
    if (!shouldShowVersionInSubtitle() && (prefVersion != null) && (versionInfo != null)) {
      prefVersion.setSummary(versionInfo);
    } else {
      getPreferenceScreen().removePreference(prefVersion);
    }

    final @Nullable TwoStatePreference multipleGestureSetPref =
        findPreference(getString(R.string.pref_multiple_gesture_set_key));
    if (multipleGestureSetPref != null) {
      if (FeatureSupport.supportMultipleGestureSet()
          && FeatureFlagReader.useMultipleGestureSet(context)) {
        multipleGestureSetPref.setOnPreferenceChangeListener(
            (preference, newValue) -> {
              final SharedPreferences.Editor prefEditor = prefs.edit();
              // Reset gesture set to set 0
              prefEditor
                  .remove(context.getResources().getString(R.string.pref_gesture_set_key))
                  .apply();
              return true;
            });
      } else {
        getPreferenceScreen().removePreference(multipleGestureSetPref);
      }
    }

    final @Nullable Preference export =
        findPreference(getString(R.string.pref_export_diagnostics_to_downloads_key));
    if (export != null) {
      export.setOnPreferenceClickListener(
          preference -> {
            exportToDownloads();
            return true;
          });
    }
    final @Nullable Preference copy =
        findPreference(getString(R.string.pref_copy_diagnostics_key));
    if (copy != null) {
      copy.setOnPreferenceClickListener(
          preference -> {
            copyToClipboard();
            return true;
          });
    }
    final @Nullable Preference delete =
        findPreference(getString(R.string.pref_delete_debug_log_key));
    if (delete != null) {
      delete.setOnPreferenceClickListener(
          preference -> {
            deleteDebugLog();
            return true;
          });
    }
  }

  /** Keeps the speech-preparation switches available only while latency extras are enabled. */
  private void initLatencyPreparationPreferences() {
    final @Nullable TwoStatePreference disableLatencyExtras =
        findPreference(getString(R.string.pref_disable_latency_extras_key));
    final @Nullable Preference prepareAdjacent = findPreference("pref_prepare_adjacent_speech");
    final @Nullable Preference prepareSecondAhead =
        findPreference("pref_prepare_second_ahead_speech");
    if (disableLatencyExtras == null) {
      return;
    }
    final Runnable updatePreparationEnabled =
        () -> {
          boolean enabled = !disableLatencyExtras.isChecked();
          if (prepareAdjacent != null) {
            prepareAdjacent.setEnabled(enabled);
          }
          if (prepareSecondAhead != null) {
            prepareSecondAhead.setEnabled(enabled);
          }
        };
    updatePreparationEnabled.run();
    disableLatencyExtras.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          boolean enabled = !((Boolean) newValue);
          if (prepareAdjacent != null) {
            prepareAdjacent.setEnabled(enabled);
          }
          if (prepareSecondAhead != null) {
            prepareSecondAhead.setEnabled(enabled);
          }
          return true;
        });
  }

  @Override
  public void onDestroy() {
    executor.shutdownNow();
    super.onDestroy();
  }

  private static boolean shouldShowVersionInSubtitle() {
    // Watch does not have action bar.
    // TV does have an action bar but it doesn't support a subtitle.
    return !FeatureSupport.supportSettingsTheme()
        && !FormFactorUtils.isAndroidWear()
        && !FormFactorUtils.isAndroidTv();
  }

  private void initVersionInfo() {
    versionInfo = null;

    // Shows TalkBack's abbreviated version number in the action bar.
    try {
      PackageInfo packageInfo =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      if (packageInfo != null) {
        long versionCode =
            FeatureSupport.supportLongVersionCode()
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;
        versionInfo =
            getString(
                R.string.talkback_preferences_subtitle,
                packageInfo.versionName + " (" + versionCode + ")");
      }
    } catch (NameNotFoundException e) {
      LogUtils.e(TAG, "Can't find PackageInfo by the package name.");
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    // Monitor the touch-explore system-setting.
    @Nullable TalkBackService talkBackService = TalkBackService.getInstance();
    if (talkBackService == null || !talkBackService.supportsTouchScreen()) {
      return;
    }

    Uri uri = Settings.Secure.getUriFor(Settings.Secure.TOUCH_EXPLORATION_ENABLED);
    context.getContentResolver().registerContentObserver(uri, false, touchExploreObserver);
    contentObserverRegistered = true;
  }

  @Override
  public void onPause() {
    // Stop monitoring the touch-explore system-setting.
    if (contentObserverRegistered) {
      context.getContentResolver().unregisterContentObserver(touchExploreObserver);
    }

    super.onPause();
  }

  private void exportToDownloads() {
    Context appContext = requireContext().getApplicationContext();
    executor.execute(
        () -> {
          String text = DiagnosticsExport.INSTANCE.buildForExport(appContext);
          boolean saved = DiagnosticsExport.INSTANCE.saveToDownloads(appContext, text);
          mainHandler.post(
              () ->
                  Toast.makeText(
                          appContext,
                          saved
                              ? R.string.message_diagnostics_saved_to_downloads
                              : R.string.message_diagnostics_save_failed,
                          Toast.LENGTH_LONG)
                      .show());
        });
  }

  private void copyToClipboard() {
    Context appContext = requireContext().getApplicationContext();
    executor.execute(
        () -> {
          String text = DiagnosticsExport.INSTANCE.build(appContext);
          mainHandler.post(
              () -> {
                ClipboardManager clipboard = appContext.getSystemService(ClipboardManager.class);
                if (clipboard != null) {
                  clipboard.setPrimaryClip(
                      ClipData.newPlainText(
                          appContext.getString(R.string.title_pref_export_diagnostics), text));
                }
                Toast.makeText(
                        appContext, R.string.message_diagnostics_copied, Toast.LENGTH_LONG)
                    .show();
              });
        });
  }

  private void deleteDebugLog() {
    Context appContext = requireContext().getApplicationContext();
    executor.execute(
        () -> {
          boolean deleted = LogUtils.clearLogFiles();
          mainHandler.post(
              () ->
                  Toast.makeText(
                          appContext,
                          deleted
                              ? R.string.message_debug_log_deleted
                              : R.string.message_debug_log_nothing_to_delete,
                          Toast.LENGTH_LONG)
                      .show());
        });
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Touch-explore preference methods.

  private final ContentObserver touchExploreObserver =
      new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
          if (selfChange) {
            return;
          }

          // The actual state of touch exploration has changed.
          updateTouchExplorationDisplay();
        }
      };

  /** Assigns the appropriate intent to the touch exploration preference. */
  private void initTouchExplorationPreference() {
    final TwoStatePreference prefTouchExploration =
        (TwoStatePreference) findPreference(getString(R.string.pref_explore_by_touch_reflect_key));
    if (prefTouchExploration == null) {
      return;
    }

    // Ensure that changes to the reflected preference's checked state never
    // trigger content observers.
    prefTouchExploration.setPersistent(false);

    // Synchronize the reflected state.
    updateTouchExplorationDisplay();

    // Set up listeners that will keep the state synchronized.
    prefTouchExploration.setOnPreferenceChangeListener(touchExplorationChangeListener);

    // Initialize preference dialog
    exploreByTouchDialog =
        A11yAlertDialogWrapper.materialDialogBuilder(context, fragmentManager)
            .setTitle(R.string.dialog_title_disable_exploration)
            .setMessage(R.string.dialog_message_disable_exploration)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnCancelListener(null)
            .setPositiveButton(
                android.R.string.ok,
                (DialogInterface dialog, int which) -> {
                  if (setTouchExplorationRequested(false)) {
                    prefTouchExploration.setChecked(false);
                  }
                })
            .create();
  }

  /**
   * Updates the preferences state to match the actual state of touch exploration. This is called
   * once when the preferences activity launches and again whenever the actual state of touch
   * exploration changes.
   */
  private void updateTouchExplorationDisplay() {
    TwoStatePreference prefTouchExploration =
        (TwoStatePreference) findPreference(getString(R.string.pref_explore_by_touch_reflect_key));
    if (prefTouchExploration == null) {
      return;
    }

    ContentResolver resolver = context.getContentResolver();
    Resources res = getResources();
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);

    boolean requestedState =
        SharedPreferencesUtils.getBooleanPref(
            prefs, res, R.string.pref_explore_by_touch_key, R.bool.pref_explore_by_touch_default);
    boolean reflectedState = prefTouchExploration.isChecked();
    boolean actualState =
        TalkBackService.isServiceActive() ? isTouchExplorationEnabled(resolver) : requestedState;

    // If touch exploration is actually off and we requested it on, the user
    // must have declined the "Enable touch exploration" dialog. Update the
    // requested value to reflect this.
    if (requestedState != actualState) {
      LogUtils.d(TAG, "Set touch exploration preference to reflect actual state %b", actualState);
      SharedPreferencesUtils.putBooleanPref(
          prefs, res, R.string.pref_explore_by_touch_key, actualState);
    }

    // Ensure that the check box preference reflects the requested state,
    // which was just synchronized to match the actual state.
    if (reflectedState != actualState) {
      prefTouchExploration.setChecked(actualState);
    }
  }

  private final OnPreferenceChangeListener touchExplorationChangeListener =
      new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
          final boolean requestedState = Boolean.TRUE.equals(newValue);

          // If the user is trying to turn touch exploration off, show
          // a confirmation dialog and don't change anything.
          if (!requestedState) {
            exploreByTouchDialog.show();
            return false;
          }

          return setTouchExplorationRequested(true); // requestedState
        }
      };

  /**
   * Updates the preference that controls whether TalkBack will attempt to request Explore by Touch.
   *
   * @param requestedState The state requested by the user.
   * @return Whether to update the reflected state.
   */
  private boolean setTouchExplorationRequested(boolean requestedState) {

    final SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);

    // Update the "requested" state. This will trigger a listener in
    // TalkBack that changes the "actual" state.
    SharedPreferencesUtils.putBooleanPref(
        prefs, getResources(), R.string.pref_explore_by_touch_key, requestedState);

    // If TalkBack is inactive, we should immediately reflect the change in
    // "requested" state.
    if (!TalkBackService.isServiceActive()) {
      return true;
    }

    TalkBackService talkBackService = TalkBackService.getInstance();
    if (requestedState && talkBackService != null && talkBackService.shouldShowTutorial()) {
      talkBackService.showTutorial();
    }

    // If accessibility is on, we should wait for the "actual" state to
    // change, then reflect that change. If the user declines the system's
    // touch exploration dialog, the "actual" state will not change and
    // nothing needs to happen.
    LogUtils.d(TAG, "TalkBack active, waiting for EBT request to take effect");
    return false;
  }

  private static boolean isTouchExplorationEnabled(ContentResolver resolver) {
    return Settings.Secure.getInt(resolver, Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0) == 1;
  }

  // TODO: Separate function for duplicate OnPreferenceChangeListener code.

  // TODO: Separate function for duplicate OnPreferenceChangeListener code.


}
