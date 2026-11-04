package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SupportNotificationsTest {
  @Test public void notificationOpensTheExactTicketAndRetriesDoNotDuplicateIt() {
    Context context = RuntimeEnvironment.getApplication();
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    manager.cancelAll();
    String ticket = "1700000000000-" + "a".repeat(20);
    SupportNotifications.notifyUpdate(context, ticket, "Yêu cầu", "Trạng thái mới: Đã hoàn tất", "status-1");
    SupportNotifications.notifyUpdate(context, ticket, "Yêu cầu", "Trạng thái mới: Đã hoàn tất", "status-1");
    assertEquals(1, manager.getActiveNotifications().length);
    Notification notification = manager.getActiveNotifications()[0].getNotification();
    assertEquals("Yêu cầu", notification.extras.getString(Notification.EXTRA_TITLE));
    assertEquals("Trạng thái mới: Đã hoàn tất", notification.extras.getString(Notification.EXTRA_TEXT));
    Intent open = shadowOf(notification.contentIntent).getSavedIntent();
    assertEquals(ticket, open.getStringExtra(SupportNotifications.TICKET));
    assertEquals(SupportActivity.class.getName(), open.getComponent().getClassName());
    assertEquals(NotificationManager.IMPORTANCE_HIGH,
        manager.getNotificationChannel(notification.getChannelId()).getImportance());
    assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility);
    SupportNotifications.notifyUpdate(context, ticket, "Yêu cầu", "Cần thêm thông tin", "status-2");
    assertEquals(2, manager.getActiveNotifications().length);
    manager.cancelAll();
  }
}
