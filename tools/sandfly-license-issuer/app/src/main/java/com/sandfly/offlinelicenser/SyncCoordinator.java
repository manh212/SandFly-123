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
      CatalogPublisher publisher = new CatalogPublisher(CatalogPublisher.github(GitHubConnection.token(store)));
      String publicKey = IssuerKeys.publicKey();
      publisher.register(publicKey);
      JSONObject records = store.records();
      Iterator<String> ids = records.keys();
      while (ids.hasNext()) {
        if (stopped.getAsBoolean()) return true;
        String id = ids.next();
        JSONObject entry = records.getJSONObject(id);
        if (entry.optBoolean("synced")) continue;
        publisher.publish(entry.getString("request"), entry.getString("value"), publicKey);
        store.synced(id, entry.getString("value"));
      }
      store.status("Đã đồng bộ GitHub. Lần thiết lập đầu tiên cần chờ bản Meridian mới được build xong.");
      return false;
    } catch (Exception e) {
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
