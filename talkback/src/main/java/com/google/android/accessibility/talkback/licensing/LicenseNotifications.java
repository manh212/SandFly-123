package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.core.os.UserManagerCompat;
import io.meridian.licensing.FirebaseEvents;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.LicenseEventState;
import io.meridian.licensing.RecoveryProtocol;

final class LicenseNotifications {
  private static Monitor current;

  private LicenseNotifications() {}

  static synchronized void start(Context context) {
    if (current != null) return;
    current = new Monitor(context.getApplicationContext());
    current.thread.start();
  }

  static synchronized void stop() {
    if (current == null) return;
    current.running = false;
    if (current.events != null) current.events.close();
    current.thread.interrupt();
    current = null;
  }

  private static final class Monitor implements Runnable {
    final Context context;
    final Thread thread;
    volatile boolean running = true;
    volatile FirebaseEvents events;

    Monitor(Context context) {
      this.context = context;
      thread = new Thread(this, "meridian-license-updates");
      thread.setDaemon(true);
    }

    @Override public void run() {
      long delay = 1_000;
      while (running) {
        FirebaseRest service = null;
        try {
          if (!ApkSignatureVerifier.isOfficial(context) || !DeviceLicenseManager.recoveryConfigured()) return;
          if (!UserManagerCompat.isUserUnlocked(context)) {
            Thread.sleep(5_000);
            continue;
          }
          service = Enrollment.service(context);
          String request = DeviceLicenseManager.recoveryRequest(context);
          LicenseEventState state = new LicenseEventState();
          String[] accepted = {""};
          FirebaseEvents stream = new FirebaseEvents();
          events = stream;
          if (!running) { stream.close(); return; }
          stream.read(service.eventsAddress("licenseEvents/" + RecoveryProtocol.deviceId(request), false), (event, update) -> {
            if (!running) return;
            String value = state.accept(event, update, request);
            if (value == null || value.equals(accepted[0])) return;
            DeviceLicenseManager.acceptRemote(context, value, stream.serverTimeMillis());
            accepted[0] = value;
            new Handler(Looper.getMainLooper()).post(() -> { if (running) ActivationReminder.onPolicyChanged(); });
          });
          delay = 1_000;
        } catch (Exception error) {
          if (service != null && error instanceof FirebaseEvents.AuthenticationExpired) service.invalidateAuthentication();
        } finally {
          FirebaseEvents stream = events;
          if (stream != null) stream.close();
          events = null;
        }
        if (!running) return;
        try { Thread.sleep(delay); }
        catch (InterruptedException stopped) { return; }
        delay = Math.min(60_000, delay * 2);
      }
    }
  }
}
