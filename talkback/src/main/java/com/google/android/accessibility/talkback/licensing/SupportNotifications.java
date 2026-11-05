package com.google.android.accessibility.talkback.licensing;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.core.os.UserManagerCompat;
import io.meridian.licensing.FirebaseEvents;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.SupportChanges;
import io.meridian.licensing.SupportProtocol;
import org.json.JSONObject;

public final class SupportNotifications {
  public static final String UPDATED = "io.github.aaron_gh.meridian.SUPPORT_UPDATED";
  public static final String TICKET = "supportTicket";
  private static final String CHANNEL = "meridian_support_updates";
  private static Monitor current;

  private SupportNotifications() {}

  public static synchronized void start(Context context) {
    LicenseNotifications.start(context);
    if (current != null) return;
    current = new Monitor(context.getApplicationContext());
    current.thread.start();
  }

  public static synchronized void stop() {
    LicenseNotifications.stop();
    if (current == null) return;
    current.running = false;
    FirebaseEvents events = current.events;
    if (events != null) events.close();
    current.thread.interrupt();
    current = null;
  }

  private static final class Monitor implements Runnable {
    final Context context;
    final Thread thread;
    volatile boolean running = true;
    volatile FirebaseEvents events;
    volatile long retryDelay = 1_000;

    Monitor(Context context) {
      this.context = context;
      thread = new Thread(this, "meridian-support-updates");
      thread.setDaemon(true);
    }

    @Override public void run() {
      while (running) {
        FirebaseRest service = null;
        try {
          if (!ApkSignatureVerifier.isOfficial(context)) return;
          if (!UserManagerCompat.isUserUnlocked(context)) {
            Thread.sleep(5_000);
            continue;
          }
          service = Enrollment.service(context);
          FirebaseRest connected = service;
          SupportStore store = new SupportStore(context);
          String uid = service.uid();
          SupportChanges changes = new SupportChanges(store.notificationState(uid));
          FirebaseEvents stream = new FirebaseEvents();
          events = stream;
          if (!running) { stream.close(); return; }
          stream.read(service.eventsAddress("supportUsers/" + uid), (event, value) -> {
            if (!running) return;
            boolean[] delivered = {false};
            changes.accept(event, value, (ticket, item, reply) -> {
              delivered[0] = true;
              String text;
              String identity;
              if (reply) {
                String messageId = item.getString("lastReplyId");
                JSONObject message = connected.get("supportMessages/" + ticket + "/" + messageId);
                if (!"staff".equals(message.optString("kind"))) return;
                text = message.optString("text");
                identity = messageId;
              } else {
                text = "Trạng thái mới: " + SupportProtocol.stateName(item.optString("status"));
                identity = "status-" + item.optLong("statusAt");
              }
              String content = text;
              String id = identity;
              new Handler(Looper.getMainLooper()).post(() -> {
                if (running) notifyUpdate(context, ticket, item.optString("subject"), content, id);
              });
            });
            store.notificationState(uid, changes.saved());
            retryDelay = 1_000;
            if (running && delivered[0]) context.sendBroadcast(new Intent(UPDATED).setPackage(context.getPackageName()));
          });
          stream.close();
        } catch (Exception ignored) {
          if (service != null && ignored instanceof FirebaseEvents.AuthenticationExpired) service.invalidateAuthentication();
        } finally {
          FirebaseEvents stream = events;
          if (stream != null) stream.close();
          events = null;
        }
        if (!running) return;
        try { Thread.sleep(retryDelay); }
        catch (InterruptedException stopped) { return; }
        retryDelay = Math.min(60_000, retryDelay * 2);
      }
    }

  }

  static boolean enabled(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return false;
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }

  static void notifyUpdate(Context context, String ticket, String subject, String text, String identity) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    manager.createNotificationChannel(new NotificationChannel(CHANNEL,
        "Cập nhật yêu cầu hỗ trợ", NotificationManager.IMPORTANCE_HIGH));
    Intent open = new Intent(context, SupportActivity.class).putExtra(TICKET, ticket)
        .setData(Uri.parse("meridian://support/" + ticket))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent action = PendingIntent.getActivity(context, 0, open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification = new Notification.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(subject.isEmpty() ? "Cập nhật yêu cầu hỗ trợ" : subject)
        .setContentText(text)
        .setStyle(new Notification.BigTextStyle().bigText(text))
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(action).setAutoCancel(true).setOnlyAlertOnce(true).build();
    try { manager.notify("support:" + ticket, identity.hashCode(), notification); }
    catch (SecurityException ignored) {}
  }
}
