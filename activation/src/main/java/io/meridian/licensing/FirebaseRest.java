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
  private static final java.util.Map<String, Session> SESSIONS = new java.util.HashMap<>();
  private static final java.util.Map<Storage, java.util.Map<String, Session>> LOCAL_SESSIONS = new java.util.WeakHashMap<>();
  private static final class Session {
    JSONObject identity;
    String token;
    long expiresAt;
  }
  public interface Transport {
    String request(String address, String method, String body, String contentType) throws Exception;
    default void cancel() {}
    default long serverTimeMillis() { return 0; }
  }
  public interface Storage {
    String read() throws Exception;
    void write(String value) throws Exception;
    default String sessionKey() { return ""; }
  }

  public static final class Config {
    final String apiKey, database;
    public Config(String json) throws Exception {
      JSONObject value = new JSONObject(json);
      if (value.has("payload")) {
        value = new JSONObject(EndpointCodec.decode(value.getString("payload"), value.getInt("seed")));
      }
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
  private volatile JSONObject identity;
  private volatile String token;
  private final Session session;

  public FirebaseRest(Config config, Storage storage) {
    this(config, storage, new HttpTransport());
  }

  public FirebaseRest(Config config, Storage storage, Transport transport) {
    this.config = config;
    this.storage = storage;
    this.transport = transport;
    String scope = storage.sessionKey();
    String configuration = config.database + "|" + config.apiKey;
    if (scope.isEmpty()) {
      synchronized (LOCAL_SESSIONS) {
        session = LOCAL_SESSIONS.computeIfAbsent(storage, ignored -> new java.util.HashMap<>())
            .computeIfAbsent(configuration, ignored -> new Session());
      }
    } else {
      synchronized (SESSIONS) {
        String key = configuration + "|" + scope;
        session = SESSIONS.computeIfAbsent(key, ignored -> new Session());
      }
    }
  }

  public String uid() throws Exception { authenticate(); return identity.getString("uid"); }
  public long serverTimeMillis() { return transport.serverTimeMillis(); }

  public void cancelRequests() { transport.cancel(); }

  private void authenticate() throws Exception {
    synchronized (session) { authenticateLocked(); }
  }

  private void authenticateLocked() throws Exception {
    long now = System.currentTimeMillis();
    if (session.token != null && now < session.expiresAt && now > session.expiresAt - 3_600_000L) {
      identity = session.identity;
      token = session.token;
      return;
    }
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
    session.identity = identity;
    session.token = token;
    long seconds = result.optLong("expiresIn", result.optLong("expires_in", 3600));
    session.expiresAt = now + Math.max(0, Math.min(3600, seconds) - 60) * 1000;
  }

  public void invalidateAuthentication() {
    invalidateAuthentication(token);
  }

  private void invalidateAuthentication(String rejectedToken) {
    synchronized (session) {
      if (rejectedToken == null || rejectedToken.equals(session.token)) {
        session.token = null;
        session.expiresAt = 0;
      }
    }
  }

  private String authorization() throws Exception {
    synchronized (session) { authenticateLocked(); return token; }
  }

  public static final class AuthenticationExpired extends IllegalStateException {
    public AuthenticationExpired() { super("Phiên Firebase cần được làm mới."); }
  }

  public static final class PermissionDenied extends IllegalStateException {
    public PermissionDenied() { super("Firebase từ chối truy cập. Kiểm tra UID quản trị và quy tắc cơ sở dữ liệu."); }
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

  public JSONObject activity(String path, int limit, Long before) throws Exception {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Trang dữ liệu không hợp lệ.");
    String query = "&orderBy=%22lastAt%22&limitToLast=" + limit;
    if (before != null) query += "&endAt=" + before;
    String raw = database(path, "GET", null, query);
    return raw.trim().equals("null") ? new JSONObject() : new JSONObject(raw);
  }

  public JSONObject activityAt(String path, long time) throws Exception {
    String raw = database(path, "GET", null, "&orderBy=%22lastAt%22&equalTo=" + time);
    return raw.trim().equals("null") ? new JSONObject() : new JSONObject(raw);
  }

  public JSONObject keyRange(String path, String first, String last, int limit) throws Exception {
    if (limit < 1 || limit > 1000 || !first.matches("[a-zA-Z0-9_-]{1,128}")
        || !last.matches("[a-zA-Z0-9_-]{1,128}") || first.compareTo(last) > 0) {
      throw new IllegalArgumentException("Khoảng dữ liệu không hợp lệ.");
    }
    String query = "&orderBy=%22%24key%22&startAt=" + URLEncoder.encode(JSONObject.quote(first), "UTF-8")
        + "&endAt=" + URLEncoder.encode(JSONObject.quote(last), "UTF-8") + "&limitToFirst=" + limit;
    String raw = database(path, "GET", null, query);
    return raw.trim().equals("null") ? new JSONObject() : new JSONObject(raw);
  }

  public void update(JSONObject changes) throws Exception {
    java.util.Iterator<String> keys = changes.keys();
    while (keys.hasNext()) validatePath(keys.next());
    databaseRequest("", "PATCH", changes.toString(), "&print=silent");
  }

  public void patch(String path, JSONObject value) throws Exception {
    database(path, "PATCH", value.toString(), "&print=silent");
  }

  public String eventsAddress(String path) throws Exception {
    return eventsAddress(path, true);
  }

  public String eventsAddress(String path, boolean recent) throws Exception {
    validatePath(path);
    return config.database + "/" + path + ".json?auth=" + URLEncoder.encode(authorization(), "UTF-8")
        + (recent ? "&orderBy=%22lastAt%22&limitToLast=100" : "");
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

  public void putBoolean(String path, boolean value) throws Exception {
    database(path, "PUT", Boolean.toString(value), "&print=silent");
  }

  public JSONObject shallowKeys(String path) throws Exception {
    String raw = database(path, "GET", null, "&shallow=true");
    return raw.trim().equals("null") ? new JSONObject() : new JSONObject(raw);
  }

  public void deleteCleanupData(String path) throws Exception {
    if (!path.matches("(?:supportStaff|supportIndex|supportUsers|supportMessages|supportStates|supportContacts|supportAttachments|supportLogChunks|forumCategories|forumTopics|forumPosts|forumProfiles|forumNotices|forumBans|forumModerators)(?:/[A-Za-z0-9_-]{1,128}){0,2}|requests/[A-Za-z0-9_-]{1,128}")) {
      throw new IllegalArgumentException("Không được xóa dữ liệu giấy phép hoặc quyền quản trị.");
    }
    database(path, "DELETE", null, "&print=silent");
  }

  private String database(String path, String method, String body) throws Exception {
    return database(path, method, body, "");
  }

  private String database(String path, String method, String body, String query) throws Exception {
    validatePath(path);
    return databaseRequest(path, method, body, query);
  }

  private String databaseRequest(String path, String method, String body, String query) throws Exception {
    for (int attempt = 0; ; attempt++) {
      String authorization = authorization();
      try {
        return transport.request(config.database + "/" + path + ".json?auth=" + URLEncoder.encode(authorization, "UTF-8") + query,
            method, body, "application/json");
      } catch (AuthenticationExpired expired) {
        if (attempt != 0) throw expired;
        invalidateAuthentication(authorization);
      }
    }
  }

  private static void validatePath(String path) {
    if (!path.matches("(?:(?:requests|decisions|devices|admins|licenseEvents|supportIndex|supportUsers|supportMessages|supportStates|supportStaff|supportContacts|supportAttachments|forumCategories|forumTopics|forumPosts|forumProfiles|forumNotices|forumBans|forumModerators)(?:/[a-zA-Z0-9_-]{1,128}){0,2}|supportLogChunks(?:/[a-zA-Z0-9_-]{1,128}){0,3})")) {
      throw new IllegalArgumentException("Đường dẫn dịch vụ không hợp lệ.");
    }
  }

  public static JSONObject timestamp() throws Exception { return new JSONObject().put(".sv", "timestamp"); }

  private static final class HttpTransport implements Transport {
    private final java.util.Set<HttpURLConnection> connections = new java.util.HashSet<>();
    private boolean cancelled;
    private volatile long serverDate;

    @Override public long serverTimeMillis() { return serverDate; }

    @Override public String request(String address, String method, String body, String contentType) throws Exception {
      HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
      synchronized (connections) {
        if (cancelled) throw new java.io.InterruptedIOException("Đã hủy yêu cầu tải nhật ký.");
        connections.add(connection);
      }
      try {
        String response = http(connection, address, method, body, contentType);
        serverDate = connection.getDate();
        return response;
      } finally {
        synchronized (connections) { connections.remove(connection); }
        connection.disconnect();
      }
    }

    @Override public void cancel() {
      java.util.List<HttpURLConnection> active;
      synchronized (connections) {
        cancelled = true;
        active = new java.util.ArrayList<>(connections);
      }
      for (HttpURLConnection connection : active) {
        try { connection.disconnect(); } catch (RuntimeException ignored) {}
      }
    }
  }

  private static String http(HttpURLConnection connection, String address, String method, String body, String contentType) throws Exception {
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
        if (status == 401) {
          try (InputStream error = connection.getErrorStream()) {
            if (error != null) {
              ByteArrayOutputStream detail = new ByteArrayOutputStream();
              byte[] buffer = new byte[1024];
              int count;
              while (detail.size() < 8192 && (count = error.read(buffer)) != -1) detail.write(buffer, 0, count);
              if (detail.toString("UTF-8").toLowerCase(java.util.Locale.ROOT).contains("permission denied")) {
                throw new PermissionDenied();
              }
            }
          }
          throw new AuthenticationExpired();
        }
        if (status == 403) throw new PermissionDenied();
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
