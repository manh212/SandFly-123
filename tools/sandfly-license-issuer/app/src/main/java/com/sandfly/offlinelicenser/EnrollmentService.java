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
          @Override public String sessionKey() { return "meridian-admin"; }
          @Override public String read() throws Exception { return store.firebase(); }
          @Override public void write(String value) throws Exception { store.firebase(value); }
        });
  }

  static void publish(Context context, String id, JSONObject entry) throws Exception {
    publish(create(context), new JSONObject().put(id, entry));
  }

  static boolean publish(FirebaseRest service, JSONObject entries) throws Exception {
    JSONObject changes = new JSONObject();
    java.util.Iterator<String> ids = entries.keys();
    while (ids.hasNext()) {
      String id = ids.next();
      JSONObject entry = entries.getJSONObject(id);
      if (!id.equals(io.meridian.licensing.RecoveryProtocol.deviceId(entry.getString("request")))) {
        throw new IllegalArgumentException("Định danh giấy phép không khớp.");
      }
      JSONObject body = new JSONObject().put("request", entry.getString("request"))
          .put("name", entry.optString("name")).put("description", entry.optString("description"))
          .put("uid", entry.optString("uid")).put("value", entry.getString("value"));
      changes.put("devices/" + id, body);
      changes.put("licenseEvents/" + id, new JSONObject().put("request", entry.getString("request"))
          .put("value", entry.getString("value")).put("updatedAt", FirebaseRest.timestamp()));
      String uid = entry.optString("uid");
      if (!uid.isEmpty()) {
        JSONObject decision = new JSONObject().put("request", entry.getString("request"))
            .put("state", "approved").put("policy", entry.getString("value"))
            .put("decidedAt", FirebaseRest.timestamp());
        changes.put("decisions/" + uid, decision);
      }
    }
    if (changes.length() == 0) return true;
    try {
      service.update(changes);
      return true;
    } catch (FirebaseRest.PermissionDenied denied) {
      java.util.Iterator<String> paths = new JSONObject(changes.toString()).keys();
      while (paths.hasNext()) {
        String path = paths.next();
        if (path.startsWith("licenseEvents/")) changes.remove(path);
      }
      service.update(changes);
      return false;
    }
  }
}
