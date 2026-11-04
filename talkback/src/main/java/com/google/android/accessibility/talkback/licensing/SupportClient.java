package com.google.android.accessibility.talkback.licensing;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.os.UserManagerCompat;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.SupportProtocol;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

public final class SupportClient {
  interface EnqueueCallback { void completed(boolean saved, String message); }
  static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  static final Handler MAIN = new Handler(Looper.getMainLooper());
  static final ExecutorService LOCAL = Executors.newSingleThreadExecutor();
  private static final Set<String> SENDING = new HashSet<>();
  private static final int JOB = 0x535550;
  private static long lastOpen = -60_000L;
  private SupportClient() {}

  public static void opened(Context context) {
    Context app = context.getApplicationContext();
    if (!UserManagerCompat.isUserUnlocked(app) || !ApkSignatureVerifier.isOfficial(app)) return;
    synchronized (SupportClient.class) {
      long now = SystemClock.elapsedRealtime();
      if (now - lastOpen < 60_000L) return;
      lastOpen = now;
    }
    WORKER.execute(() -> {
      try { touch(app); } catch (Exception ignored) {}
      try { if (new SupportStore(app).queue().length() > 0) schedule(app); } catch (Exception ignored) {}
    });
  }

  static void touch(Context context) throws Exception {
    FirebaseRest service = Enrollment.service(context);
    String uid = service.uid();
    service.put("supportContacts/" + uid, new JSONObject()
        .put("request", DeviceLicenseManager.recoveryRequest(context)).put("lastSeen", FirebaseRest.timestamp()));
  }

  static void submittedRegistration(Context context, String name) {
    Context app = context.getApplicationContext();
    WORKER.execute(() -> {
      try { new SupportStore(app).name(name); touch(app); } catch (Exception ignored) {}
    });
  }

  static void enqueue(Context context, JSONObject draft, EnqueueCallback callback) {
    Context app = context.getApplicationContext();
    LOCAL.execute(() -> {
      try {
        if (!ApkSignatureVerifier.isOfficial(app)) throw new IllegalStateException("Bản cài đặt chưa được xác thực.");
        new SupportStore(app).add(draft);
        schedule(app);
        MAIN.post(() -> callback.completed(true, "Đã lưu vào hàng chờ. Nội dung sẽ tự gửi khi có mạng."));
        WORKER.execute(() -> drain(app, () -> false));
      } catch (Exception e) { MAIN.post(() -> callback.completed(false, error(e))); }
    });
  }

  static void cancel(Context context, String id, Consumer<String> callback) {
    Context app = context.getApplicationContext();
    LOCAL.execute(() -> {
      String message;
      try {
        synchronized (SENDING) {
          if (SENDING.contains(id)) message = "Nội dung đang gửi, chưa thể hủy.";
          else message = new SupportStore(app).remove(id) ? "Đã hủy nội dung đang chờ gửi." : "Nội dung không còn trong hàng chờ.";
        }
      } catch (Exception e) { message = error(e); }
      String result = message; MAIN.post(() -> callback.accept(result));
    });
  }

  static boolean sending(String id) { synchronized (SENDING) { return SENDING.contains(id); } }

  static void schedule(Context context) {
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler != null && scheduler.getPendingJob(JOB) == null) {
      scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, SupportJobService.class))
          .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
          .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
  }

  static boolean drain(Context context, BooleanSupplier stopped) {
    if (!UserManagerCompat.isUserUnlocked(context) || !ApkSignatureVerifier.isOfficial(context)) return false;
    try {
      SupportStore store = new SupportStore(context);
      JSONObject queue = store.queue();
      for (String id : SupportProtocol.keys(queue)) {
        if (stopped.getAsBoolean()) return true;
        JSONObject draft;
        synchronized (SENDING) {
          draft = store.queue().optJSONObject(id);
          if (draft == null) continue;
          SENDING.add(id);
        }
        try {
          SupportProtocol.publishUser(Enrollment.service(context), draft);
          store.remove(id);
        } catch (Exception e) {
          store.failed(id, error(e));
          return true;
        } finally { synchronized (SENDING) { SENDING.remove(id); } }
      }
      return false;
    } catch (Exception e) { return true; }
  }

  static String error(Exception e) {
    return e instanceof IllegalArgumentException || e instanceof IllegalStateException
        ? e.getMessage() : "Chưa kết nối được dịch vụ. Hãy kiểm tra Internet và thử lại.";
  }
}
