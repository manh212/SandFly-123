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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ForumNotifications {
  private static final String CHANNEL = "meridian_forum_updates";
  private static final String STORE = "meridian_forum_notifications";

  private ForumNotifications() {}

  static void poll(Context context) throws Exception {
    FirebaseRest service = Enrollment.service(context);
    String uid = service.uid();
    JSONObject notices = service.activity("forumNotices/" + uid, 40, null);
    SharedPreferences store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    long latest = store.getLong("latest:" + uid, -1);
    String oldIds = store.getString("ids:" + uid, "[]");
    List<String> already = keys(oldIds);
    List<String> fresh = new ArrayList<>();
    long highest = latest;
    for (java.util.Iterator<String> iterator = notices.keys(); iterator.hasNext();) {
      String id = iterator.next();
      JSONObject item = notices.optJSONObject(id);
      if (item == null) continue;
      long at = item.optLong("lastAt");
      if (at > latest || (at == latest && !already.contains(id))) fresh.add(id);
      highest = Math.max(highest, at);
    }
    if (latest >= 0) {
      fresh.sort((first, second) -> Long.compare(
          notices.optJSONObject(first).optLong("lastAt"), notices.optJSONObject(second).optLong("lastAt")));
      int from = Math.max(0, fresh.size() - 3);
      for (int index = from; index < fresh.size(); index++) {
        String id = fresh.get(index);
        JSONObject item = notices.optJSONObject(id);
        if (item != null && ForumProtocol.validId(item.optString("topic"))) {
          show(context, item.optString("topic"), item.optString("kind"), id);
        }
      }
    }
    if (highest >= 0) save(context, uid, notices, highest);
  }

  static void remember(Context context, String uid, JSONObject notices) {
    long highest = -1;
    for (java.util.Iterator<String> iterator = notices.keys(); iterator.hasNext();) {
      JSONObject item = notices.optJSONObject(iterator.next());
      if (item != null) highest = Math.max(highest, item.optLong("lastAt"));
    }
    if (highest >= 0) save(context, uid, notices, highest);
  }

  private static List<String> keys(String json) {
    List<String> result = new ArrayList<>();
    try {
      JSONArray values = new JSONArray(json);
      for (int index = 0; index < values.length(); index++) result.add(values.getString(index));
    } catch (Exception ignored) {}
    return result;
  }

  private static void save(Context context, String uid, JSONObject entries, long highest) {
    SharedPreferences store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    long old = store.getLong("latest:" + uid, -1);
    if (old > highest) return;
    JSONArray same = new JSONArray();
    if (highest == old) {
      for (String id : keys(store.getString("ids:" + uid, "[]"))) same.put(id);
    }
    for (java.util.Iterator<String> iterator = entries.keys(); iterator.hasNext();) {
      String id = iterator.next();
      JSONObject item = entries.optJSONObject(id);
      if (item != null && item.optLong("lastAt") == highest && !keys(same.toString()).contains(id)) same.put(id);
    }
    store.edit().putLong("latest:" + uid, highest).putString("ids:" + uid, same.toString()).apply();
  }

  private static void show(Context context, String topic, String kind, String key) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return;
    manager.createNotificationChannel(new NotificationChannel(CHANNEL,
        "Thông báo diễn đàn", NotificationManager.IMPORTANCE_DEFAULT));
    Intent open = new Intent(context, ForumActivity.class).putExtra("forumTopic", topic)
        .setData(Uri.parse("meridian://forum/" + topic))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pending = PendingIntent.getActivity(context, key.hashCode(), open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String label = "mention".equals(kind) ? "Có người nhắc đến bạn trong diễn đàn" : "Có người trả lời trong diễn đàn";
    Notification notice = new Notification.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle("Diễn đàn Meridian").setContentText(label)
        .setContentIntent(pending).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build();
    try { manager.notify("forum:" + key, key.hashCode(), notice); }
    catch (SecurityException ignored) {}
  }
}
