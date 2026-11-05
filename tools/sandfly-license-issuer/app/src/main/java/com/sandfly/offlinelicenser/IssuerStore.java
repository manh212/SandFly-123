package com.sandfly.offlinelicenser;

import android.content.Context;
import io.meridian.licensing.RecoveryProtocol;
import org.json.JSONObject;

final class IssuerStore {
  private static final Object RECORDS_LOCK = new Object();
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
    synchronized (RECORDS_LOCK) {
      JSONObject records = records();
      JSONObject previous = records.optJSONObject(RecoveryProtocol.deviceId(request));
      if (previous != null && previous.optString("value").startsWith("SFLIC3.")) {
        java.security.PublicKey key = RecoveryProtocol.publicKey(IssuerKeys.publicKey());
        io.meridian.licensing.LicensePolicy old = io.meridian.licensing.LicensePolicy.verify(request, previous.getString("value"), key);
        if (!license.startsWith("SFLIC3.") || io.meridian.licensing.LicensePolicy.verify(request, license, key).revision <= old.revision) {
          throw new IllegalStateException("Thiết bị vừa được thay đổi. Mở lại chi tiết rồi ký lại.");
        }
      }
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
  }

  void synced(String id, String license) throws Exception {
    synchronized (RECORDS_LOCK) {
      JSONObject records = records();
      JSONObject entry = records.getJSONObject(id);
      if (!entry.getString("value").equals(license)) return;
      entry.put("synced", true);
      secrets.write("records", records.toString());
    }
  }

  void privateSynced(String id, String license) throws Exception {
    synchronized (RECORDS_LOCK) {
      JSONObject records = records();
      JSONObject entry = records.getJSONObject(id);
      if (!entry.getString("value").equals(license)) return;
      entry.put("privateSynced", true);
      entry.put("eventVersion", 1);
      secrets.write("records", records.toString());
    }
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
    synchronized (RECORDS_LOCK) {
      JSONObject records = records();
      if (merge(records, entry, privateSynced, IssuerKeys.publicKey())) secrets.write("records", records.toString());
    }
  }

  JSONObject importRecords(JSONObject incoming) throws Exception {
    synchronized (RECORDS_LOCK) {
      JSONObject records = records();
      String key = IssuerKeys.publicKey();
      boolean changed = false;
      java.util.Iterator<String> ids = incoming.keys();
      while (ids.hasNext()) {
        String id = ids.next();
        JSONObject entry = incoming.getJSONObject(id);
        if (!id.equals(RecoveryProtocol.deviceId(entry.getString("request")))) {
          throw new IllegalArgumentException("Danh sách chứa định danh thiết bị không khớp.");
        }
        changed |= merge(records, entry, true, key);
      }
      if (changed) secrets.write("records", records.toString());
      JSONObject visible = new JSONObject();
      java.util.Iterator<String> keys = records.keys();
      while (keys.hasNext()) {
        String id = keys.next();
        JSONObject entry = records.getJSONObject(id);
        if (incoming.has(id) || !entry.optBoolean("synced") || !entry.optBoolean("privateSynced")) visible.put(id, entry);
      }
      return visible;
    }
  }

  void privateSynced(JSONObject uploaded, boolean eventsPublished) throws Exception {
    synchronized (RECORDS_LOCK) {
      JSONObject records = records();
      java.util.Iterator<String> ids = uploaded.keys();
      while (ids.hasNext()) {
        String id = ids.next();
        JSONObject entry = records.optJSONObject(id);
        if (entry != null && entry.optString("value").equals(uploaded.getJSONObject(id).optString("value"))) {
          entry.put("privateSynced", true).put("eventVersion", eventsPublished ? 1 : 0);
        }
      }
      secrets.write("records", records.toString());
    }
  }

  private static boolean merge(JSONObject records, JSONObject entry, boolean privateSynced, String publicKey) throws Exception {
    String request = entry.getString("request");
    String value = entry.getString("value");
    if (value.startsWith("SFLIC3.")) {
      io.meridian.licensing.LicensePolicy.verify(request, value, RecoveryProtocol.publicKey(publicKey));
    } else if (!RecoveryProtocol.verify(request, value, RecoveryProtocol.publicKey(publicKey))) {
      throw new IllegalArgumentException("Giấy phép không do khóa quản trị này cấp.");
    }
    String id = RecoveryProtocol.deviceId(request);
    JSONObject old = records.optJSONObject(id);
    if (old != null) {
      String previous = old.optString("value");
      if (previous.equals(value)) {
        if (!privateSynced) return false;
        if (old.optBoolean("synced") && old.optBoolean("privateSynced")
            && old.optString("name").equals(entry.optString("name"))
            && old.optString("description").equals(entry.optString("description"))
            && old.optString("uid").equals(entry.optString("uid"))) return false;
        JSONObject refreshed = new JSONObject(old.toString());
        refreshed.put("name", entry.optString("name")).put("description", entry.optString("description"))
            .put("uid", entry.optString("uid")).put("synced", true).put("privateSynced", true);
        records.put(id, refreshed);
        return true;
      }
      if (previous.startsWith("SFLIC3.")) {
        io.meridian.licensing.LicensePolicy before = io.meridian.licensing.LicensePolicy.verify(
            request, previous, RecoveryProtocol.publicKey(publicKey));
        if (!value.startsWith("SFLIC3.") || io.meridian.licensing.LicensePolicy.verify(
            request, value, RecoveryProtocol.publicKey(publicKey)).revision <= before.revision) return false;
      }
      if (!old.optBoolean("synced") && !value.startsWith("SFLIC3.")) return false;
    }
    entry.put("synced", true).put("privateSynced", privateSynced);
    records.put(id, entry);
    return true;
  }
}
