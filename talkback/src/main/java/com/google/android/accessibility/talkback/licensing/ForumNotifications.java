package com.google.android.accessibility.talkback.licensing;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.ForumProtocol;
import org.json.JSONObject;

public final class ForumNotifications {
  private static final String CHANNEL = "meridian_forum";
  private static Monitor monitor;

  private ForumNotifications() {}

  public static synchronized void start(Context context) {
    if (monitor != null) return;
    monitor = new Monitor(context.getApplicationContext());
    monitor.thread.start();
  }

  public static synchronized void stop() {
    if (monitor == null) return;
    monitor.running = false;
    monitor.thread.interrupt();
    monitor = null;
  }

  private static final class Monitor implements Runnable {
    final Context context;
    final Thread thread;
    volatile boolean running = true;

    Monitor(Context context) {
      this.context = context;
      thread = new Thread(this, "meridian-forum-poll");
      thread.setDaemon(true);
    }

    @Override public void run() {
      while (running) {
        try {
          if (!ApkSignatureVerifier.isOfficial(context)) return;
          FirebaseRest service = Enrollment.service(context);
          String uid = service.uid();
          SharedPreferences state = context.getSharedPreferences("forum_alerts_" + uid, Context.MODE_PRIVATE);
          JSONObject notices = service.recent("forumNotices/" + uid, "$key", 50, null);
          String newest = state.getString("notice", null);
          String high = newest;
          for (java.util.Iterator<String> it = notices.keys(); it.hasNext();) {
            String id = it.next();
            if (high == null || id.compareTo(high) > 0) high = id;
            if (newest == null || id.compareTo(newest) <= 0) continue;
            JSONObject entry = notices.optJSONObject(id);
            if (entry == null || !ForumProtocol.validId(entry.optString("topic"))) continue;
            String label = "mention".equals(entry.optString("kind")) ? "Nhắc đến bạn" : "Trả lời bạn";
            ForumNotifications.notify(context, "forum:" + id, label, ForumActivity.class, entry.optString("topic"));
          }
          if (newest == null || (high != null && !high.equals(newest))) {
            state.edit().putString("notice", high == null ? "" : high).apply();
          }

          JSONObject announcements = service.get("forumAnnouncements");
          long oldAnnouncement = state.getLong("announcement", -1);
          long maxAnnouncement = oldAnnouncement;
          for (java.util.Iterator<String> it = announcements.keys(); it.hasNext();) {
            String topic = it.next();
            JSONObject item = announcements.optJSONObject(topic);
            long timestamp = item == null ? 0 : item.optLong("createdAt");
            if (timestamp > maxAnnouncement) maxAnnouncement = timestamp;
            if (oldAnnouncement >= 0 && timestamp > oldAnnouncement && ForumProtocol.validId(topic)) {
              ForumNotifications.notify(context, "announcement:" + topic, "Thông báo quan trọng", ForumActivity.class, topic);
            }
          }
          if (oldAnnouncement < 0 || maxAnnouncement != oldAnnouncement) {
            state.edit().putLong("announcement", Math.max(0, maxAnnouncement)).apply();
          }

          JSONObject messages = service.recent("forumChats/" + uid + "/messages", "$key", 25, null);
          String oldMessage = state.getString("chat", null);
          String newMessage = oldMessage;
          for (java.util.Iterator<String> it = messages.keys(); it.hasNext();) {
            String id = it.next();
            if (newMessage == null || id.compareTo(newMessage) > 0) newMessage = id;
            if (oldMessage == null || id.compareTo(oldMessage) <= 0) continue;
            JSONObject item = messages.optJSONObject(id);
            if (item != null && !uid.equals(item.optString("from"))) {
              ForumNotifications.notify(context, "chat:" + id, "Tin nhắn mới", OwnerChatActivity.class, "");
            }
          }
          if (oldMessage == null || (newMessage != null && !newMessage.equals(oldMessage))) {
            state.edit().putString("chat", newMessage == null ? "" : newMessage).apply();
          }
        } catch (InterruptedException stopped) {
          return;
        } catch (Exception ignored) {
        }
        if (!running) return;
        try { Thread.sleep(15L * 60 * 1000); }
        catch (InterruptedException stopped) { return; }
      }
    }
  }

  private static void notify(Context context, String key, String label,
      Class<?> activity, String topic) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return;
    manager.createNotificationChannel(new NotificationChannel(CHANNEL,
        "Thông báo Meridian", NotificationManager.IMPORTANCE_DEFAULT));
    Intent intent = new Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (!topic.isEmpty()) intent.putExtra("forumTopic", topic);
    intent.setData(Uri.parse("meridian://forum-alert/" + key));
    PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification = new Notification.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(label)
        .setContentIntent(pending)
        .setCategory(Notification.CATEGORY_SOCIAL)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setAutoCancel(true).build();
    try { manager.notify(key, key.hashCode(), notification); }
    catch (SecurityException ignored) {}
  }
}
