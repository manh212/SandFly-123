package com.sandfly.offlinelicenser;

import android.content.Context;
import io.meridian.licensing.RecoveryProtocol;
import org.json.JSONObject;

final class IssuerStore {
  private final IssuerSecrets secrets;
  IssuerStore(Context context) { secrets = new IssuerSecrets(context); }
  String legacyToken() throws Exception { return secrets.read("github", ""); }
  void clearLegacyToken() throws Exception { secrets.write("github", ""); }
  boolean connected() throws Exception { return connection().length() != 0 || !legacyToken().isEmpty(); }
  JSONObject connection() throws Exception { return new JSONObject(secrets.read("connection", "{}")); }
  void connection(JSONObject value) throws Exception { secrets.write("connection", value.toString()); }
  JSONObject browser() throws Exception { return new JSONObject(secrets.read("browser", "{}")); }
  void browser(JSONObject value) throws Exception { secrets.write("browser", value.toString()); }
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
