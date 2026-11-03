package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.os.UserManagerCompat;
import io.meridian.licensing.RecoveryProtocol;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

final class LicenseRecovery {
  enum Result { RESTORED, ALREADY_LICENSED, NOT_FOUND, OFFLINE, INVALID, NOT_CONFIGURED }
  interface Callback { void completed(Result result); }
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static boolean running;
  private static long lastAttempt = -30_000L;
  private static Result lastResult = Result.NOT_FOUND;
  private static final List<Callback> WAITING = new ArrayList<>();

  private LicenseRecovery() {}

  static void activateManual(Context context, String value, java.util.function.Consumer<String> callback) {
    Context app = context.getApplicationContext();
    WORKER.execute(() -> {
      String message;
      try {
        Result result = recover(app);
        if (result == Result.RESTORED) {
          message = DeviceLicenseManager.status(app);
        } else if (result == Result.NOT_FOUND) {
          DeviceLicenseManager.activate(app, value);
          message = DeviceLicenseManager.status(app);
        } else {
          throw new IllegalStateException("Cần kết nối Internet để kiểm tra trạng thái khi nhập giấy phép lần đầu.");
        }
      } catch (Exception e) { message = e.getMessage(); }
      String response = message;
      MAIN.post(() -> { ActivationReminder.onPolicyChanged(); callback.accept(response); });
    });
  }

  static synchronized void start(Context context, boolean force, Callback callback) {
    Context app = context.getApplicationContext();
    if (!UserManagerCompat.isUserUnlocked(app) || !ApkSignatureVerifier.isOfficial(app)) return;
    if (!DeviceLicenseManager.recoveryConfigured()) {
      if (callback != null) callback.completed(Result.NOT_CONFIGURED);
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (running) {
      if (callback != null) WAITING.add(callback);
      return;
    }
    if (!force && now - lastAttempt < 30_000L) {
      if (callback != null) callback.completed(lastResult);
      return;
    }
    if (callback != null) WAITING.add(callback);
    running = true;
    lastAttempt = now;
    WORKER.execute(() -> {
      Result result = recover(app);
      MAIN.post(() -> {
        List<Callback> callbacks;
        synchronized (LicenseRecovery.class) {
          running = false;
          lastResult = result;
          callbacks = new ArrayList<>(WAITING);
          WAITING.clear();
        }
        ActivationReminder.onPolicyChanged();
        for (Callback waiting : callbacks) waiting.completed(result);
      });
    });
  }

  private static Result recover(Context context) {
    HttpURLConnection connection = null;
    try {
      String request = DeviceLicenseManager.recoveryRequest(context);
      URL url = new URL("https://raw.githubusercontent.com/manh212/SandFly-123/master/"
          + RecoveryProtocol.recordPath(request) + "?v=" + System.currentTimeMillis());
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(8_000);
      connection.setReadTimeout(8_000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestProperty("Cache-Control", "no-cache");
      int status = connection.getResponseCode();
      if (status == 404) return Result.NOT_FOUND;
      if (status != 200) return Result.OFFLINE;
      if (connection.getContentLengthLong() > 8192) return Result.INVALID;
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream input = connection.getInputStream()) {
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (bytes.size() + count > 8192) return Result.INVALID;
          bytes.write(buffer, 0, count);
        }
      }
      JSONObject record = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      if (record.optInt("format") != 2 && record.optInt("format") != 3) return Result.INVALID;
      String license = record.optString("value", "");
      DeviceLicenseManager.acceptRemote(context, license, connection.getDate());
      return Result.RESTORED;
    } catch (java.io.IOException e) {
      return Result.OFFLINE;
    } catch (Exception e) {
      return Result.INVALID;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }
}
