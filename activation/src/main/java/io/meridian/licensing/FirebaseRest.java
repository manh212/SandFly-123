package io.meridian.licensing;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class FirebaseRest {
  private static final Object AUTH_LOCK = new Object();
  public interface Transport {
    String request(String address, String method, String body, String contentType) throws Exception;
  }
  public interface Storage {
    String read() throws Exception;
    void write(String value) throws Exception;
  }

  public static final class Config {
    final String apiKey, database;
    public Config(String json) throws Exception {
      JSONObject value = new JSONObject(json);
      apiKey = value.optString("apiKey");
      database = value.optString("databaseUrl").replaceAll("/+$", "");
      if (!apiKey.isEmpty() && (!apiKey.matches("[A-Za-z0-9_-]{20,100}")
          || !database.matches("https://[a-z0-9-]+(?:\\.[a-z0-9-]+)?\\.(?:firebaseio\\.com|firebasedatabase\\.app)"))) {
        throw new IllegalArgumentException("Cấu hình Firebase không hợp lệ.");
      }
    }
    public boolean enabled() { return !apiKey.isEmpty() && !database.isEmpty(); }
  }

  private final Config config;
  private final Storage storage;
  private final Transport transport;
  private JSONObject identity;
  private String token;

  public FirebaseRest(Config config, Storage storage) {
    this(config, storage, FirebaseRest::http);
  }

  public FirebaseRest(Config config, Storage storage, Transport transport) {
    this.config = config;
    this.storage = storage;
    this.transport = transport;
  }

  public String uid() throws Exception { authenticate(); return identity.getString("uid"); }

  private void authenticate() throws Exception {
    synchronized (AUTH_LOCK) { authenticateLocked(); }
  }

  private void authenticateLocked() throws Exception {
    if (token != null) return;
    if (!config.enabled()) throw new IllegalStateException("Chưa cấu hình dịch vụ đăng ký Firebase.");
    identity = new JSONObject(storage.read());
    JSONObject result;
    String nextToken;
    if (identity.length() == 0) {
      result = new JSONObject(transport.request("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key="
          + config.apiKey, "POST", "{\"returnSecureToken\":true}", "application/json"));
      identity.put("uid", result.getString("localId"));
      identity.put("refresh", result.getString("refreshToken"));
      nextToken = result.getString("idToken");
    } else {
      result = new JSONObject(transport.request("https://securetoken.googleapis.com/v1/token?key=" + config.apiKey,
          "POST", "grant_type=refresh_token&refresh_token="
              + URLEncoder.encode(identity.getString("refresh"), "UTF-8"),
          "application/x-www-form-urlencoded"));
      if (!identity.getString("uid").equals(result.getString("user_id"))) {
        throw new IllegalStateException("Danh tính Firebase không khớp.");
      }
      identity.put("refresh", result.getString("refresh_token"));
      nextToken = result.getString("id_token");
    }
    storage.write(identity.toString());
    token = nextToken;
  }

  public JSONObject get(String path) throws Exception {
    String raw = database(path, "GET", null);
    return raw.trim().equals("null") ? new JSONObject() : new JSONObject(raw);
  }

  public boolean flag(String path) throws Exception {
    return database(path, "GET", null).trim().equals("true");
  }

  public JSONObject recent(String path, String order, int limit, String before) throws Exception {
    if (!order.matches("\\$key|lastAt|request") || limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Bộ lọc dữ liệu không hợp lệ.");
    }
    String query = "&orderBy=" + URLEncoder.encode(JSONObject.quote(order), "UTF-8")
        + "&limitToLast=" + limit;
    if (before != null) query += "&endAt=" + URLEncoder.encode(JSONObject.quote(before), "UTF-8");
    String raw = database(path, "GET", null, query);
    return raw.trim().equals("null") ? new JSONObject() : new JSONObject(raw);
  }

  public JSONObject matching(String path, String field, String value) throws Exception {
    if (!field.equals("request")) throw new IllegalArgumentException("Bộ lọc dữ liệu không hợp lệ.");
    String raw = database(path, "GET", null, "&orderBy=" + URLEncoder.encode(JSONObject.quote(field), "UTF-8")
        + "&equalTo=" + URLEncoder.encode(JSONObject.quote(value), "UTF-8") + "&limitToLast=50");
    return raw.trim().equals("null") ? new JSONObject() : new JSONObject(raw);
  }

  public void put(String path, JSONObject value) throws Exception {
    database(path, "PUT", value.toString(), "&print=silent");
  }

  private String database(String path, String method, String body) throws Exception {
    return database(path, method, body, "");
  }

  private String database(String path, String method, String body, String query) throws Exception {
    if (!path.matches("(?:(?:requests|decisions|devices|admins|supportIndex|supportUsers|supportMessages|supportStates|supportStaff|supportContacts|supportAttachments)(?:/[a-zA-Z0-9_-]{1,128}){0,2}|supportLogChunks(?:/[a-zA-Z0-9_-]{1,128}){3})")) {
      throw new IllegalArgumentException("Đường dẫn dịch vụ không hợp lệ.");
    }
    authenticate();
    return transport.request(config.database + "/" + path + ".json?auth=" + URLEncoder.encode(token, "UTF-8") + query,
        method, body, "application/json");
  }

  public static JSONObject timestamp() throws Exception { return new JSONObject().put(".sv", "timestamp"); }

  private static String http(String address, String method, String body, String contentType) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
    try {
      connection.setConnectTimeout(8_000);
      connection.setReadTimeout(address.contains("/supportLogChunks/") ? 30_000 : 8_000);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestMethod(method);
      if (body != null) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType + "; charset=utf-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
      }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        if (status == 400) throw new IllegalStateException("Dịch vụ đăng ký từ chối yêu cầu. Kiểm tra cấu hình và danh tính Firebase.");
        if (status == 401 || status == 403) throw new IllegalStateException(
            "Firebase từ chối truy cập. Kiểm tra đăng nhập ẩn danh, UID quản trị và quy tắc cơ sở dữ liệu.");
        throw new IOException("Chưa kết nối được Firebase (HTTP " + status + ").");
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream input = connection.getInputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (bytes.size() + count > 2_097_152) throw new IOException("Dữ liệu Firebase quá lớn.");
          bytes.write(buffer, 0, count);
        }
      }
      return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    } finally { connection.disconnect(); }
  }
}
