package com.sandfly.offlinelicenser;

import android.content.Context;
import io.meridian.licensing.FirebaseRest;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class EnrollmentService {
  private EnrollmentService() {}
  static FirebaseRest create(Context context) throws Exception {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.InputStream input = context.getAssets().open("meridian-service.json")) {
      byte[] buffer = new byte[1024];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
    }
    IssuerStore store = new IssuerStore(context);
    FirebaseRest.Config config = new FirebaseRest.Config(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    if (!config.enabled()) throw new IllegalStateException("Chưa cấu hình dịch vụ đăng ký Firebase.");
    return new FirebaseRest(config,
        new FirebaseRest.Storage() {
          @Override public String read() throws Exception { return store.firebase(); }
          @Override public void write(String value) throws Exception { store.firebase(value); }
        });
  }

  static void publish(Context context, String id, JSONObject entry) throws Exception {
    FirebaseRest service = create(context);
    JSONObject body = new JSONObject().put("request", entry.getString("request"))
        .put("name", entry.optString("name")).put("description", entry.optString("description"))
        .put("uid", entry.optString("uid")).put("value", entry.getString("value"));
    service.put("devices/" + id, body);
    String uid = entry.optString("uid");
    if (!uid.isEmpty()) {
      JSONObject decision = new JSONObject().put("request", entry.getString("request"))
          .put("state", "approved").put("policy", entry.getString("value"))
          .put("decidedAt", FirebaseRest.timestamp());
      service.put("decisions/" + uid, decision);
    }
  }
}
