package com.google.android.accessibility.talkback.licensing;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import com.google.android.accessibility.talkback.R;
import java.lang.ref.WeakReference;

/**
 * Non-blocking, dismissible registration reminder, shared between Settings and the
 * running accessibility service. The screen reader and its speech are never disabled.
 *
 * All lifecycle methods are called on the main thread. No timer runs if both Settings
 * and the accessibility service are gone, and only one dialog may be shown at a time.
 */
public final class ActivationReminder {
  private static final String TAG = "ActivationReminder";
  private static final long INTERVAL_MS = 60_000L;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private static WeakReference<AccessibilityService> serviceRef = new WeakReference<>(null);
  private static WeakReference<Activity> settingsRef = new WeakReference<>(null);
  private static AlertDialog dialog;
  private static Context dialogContext;
  private static boolean activationVisible;
  private static boolean scheduled;
  private static long lastShownElapsed = -INTERVAL_MS;

  private ActivationReminder() {}

  private static final Runnable TICK =
      new Runnable() {
        @Override
        public void run() {
          scheduled = false;
          if (!hasOwner()) {
            closeDialog();
            return;
          }
          maybeShow();
          schedule();
        }
      };

  /** Called once the accessibility service is ready to offer overlays. */
  public static void startService(AccessibilityService service) {
    serviceRef = new WeakReference<>(service);
    schedule();
  }

  /** Called on both normal and early service shutdown paths. */
  public static void stopService(AccessibilityService service) {
    if (serviceRef.get() == service) {
      serviceRef.clear();
      if (dialogContext == service) closeDialog();
    }
    stopIfUnused();
  }

  /** Opens a prompt promptly when Settings becomes visible. */
  public static void resumeSettings(Activity activity) {
    settingsRef = new WeakReference<>(activity);
    if (dialog != null && dialogContext != activity) closeDialog();
    schedule();
    MAIN.postDelayed(
        () -> {
          if (settingsRef.get() == activity && !activity.isFinishing()) maybeShow();
        },
        400L);
  }

  public static void pauseSettings(Activity activity) {
    if (settingsRef.get() == activity) {
      settingsRef.clear();
      if (dialogContext == activity) closeDialog();
    }
    stopIfUnused();
  }

  /** Do not cover the entry form while the user is activating. */
  public static void setActivationVisible(boolean visible) {
    activationVisible = visible;
    if (visible) closeDialog();
  }

  private static boolean hasOwner() {
    Activity a = settingsRef.get();
    return (a != null && !a.isFinishing()) || serviceRef.get() != null;
  }

  private static void schedule() {
    if (!scheduled && hasOwner()) {
      scheduled = true;
      MAIN.postDelayed(TICK, INTERVAL_MS);
    }
  }

  private static void stopIfUnused() {
    if (!hasOwner()) {
      MAIN.removeCallbacks(TICK);
      scheduled = false;
      closeDialog();
    }
  }

  private static void closeDialog() {
    AlertDialog old = dialog;
    dialog = null;
    dialogContext = null;
    if (old != null) {
      try {
        old.dismiss();
      } catch (RuntimeException e) {
        Log.w(TAG, "Unable to close reminder", e);
      }
    }
  }

  private static void maybeShow() {
    if (activationVisible || (dialog != null && dialog.isShowing())) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastShownElapsed < INTERVAL_MS) return;

    Activity activity = settingsRef.get();
    boolean inSettings = activity != null && !activity.isFinishing();
    AccessibilityService service = serviceRef.get();
    Context owner = inSettings ? activity : service;
    if (owner == null) return;
    if (!DeviceLicenseManager.configured() || DeviceLicenseManager.isLicensed(owner)) {
      closeDialog();
      return;
    }

    // Avoid covering the lock screen or waking the phone solely for a prompt.
    if (!inSettings) {
      PowerManager power = (PowerManager) owner.getSystemService(Context.POWER_SERVICE);
      KeyguardManager keyguard = (KeyguardManager) owner.getSystemService(Context.KEYGUARD_SERVICE);
      if ((power != null && !power.isInteractive())
          || (keyguard != null && keyguard.isKeyguardLocked())) {
        return;
      }
    }

    try {
      AlertDialog next =
          new AlertDialog.Builder(owner)
              .setTitle(R.string.title_sandfly_registration_reminder)
              .setMessage(R.string.summary_sandfly_registration_reminder)
              .setNegativeButton(R.string.action_sandfly_registration_later, (d, which) -> {})
              .setPositiveButton(
                  R.string.action_sandfly_registration_open,
                  (d, which) -> {
                    Intent intent = new Intent(owner, ActivationActivity.class);
                    if (!inSettings) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    owner.startActivity(intent);
                  })
              .create();
      if (!inSettings) {
        // Accessibility services may present their own accessible windows without
        // requesting the SYSTEM_ALERT_WINDOW permission.
        Window window = next.getWindow();
        if (window == null) return;
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
      }
      next.setOnDismissListener(
          d -> {
            if (dialog == next) {
              dialog = null;
              dialogContext = null;
            }
          });
      next.show();
      dialog = next;
      dialogContext = owner;
      lastShownElapsed = now;
    } catch (RuntimeException e) {
      // An overlay can be rejected by some device firmware. Do not crash speech.
      Log.w(TAG, "Could not display reminder", e);
    }
  }
}
