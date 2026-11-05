package com.sandfly.offlinelicenser;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import io.meridian.licensing.CatalogPublisher;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

final class SyncCoordinator {
  static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  static final ExecutorService SIGNER = Executors.newSingleThreadExecutor();
  static final ExecutorService READS = Executors.newFixedThreadPool(3);
  static final ExecutorService NETWORK = Executors.newFixedThreadPool(4);
  private static final int JOB = 0x4d4552;
  private SyncCoordinator() {}

  static void schedule(Context context) {
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler != null && scheduler.getPendingJob(JOB) == null) {
      scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, SyncJobService.class))
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
          .setPersisted(true)
          .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
          .build());
    }
  }

  static boolean sync(Context context, BooleanSupplier stopped) {
    IssuerStore store = new IssuerStore(context);
    try {
      if (!OfficialInstall.verified(context) || !IssuerKeys.exists() || !store.connected()) return false;
      JSONObject records = store.records();
      boolean githubPending = false, firebasePending = false;
      for (String id : io.meridian.licensing.SupportProtocol.keys(records)) {
        JSONObject entry = records.getJSONObject(id);
        githubPending |= !entry.optBoolean("synced");
        firebasePending |= !entry.optBoolean("privateSynced") || entry.optInt("eventVersion") < 1;
      }
      if (!githubPending && !firebasePending) return false;
      CatalogPublisher publisher = githubPending
          ? new CatalogPublisher(CatalogPublisher.github(GitHubConnection.token(store))) : null;
      String publicKey = IssuerKeys.publicKey();
      Iterator<String> ids = records.keys();
      while (ids.hasNext()) {
        if (stopped.getAsBoolean()) return true;
        String id = ids.next();
        JSONObject entry = records.getJSONObject(id);
        if (!entry.optBoolean("synced")) {
          publisher.publish(entry.getString("request"), entry.getString("value"), publicKey);
          store.synced(id, entry.getString("value"));
        }
      }
      records = store.records();
      ids = records.keys();
      io.meridian.licensing.FirebaseRest service = null;
      JSONObject batch = new JSONObject();
      boolean eventsAvailable = true;
      while (ids.hasNext()) {
        if (stopped.getAsBoolean()) return true;
        String id = ids.next();
        JSONObject entry = records.getJSONObject(id);
        if (entry.optBoolean("synced") && (!entry.optBoolean("privateSynced") || entry.optInt("eventVersion") < 1)) {
          batch.put(id, entry);
        }
        if (batch.length() >= 25 || (!ids.hasNext() && batch.length() != 0)) {
          try {
            if (service == null) service = EnrollmentService.create(context);
            boolean eventsPublished = EnrollmentService.publish(service, batch);
            store.privateSynced(batch, eventsPublished);
            eventsAvailable &= eventsPublished;
            batch = new JSONObject();
          } catch (Exception e) {
            store.status("Đã lưu GitHub. Chưa lưu thông tin riêng hoặc gửi kết quả duyệt qua Firebase. "
                + (e instanceof IllegalStateException ? e.getMessage() : "Sẽ tự thử lại khi có mạng."));
            return e instanceof IOException;
          }
        }
      }
      JSONObject remaining = store.records();
      for (String id : io.meridian.licensing.SupportProtocol.keys(remaining)) {
        JSONObject entry = remaining.getJSONObject(id);
        if (!entry.optBoolean("synced") || !entry.optBoolean("privateSynced")
            || (eventsAvailable && entry.optInt("eventVersion") < 1)) {
          store.status("Đã đồng bộ đợt hiện tại. Còn thay đổi mới đang chờ gửi.");
          return true;
        }
      }
      store.status(eventsAvailable ? "Đã đồng bộ giấy phép lên GitHub và thông tin riêng lên Firebase."
          : "Đã lưu giấy phép và kết quả duyệt. Cần cập nhật quy tắc Firebase để nhận thay đổi giấy phép ngay.");
      return false;
    } catch (Exception e) {
      GitHubConnection.invalidate();
      boolean retry = e instanceof IOException
          && (!(e instanceof CatalogPublisher.SyncException)
              || ((CatalogPublisher.SyncException) e).retryable);
      String message = e instanceof CatalogPublisher.SyncException || e instanceof IllegalStateException
          ? e.getMessage() : "Chưa đồng bộ được GitHub. Dữ liệu vẫn được giữ trên máy.";
      try { store.status(message); } catch (Exception ignored) {}
      return retry;
    }
  }
}
