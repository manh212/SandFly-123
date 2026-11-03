package com.sandfly.offlinelicenser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class GitHubConnection {
  private GitHubConnection() {}

  static String token(IssuerStore store) throws Exception {
    JSONObject app = store.connection();
    if (app.length() == 0) return store.legacyToken();
    if (!app.optBoolean("confirmed") && !store.legacyToken().isEmpty()) return store.legacyToken();
    return installationToken(app);
  }

  static String installationToken(JSONObject app) throws Exception {
    String jwt = GitHubAppProtocol.jwt(app, System.currentTimeMillis() / 1000L);
    JSONObject installation = request("GET", "/repos/" + GitHubAppProtocol.REPOSITORY
        + "/installation", jwt, null);
    if (installation.getLong("app_id") != app.getLong("id")
        || !"manh212".equals(installation.getJSONObject("account").getString("login"))
        || !"selected".equals(installation.optString("repository_selection"))
        || !"write".equals(installation.getJSONObject("permissions").optString("contents"))) {
      throw new IllegalStateException("Hãy chọn riêng SandFly-123 khi cấp quyền GitHub.");
    }
    JSONObject result = request("POST", "/app/installations/" + installation.getLong("id")
        + "/access_tokens", jwt, GitHubAppProtocol.tokenRequest());
    String token = result.getString("token");
    if (token.isEmpty() || token.length() > 16384 || token.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalStateException("GitHub chưa trả về phiên kết nối hợp lệ.");
    }
    return token;
  }

  static JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
    if (!path.startsWith("/") || path.contains("?") || path.contains("#")) {
      throw new IllegalArgumentException("Đường dẫn GitHub không hợp lệ.");
    }
    HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.github.com" + path).openConnection();
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(15_000);
      connection.setRequestMethod(method);
      connection.setRequestProperty("Accept", "application/vnd.github+json");
      connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
      connection.setRequestProperty("User-Agent", "Meridian-Admin");
      if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
      if (body != null) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
      }
      int status = connection.getResponseCode();
      if (status == 401 || status == 403 || status == 404 || status == 422) {
        throw new IllegalStateException("Chưa có quyền đồng bộ. Hãy đăng nhập GitHub và chọn riêng SandFly-123.");
      }
      if (status < 200 || status >= 300) throw new IOException("GitHub chưa sẵn sàng.");
      try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (out.size() + count > 131072) throw new IOException("Phản hồi GitHub quá lớn.");
          out.write(buffer, 0, count);
        }
        return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
      }
    } finally { connection.disconnect(); }
  }
}
