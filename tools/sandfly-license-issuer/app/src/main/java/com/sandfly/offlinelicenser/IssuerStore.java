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
    record(request, name, "", "", license);
  }

  void record(String request, String name, String description, String uid, String license) throws Exception {
    JSONObject records = records();
    JSONObject entry = new JSONObject();
    entry.put("request", request);
    entry.put("name", name);
    entry.put("description", description);
    entry.put("uid", uid);
    entry.put("value", license);
    entry.put("synced", false);
    entry.put("privateSynced", false);
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

  void privateSynced(String id, String license) throws Exception {
    JSONObject records = records();
    JSONObject entry = records.getJSONObject(id);
    if (!entry.getString("value").equals(license)) return;
    entry.put("privateSynced", true);
    secrets.write("records", records.toString());
  }

  JSONObject packages() throws Exception {
    JSONObject value = new JSONObject(secrets.read("packages", "{}"));
    if (value.length() == 0) value.put("full", new JSONObject().put("name", "Đầy đủ tính năng")
        .put("features", new org.json.JSONArray(io.meridian.licensing.LicensePolicy.FEATURES)));
    return value;
  }

  void packages(JSONObject value) throws Exception { secrets.write("packages", value.toString()); }
  String firebase() throws Exception { return secrets.read("firebase", "{}"); }
  void firebase(String value) throws Exception { secrets.write("firebase", value); }
  boolean ownerUiCached() throws Exception { return secrets.read("supportOwner", "false").equals("true"); }
  void ownerUiCached(boolean value) throws Exception { secrets.write("supportOwner", Boolean.toString(value)); }
  JSONObject supportDownload() throws Exception { return new JSONObject(secrets.read("supportDownload", "{}")); }
  void supportDownload(JSONObject value) throws Exception { secrets.write("supportDownload", value.toString()); }

  void importRecord(JSONObject entry) throws Exception {
    importRecord(entry, true);
  }

  void importRecord(JSONObject entry, boolean privateSynced) throws Exception {
    String request = entry.getString("request");
    String value = entry.getString("value");
    String publicKey = IssuerKeys.publicKey();
    if (value.startsWith("SFLIC3.")) {
      io.meridian.licensing.LicensePolicy.verify(request, value, RecoveryProtocol.publicKey(publicKey));
    } else if (!RecoveryProtocol.verify(request, value, RecoveryProtocol.publicKey(publicKey))) {
      throw new IllegalArgumentException("Giấy phép không do khóa quản trị này cấp.");
    }
    JSONObject records = records();
    String id = RecoveryProtocol.deviceId(request);
    JSONObject old = records.optJSONObject(id);
    if (old != null) {
      String previous = old.optString("value");
      if (previous.startsWith("SFLIC3.")) {
        io.meridian.licensing.LicensePolicy before = io.meridian.licensing.LicensePolicy.verify(
            request, previous, RecoveryProtocol.publicKey(publicKey));
        if (!value.startsWith("SFLIC3.") || io.meridian.licensing.LicensePolicy.verify(
            request, value, RecoveryProtocol.publicKey(publicKey)).revision <= before.revision) return;
      }
      if (!old.optBoolean("synced") && !value.startsWith("SFLIC3.")) return;
    }
    entry.put("synced", true).put("privateSynced", privateSynced);
    records.put(id, entry);
    secrets.write("records", records.toString());
  }
}
