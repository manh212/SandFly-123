package io.meridian.licensing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

public final class CatalogPublisher {
  public static final String PROFILE_PATH = "talkback/distribution-profile.json";
  public interface Client {
    RemoteFile get(String path) throws Exception;
    void put(String path, String content, String sha) throws Exception;
  }
  public static final class RemoteFile {
    public final String content;
    public final String sha;
    public RemoteFile(String content, String sha) { this.content = content; this.sha = sha; }
  }
  public static final class SyncException extends IOException {
    public final boolean retryable;
    public final boolean conflict;
    public SyncException(String message, boolean retryable, boolean conflict) {
      super(message);
      this.retryable = retryable;
      this.conflict = conflict;
    }
  }
  private final Client client;
  public CatalogPublisher(Client client) { this.client = client; }

  public void register(String publicKey) throws Exception {
    RecoveryProtocol.publicKey(publicKey);
    for (int attempt = 0; attempt < 3; attempt++) {
      RemoteFile existing = client.get(PROFILE_PATH);
      if (existing != null) {
        JSONObject profile = new JSONObject(existing.content);
        if (profile.optInt("format") != 2) throw invalidProfile();
        String registered = profile.optString("publicKey", "");
        if (registered.equals(publicKey)) return;
        if (!registered.isEmpty()) throw invalidProfile();
      }
      JSONObject profile = new JSONObject();
      profile.put("format", 2);
      profile.put("publicKey", publicKey);
      try {
        client.put(PROFILE_PATH, profile.toString(2) + "\n", existing == null ? null : existing.sha);
        return;
      } catch (SyncException e) { if (!e.conflict) throw e; }
    }
    throw new SyncException("GitHub đang có thay đổi khác. Giấy phép sẽ được gửi lại.", true, true);
  }

  public void publish(String request, String license, String publicKey) throws Exception {
    java.security.PublicKey issuer = RecoveryProtocol.publicKey(publicKey);
    LicensePolicy policy = license.startsWith("SFLIC3.")
        ? LicensePolicy.verify(request, license, issuer) : null;
    if (policy == null && !RecoveryProtocol.verify(request, license, issuer)) {
      throw new SyncException("Không tải lên giấy phép sai chữ ký hoặc sai thiết bị.", false, false);
    }
    register(publicKey);
    String path = RecoveryProtocol.recordPath(request);
    JSONObject record = new JSONObject();
    record.put("format", policy == null ? 2 : 3);
    record.put("value", license);
    for (int attempt = 0; attempt < 3; attempt++) {
      RemoteFile existing = client.get(path);
      if (existing != null) {
        JSONObject value = new JSONObject(existing.content);
        if (license.equals(value.optString("value"))) return;
        if (value.optInt("format") == 3) {
          LicensePolicy previous = LicensePolicy.verify(request, value.getString("value"), issuer);
          if (policy == null || policy.revision <= previous.revision) {
            throw new SyncException("Giấy phép trên GitHub mới hơn. Mở thiết bị, tải trạng thái mới rồi ký lại thay đổi.", false, false);
          }
        }
      }
      try {
        client.put(path, record.toString(2) + "\n", existing == null ? null : existing.sha);
        return;
      } catch (SyncException e) { if (!e.conflict) throw e; }
    }
    throw new SyncException("Chưa đồng bộ được thay đổi mới nhất. Giấy phép vẫn được giữ trên máy.", true, true);
  }

  private static SyncException invalidProfile() {
    return new SyncException("Khóa công khai trên GitHub khác khóa của công cụ này. Không tự ghi đè.", false, false);
  }

  public static Client github(String token) {
    return new Client() {
      private HttpURLConnection connect(String path, String method) throws Exception {
        if (!path.equals(PROFILE_PATH) && !path.matches("docs/catalog/[0-9a-f]{64}\\.json")) {
          throw new SyncException("Đường dẫn lưu trữ không hợp lệ.", false, false);
        }
        String address = "https://api.github.com/repos/manh212/SandFly-123/contents/" + path
            + (method.equals("GET") ? "?ref=master" : "");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "Meridian-Admin");
        return connection;
      }

      @Override public RemoteFile get(String path) throws Exception {
        HttpURLConnection connection = connect(path, "GET");
        try {
          int status = connection.getResponseCode();
          if (status == 404) return null;
          if (status != 200) throw failure(status);
          JSONObject file = new JSONObject(read(connection));
          if (!file.optString("type").equals("file") || file.optLong("size", 65537) > 65536
              || !file.optString("encoding").equals("base64")
              || !file.optString("sha").matches("[0-9a-f]{40,64}")) {
            throw new SyncException("Tệp GitHub sai định dạng.", false, false);
          }
          byte[] decoded = Base64.getMimeDecoder().decode(file.getString("content"));
          if (decoded.length > 65536) throw new IOException("Tệp vượt giới hạn cho phép.");
          return new RemoteFile(new String(decoded, StandardCharsets.UTF_8), file.getString("sha"));
        } finally { connection.disconnect(); }
      }

      @Override public void put(String path, String content, String sha) throws Exception {
        HttpURLConnection connection = connect(path, "PUT");
        try {
          JSONObject body = new JSONObject();
          body.put("message", path.equals(PROFILE_PATH)
              ? "Configure distribution verification identity" : "Update distribution catalog record");
          body.put("branch", "master");
          body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
          if (sha != null) body.put("sha", sha);
          byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
          connection.setDoOutput(true);
          connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
          connection.setFixedLengthStreamingMode(bytes.length);
          try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
          int status = connection.getResponseCode();
          if (status != 200 && status != 201) throw failure(status);
          read(connection);
        } finally { connection.disconnect(); }
      }

      private String read(HttpURLConnection connection) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = connection.getInputStream()) {
          byte[] buffer = new byte[2048];
          int count;
          while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 131072) throw new IOException("Phản hồi GitHub quá lớn.");
            bytes.write(buffer, 0, count);
          }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
      }

      private SyncException failure(int status) {
        if (status == 401 || status == 403 || status == 404) {
          return new SyncException("Token hết hạn, thiếu quyền Contents: Read and write hoặc chưa chọn đúng kho GitHub.", false, false);
        }
        boolean conflict = status == 409 || status == 422;
        return new SyncException("GitHub chưa lưu được dữ liệu (HTTP " + status + ").",
            conflict || status == 429 || status >= 500, conflict);
      }
    };
  }
}
