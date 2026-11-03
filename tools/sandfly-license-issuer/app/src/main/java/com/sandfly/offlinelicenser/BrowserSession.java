package com.sandfly.offlinelicenser;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

final class BrowserSession {
  static final long LIFETIME = 1_800_000L;
  private BrowserSession() {}

  static boolean restorable(JSONObject session, long now) {
    return session.optString("state").matches("[A-Za-z0-9_-]{43}")
        && session.optString("route").matches("/[A-Za-z0-9_-]{43}")
        && session.optInt("port") > 0 && session.optInt("port") <= 65535
        && session.optLong("expires") > now && session.optLong("expires") <= now + LIFETIME;
  }

  static JSONObject created(String value, JSONObject pending, long now) throws Exception {
    String failure = "Liên kết không khớp phiên đăng nhập hoặc đã hết hạn. Hãy đăng nhập GitHub lại.";
    URI uri;
    try { uri = new URI(value); }
    catch (Exception e) { throw new IllegalArgumentException(failure); }
    if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
        || uri.getFragment() != null || uri.getRawPath() == null
        || !uri.getRawPath().matches("/[A-Za-z0-9_-]{43}/created") || uri.getRawQuery() == null
        || pending.optLong("expires") <= now || pending.optLong("expires") > now + LIFETIME) {
      throw new IllegalArgumentException(failure);
    }
    Map<String, String> query = new HashMap<>();
    for (String part : uri.getRawQuery().split("&", -1)) {
      String[] pair = part.split("=", -1);
      if (pair.length != 2) throw new IllegalArgumentException(failure);
      String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name());
      if ((!name.equals("code") && !name.equals("state")) || query.containsKey(name)) {
        throw new IllegalArgumentException(failure);
      }
      query.put(name, URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()));
    }
    String state = pending.optString("state");
    String code = query.getOrDefault("code", "");
    String supplied = query.getOrDefault("state", "");
    String route = uri.getRawPath().substring(0, uri.getRawPath().length() - "/created".length());
    if (!state.matches("[A-Za-z0-9_-]{43}") || !code.matches("[A-Za-z0-9_-]{10,200}")
        || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))
        || (pending.has("port") && pending.getInt("port") != uri.getPort())
        || (pending.has("route") && !pending.getString("route").equals(route))) {
      throw new IllegalArgumentException(failure);
    }
    return new JSONObject().put("code", code).put("port", uri.getPort()).put("route", route);
  }
}
