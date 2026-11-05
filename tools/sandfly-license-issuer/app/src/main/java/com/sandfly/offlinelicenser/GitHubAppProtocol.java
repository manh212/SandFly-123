package com.sandfly.offlinelicenser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

final class GitHubAppProtocol {
  static final String REPOSITORY = io.meridian.licensing.DistributionEndpoints.repository();
  static final String RETURN_SCHEME = "io.meridian.admin";
  static final String RETURN_URI = RETURN_SCHEME + "://github/complete";
  private GitHubAppProtocol() {}

  static String random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return encode(bytes);
  }

  static JSONObject manifest(String base, String route) throws Exception {
    return new JSONObject()
        .put("name", "Meridian Admin " + random().substring(0, 12))
        .put("url", io.meridian.licensing.DistributionEndpoints.webRepository())
        .put("description", "Đồng bộ giấy phép Meridian từ điện thoại quản trị.")
        .put("public", false)
        .put("hook_attributes", new JSONObject().put("active", false)
            .put("url", io.meridian.licensing.DistributionEndpoints.webRepository()))
        .put("default_permissions", new JSONObject().put("contents", "write"))
        .put("default_events", new JSONArray())
        .put("request_oauth_on_install", false)
        .put("setup_on_update", true)
        .put("redirect_url", base + route + "/created")
        .put("setup_url", base + route + "/installed");
  }

  static JSONObject credentials(JSONObject response, int port, String route) throws Exception {
    if (!"manh212".equals(response.getJSONObject("owner").getString("login"))
        || !response.getString("slug").matches("[a-z0-9-]{1,100}")
        || response.getLong("id") <= 0
        || !"write".equals(response.getJSONObject("permissions").optString("contents"))) {
      throw new IllegalStateException("Hãy đăng nhập tài khoản GitHub manh212 để kết nối dự án.");
    }
    jwt(response, System.currentTimeMillis() / 1000L);
    return new JSONObject().put("id", response.getLong("id"))
        .put("slug", response.getString("slug")).put("pem", response.getString("pem"))
        .put("port", port).put("route", route);
  }

  static String jwt(JSONObject credentials, long now) throws Exception {
    String pem = credentials.getString("pem");
    boolean rsa = pem.startsWith("-----BEGIN RSA PRIVATE KEY-----");
    String body = pem.replace("-----BEGIN RSA PRIVATE KEY-----", "")
        .replace("-----END RSA PRIVATE KEY-----", "")
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    byte[] key = Base64.getDecoder().decode(body);
    if (rsa) {
      byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48,
          (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
      ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
      wrapped.write(new byte[] {0x02, 0x01, 0x00});
      wrapped.write(algorithm);
      wrapped.write(der(0x04, key));
      key = der(0x30, wrapped.toByteArray());
    }
    PrivateKey privateKey = KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(key));
    String payload = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
        + "." + encode(new JSONObject().put("iss", credentials.getLong("id"))
            .put("iat", now - 60).put("exp", now + 480).toString().getBytes(StandardCharsets.UTF_8));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(privateKey);
    signature.update(payload.getBytes(StandardCharsets.US_ASCII));
    return payload + "." + encode(signature.sign());
  }

  static JSONObject tokenRequest() throws Exception {
    return new JSONObject().put("repositories", new JSONArray().put(io.meridian.licensing.DistributionEndpoints.repositoryName()))
        .put("permissions", new JSONObject().put("contents", "write"));
  }

  static void validateReturn(String value, JSONObject pending, long now) throws Exception {
    URI uri = new URI(value);
    String state = pending.optString("state");
    if (!RETURN_SCHEME.equals(uri.getScheme()) || !"github".equals(uri.getHost())
        || !"/complete".equals(uri.getPath()) || uri.getPort() != -1
        || uri.getUserInfo() != null || uri.getFragment() != null
        || state.isEmpty() || !state.matches("[A-Za-z0-9_-]{43}")
        || !pending.optBoolean("ready") || pending.optLong("expires") < now
        || !("state=" + state).equals(uri.getRawQuery())) {
      throw new IllegalStateException("Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại.");
    }
  }

  private static byte[] der(int tag, byte[] bytes) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    if (bytes.length < 128) out.write(bytes.length);
    else {
      int count = bytes.length <= 255 ? 1 : bytes.length <= 65535 ? 2 : 3;
      out.write(0x80 | count);
      for (int i = count - 1; i >= 0; i--) out.write(bytes.length >> (8 * i));
    }
    out.write(bytes);
    return out.toByteArray();
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
