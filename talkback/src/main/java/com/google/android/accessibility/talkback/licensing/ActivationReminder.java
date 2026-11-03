package com.google.android.accessibility.talkback.licensing;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.os.UserManagerCompat;
import com.google.android.accessibility.talkback.R;
import java.lang.ref.WeakReference;

/**
 * Offers registration immediately, then enforces a one-minute trial whenever the
 * accessibility service connects. Notifications are informative, not a clock source:
 * the deadline is based on elapsed realtime even when notification permission is denied.
 *
 * A valid offline registration cancels the deadline. If the issuer is not configured,
 * do not disable accessibility, because registration would be impossible.
 */
public final class ActivationReminder {
  private static final String TAG = "ActivationReminder";
  private static final String CHANNEL = "meridian_activation_countdown";
  private static final int NOTIFICATION_ID = 0x4d4552;
  private static final long PERIOD_MS = 60_000L;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private static WeakReference<AccessibilityService> serviceRef = new WeakReference<>(null);
  private static WeakReference<Activity> settingsRef = new WeakReference<>(null);
  private static AlertDialog dialog;
  private static Context dialogContext;
  private static boolean activationVisible;
  private static long deadlineElapsed;
  private static long lastDialogElapsed = -PERIOD_MS;
  private static boolean scheduled;

  private ActivationReminder() {}

