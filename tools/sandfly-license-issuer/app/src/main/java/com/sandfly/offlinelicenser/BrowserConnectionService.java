package com.sandfly.offlinelicenser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class BrowserConnectionService extends Service {
  private static final String CHANNEL = "meridian_github_setup";
  static final String CANCEL = "meridian.github.CANCEL";

  @Override public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Đăng nhập GitHub", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0,
        new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent cancel = PendingIntent.getService(this, 1,
        new Intent(this, BrowserConnectionService.class).setAction(CANCEL),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification = new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Đang đăng nhập GitHub")
        .setContentText("Đang giữ kết nối để nhận kết quả đăng nhập.")
        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "Hủy đăng nhập", cancel).build()).build();
    if (Build.VERSION.SDK_INT >= 29) startForeground(0x4d4553, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(0x4d4553, notification);
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && CANCEL.equals(intent.getAction())) {
      SyncCoordinator.WORKER.execute(() -> {
        try { BrowserConnection.cancel(getApplicationContext()); }
        catch (Exception ignored) { stopSelf(startId); }
      });
      return START_NOT_STICKY;
    }
    SyncCoordinator.WORKER.execute(() -> {
      try {
        if (!OfficialInstall.verified(this) || !BrowserConnection.restore(getApplicationContext())) stopSelf(startId);
      } catch (Exception e) {
        try { new IssuerStore(this).status("Không khôi phục được phiên nhận mã GitHub. Hãy đăng nhập lại."); }
        catch (Exception ignored) {}
        stopSelf(startId);
      }
    });
    return START_STICKY;
  }

  @Override public void onTimeout(int startId, int foregroundServiceType) {
    SyncCoordinator.WORKER.execute(() -> {
      try { BrowserConnection.cancel(getApplicationContext()); }
      catch (Exception ignored) {}
    });
    stopSelf(startId);
  }

  @Override public IBinder onBind(Intent intent) { return null; }
}
