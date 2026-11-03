package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import io.meridian.licensing.FirebaseRest;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class Enrollment {
  interface Callback { void completed(String message, boolean submitted, String name, String description); }
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
  private static final String ALIAS = "meridian.enrollment.identity.v1";
  private Enrollment() {}

  static FirebaseRest service(Context context) throws Exception {
    String json;
    try (java.io.InputStream stream = context.getAssets().open("meridian-service.json")) {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int count;
      while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
      json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    return new FirebaseRest(new FirebaseRest.Config(json), new FirebaseRest.Storage() {
      private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
          KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
          generator.init(new KeyGenParameterSpec.Builder(ALIAS,
              KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
          generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
      }
      @Override public String read() throws Exception {
        String value = context.getSharedPreferences("meridian_enrollment", 0).getString("identity", "");
        if (value.isEmpty()) return "{}";
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) throw new IllegalStateException("Không đọc được danh tính đăng ký.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
      }
      @Override public void write(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
            + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences("meridian_enrollment", 0).edit().putString("identity", encrypted).commit()) {
          throw new IllegalStateException("Không lưu được danh tính đăng ký.");
        }
      }
    });
  }

  static void check(Context context, String name, String description, Callback callback) {
    Context app = context.getApplicationContext();
    WORKER.execute(() -> {
      String message;
      boolean submitted = false;
      String savedName = "", savedDescription = "";
      try {
        if (!ApkSignatureVerifier.isOfficial(app)) throw new IllegalStateException("Bản cài đặt chưa được xác thực.");
        FirebaseRest service = service(app);
        String uid = service.uid();
        String request = DeviceLicenseManager.recoveryRequest(app);
        JSONObject existing = service.get("requests/" + uid);
        if (existing.length() == 0 && name != null) {
          String fullName = name.trim();
          String details = description == null ? "" : description.trim();
          if (fullName.isEmpty() || fullName.length() > 80 || details.isEmpty() || details.length() > 1000
              || fullName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Nhập họ tên từ 1–80 ký tự và mô tả từ 1–1000 ký tự.");
          }
          JSONObject body = new JSONObject().put("request", request).put("name", fullName)
              .put("description", details).put("createdAt", FirebaseRest.timestamp());
          service.put("requests/" + uid, body);
          existing = body;
        }
        submitted = existing.length() > 0;
        savedName = existing.optString("name"); savedDescription = existing.optString("description");
        if (submitted && !request.equals(existing.optString("request"))) {
          throw new IllegalStateException("Yêu cầu đã lưu thuộc thiết bị khác.");
        }
        JSONObject decision = service.get("decisions/" + uid);
        if (decision.length() == 0) {
          message = submitted ? "Đã gửi yêu cầu. Đang chờ quản trị viên duyệt."
              : "Nhập họ tên và mô tả để đăng ký.";
        } else if (!request.equals(decision.optString("request"))) {
          throw new IllegalStateException("Phản hồi đăng ký không khớp thiết bị.");
        } else if (decision.optString("state").equals("approved")) {
          DeviceLicenseManager.acceptRemote(app, decision.getString("policy"), 0);
          message = DeviceLicenseManager.status(app);
        } else {
          message = "Yêu cầu chưa được chấp thuận. Liên hệ quản trị viên để biết thêm.";
        }
      } catch (Exception e) {
        message = e instanceof IllegalArgumentException || e instanceof IllegalStateException
            ? e.getMessage() : "Chưa gửi hoặc kiểm tra được yêu cầu. Kết nối Internet rồi thử lại.";
      }
      String response = message;
      boolean locked = submitted;
      String displayName = savedName, displayDescription = savedDescription;
      MAIN.post(() -> { ActivationReminder.onPolicyChanged(); callback.completed(response, locked, displayName, displayDescription); });
    });
  }
}