  private static final Runnable TICK =
      new Runnable() {
        @Override
        public void run() {
          scheduled = false;
          AccessibilityService service = serviceRef.get();
          if (service != null) {
            if (!UserManagerCompat.isUserUnlocked(service)) {
              stopCountdown();
              return;
            }
            LicenseRecovery.start(service, false, null);
            if (!DeviceLicenseManager.configured() || DeviceLicenseManager.isLicensed(service)) {
              stopCountdown();
              schedule(PERIOD_MS);
              return;
            }
            if (deadlineElapsed == 0L) deadlineElapsed = SystemClock.elapsedRealtime() + PERIOD_MS;
            long remaining = deadlineElapsed - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
              // Do not make notification permission a condition for expiry.
              stopCountdown();
              serviceRef.clear();
              try {
                service.disableSelf();
              } catch (RuntimeException e) {
                Log.e(TAG, "Could not disable accessibility service", e);
              }
              return;
            }
            showNotification(service, remaining);
            maybeShowDialog(false);
            schedule(1_000L);
          } else if (settingsRef.get() != null) {
            maybeShowDialog(false);
            schedule(PERIOD_MS);
          } else {
            closeDialog();
          }
        }
      };

  public static void startService(AccessibilityService service) {
    serviceRef = new WeakReference<>(service);
    if (!UserManagerCompat.isUserUnlocked(service)) {
      stopCountdown();
      return;
    }
    if (!DeviceLicenseManager.configured() || DeviceLicenseManager.isLicensed(service)) {
      stopCountdown();
      LicenseRecovery.start(service, false, null);
      schedule(PERIOD_MS);
      return;
    }
    serviceRef = new WeakReference<>(service);
    LicenseRecovery.start(service, false, null);
    deadlineElapsed = SystemClock.elapsedRealtime() + PERIOD_MS;
    lastDialogElapsed = -PERIOD_MS;
    showNotification(service, PERIOD_MS);
    MAIN.postDelayed(() -> {
      if (serviceRef.get() == service) maybeShowDialog(true);
    }, 250L);
    schedule(1_000L);
  }

  public static void stopService(AccessibilityService service) {
    if (serviceRef.get() == service) {
      serviceRef.clear();
      deadlineElapsed = 0L;
      cancelNotification(service);
      if (dialogContext == service) closeDialog();
    }
    stopIfUnused();
  }

  /** Settings may show the dialog even before the screen reader service is enabled. */
  public static void resumeSettings(Activity activity) {
    LicenseRecovery.start(activity, false, null);
    settingsRef = new WeakReference<>(activity);
    if (dialog != null && dialogContext != activity) closeDialog();
    MAIN.postDelayed(() -> {
      if (settingsRef.get() == activity && !activity.isFinishing()) maybeShowDialog(true);
    }, 250L);
    schedule(serviceRef.get() != null ? 1_000L : PERIOD_MS);
  }

  public static void pauseSettings(Activity activity) {
    if (settingsRef.get() == activity) {
      settingsRef.clear();
      if (dialogContext == activity) closeDialog();
    }
    stopIfUnused();
  }

  /** Keep the activation form usable throughout the remaining trial period. */
  public static void setActivationVisible(boolean visible) {
    activationVisible = visible;
    if (visible) closeDialog();
  }

  /** Called after successfully storing a verified, device-bound registration. */
  public static void onActivated() {
    onPolicyChanged();
  }

  static void onPolicyChanged() {
    AccessibilityService service = serviceRef.get();
    Context context = service == null ? settingsRef.get() : service;
    if (context == null) return;
    if (DeviceLicenseManager.isLicensed(context)) {
      stopCountdown();
      schedule(PERIOD_MS);
    } else if (service != null) {
      if (deadlineElapsed == 0L) deadlineElapsed = SystemClock.elapsedRealtime() + PERIOD_MS;
      MAIN.removeCallbacks(TICK);
      scheduled = false;
      schedule(1_000L);
    }
  }

  private static void stopCountdown() {
    AccessibilityService service = serviceRef.get();
    if (service != null) cancelNotification(service);
    deadlineElapsed = 0L;
    closeDialog();
    MAIN.removeCallbacks(TICK);
    scheduled = false;
  }

  private static void schedule(long delay) {
    if (!scheduled && (serviceRef.get() != null || settingsRef.get() != null)) {
      scheduled = true;
      MAIN.postDelayed(TICK, delay);
    }
  }

  private static void stopIfUnused() {
    if (serviceRef.get() == null && settingsRef.get() == null) {
      MAIN.removeCallbacks(TICK);
      scheduled = false;
      closeDialog();
    }
  }

  private static void cancelNotification(Context context) {
    NotificationManager manager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager != null) manager.cancel(NOTIFICATION_ID);
  }

  private static void showNotification(AccessibilityService service, long remainingMs) {
    NotificationManager manager =
        (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    // Android 13+ may hide non-exempt notifications when the user denies permission.
    // The countdown is kept independently of this visibility setting.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && service.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      return;
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationChannel channel =
            new NotificationChannel(
                CHANNEL,
                service.getString(R.string.title_sandfly_countdown),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(service.getString(R.string.summary_sandfly_countdown_channel));
        manager.createNotificationChannel(channel);
      }
      Intent intent = new Intent(service, ActivationActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      PendingIntent pendingIntent =
          PendingIntent.getActivity(
              service, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification.Builder builder =
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
              ? new Notification.Builder(service, CHANNEL)
              : new Notification.Builder(service);
      builder
          .setSmallIcon(android.R.drawable.ic_dialog_alert)
          .setContentTitle(service.getString(R.string.title_sandfly_countdown))
          .setContentText(
              service.getString(
                  R.string.summary_sandfly_countdown_remaining,
                  Math.max(1L, (remainingMs + 999L) / 1000L)))
          .setContentIntent(pendingIntent)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setCategory(Notification.CATEGORY_REMINDER)
          .setShowWhen(true)
          .setWhen(System.currentTimeMillis() + remainingMs)
          .setUsesChronometer(true);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        builder.setChronometerCountDown(true);
      }
      manager.notify(NOTIFICATION_ID, builder.build());
    } catch (RuntimeException e) {
      // The user may deny Android 13+ notification permission. The deadline still applies.
      Log.w(TAG, "Registration countdown notification unavailable", e);
    }
  }

  private static void closeDialog() {
    AlertDialog old = dialog;
    dialog = null;
    dialogContext = null;
    if (old != null) {
      try { old.dismiss(); } catch (RuntimeException e) {
        Log.w(TAG, "Could not dismiss registration dialog", e);
      }
    }
  }

  private static void maybeShowDialog(boolean force) {
    if (activationVisible || (dialog != null && dialog.isShowing())) return;
    Activity activity = settingsRef.get();
    boolean inSettings = activity != null && !activity.isFinishing();
    AccessibilityService service = serviceRef.get();
    Context context = inSettings ? activity : service;
    if (context == null || !UserManagerCompat.isUserUnlocked(context)
        || !DeviceLicenseManager.configured()
        || DeviceLicenseManager.isLicensed(context)) return;

    long now = SystemClock.elapsedRealtime();
    if (!force && now - lastDialogElapsed < PERIOD_MS) return;
    long remaining = service == null
        ? PERIOD_MS : Math.max(0L, deadlineElapsed - now);
    if (service != null && remaining <= 0) return;

    try {
      AlertDialog next = new AlertDialog.Builder(context)
          .setTitle(R.string.title_sandfly_registration_reminder)
          .setMessage(
              context.getString(
                  R.string.summary_sandfly_trial_dialog,
                  Math.max(1L, (remaining + 999L) / 1000L)))
          .setNegativeButton(R.string.action_sandfly_registration_later, (d, w) -> {})
          .setPositiveButton(R.string.action_sandfly_registration_open, (d, w) -> {
            Intent open = new Intent(context, ActivationActivity.class);
            if (!inSettings) open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);
          })
          .create();
      if (!inSettings) {
        Window window = next.getWindow();
        if (window == null) return;
        window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
      }
      next.setOnDismissListener(d -> {
        if (dialog == next) { dialog = null; dialogContext = null; }
      });
      next.show();
      dialog = next;
      dialogContext = context;
      lastDialogElapsed = now;
    } catch (RuntimeException e) {
      // Do not crash the screen reader if a device forbids accessibility overlays.
      Log.w(TAG, "Could not show immediate registration dialog", e);
    }
  }
}
