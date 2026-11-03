package com.sandfly.offlinelicenser;

import android.content.Context;
import io.meridian.licensing.RecoveryProtocol;
import org.json.JSONObject;

final class IssuerStore {
  private final IssuerSecrets secrets;
  IssuerStore(Context context) { secrets = new IssuerSecrets(context); }
  String token() throws Exception { return secrets.read("github", ""); }
  void token(String token) throws Exception {
    if (token == null || !token.matches("github_pat_[A-Za-z0-9_]{40,240}")) {
      throw new IllegalArgumentException("Hãy nhập fine-grained token GitHub hợp lệ.");
    }
    secrets.write("github", token);
  }
  JSONObject records() throws Exception { return new JSONObject(secrets.read("records", "{}")); }
  String status() throws Exception { return secrets.read("status", "Chưa kết nối GitHub."); }
  void status(String value) throws Exception { secrets.write("status", value); }

  void record(String request, String name, String license) throws Exception {
    JSONObject records = records();
    JSONObject entry = new JSONObject();
    entry.put("request", request);
    entry.put("name", name);
    entry.put("value", license);
    entry.put("synced", false);
    records.put(RecoveryProtocol.deviceId(request), entry);
    secrets.write("records", records.toString());
  }

  void synced(String id, String license) throws Exception {
    JSONObject records = records();
    JSONObject entry = records.getJSONObject(id);
    if (!entry.getString("value").equals(license)) return;
    entry.put("synced", true);
    secrets.write("records", records.toString());
  }
}
