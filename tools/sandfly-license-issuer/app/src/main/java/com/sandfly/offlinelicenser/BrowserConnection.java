package com.sandfly.offlinelicenser;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class BrowserConnection {
  private static BrowserConnection active;
  private final Context context;
  private final ServerSocket server;
  private final String route, state, base;
  private final long deadline = SystemClock.elapsedRealtime() + 1_800_000L;
  private volatile boolean closed;

  private BrowserConnection(Context context, JSONObject app) throws Exception {
    this.context = context.getApplicationContext();
    route = app.optString("route", "/" + GitHubAppProtocol.random());
    state = GitHubAppProtocol.random();
    server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), app.optInt("port", 0)), 4);
    server.setSoTimeout(1000);
    base = "http://127.0.0.1:" + server.getLocalPort();
    try {
      new IssuerStore(context).browser(new JSONObject().put("state", state)
          .put("expires", System.currentTimeMillis() + 1_800_000L).put("ready", false));
    } catch (Exception e) { server.close(); throw e; }
  }

  static synchronized Uri begin(Context context) throws Exception {
    close();
    BrowserConnection flow = new BrowserConnection(context, new IssuerStore(context).connection());
    active = flow;
    Thread thread = new Thread(flow::listen, "meridian-browser-return");
    thread.setDaemon(true);
    thread.start();
    return Uri.parse(flow.base + flow.route + "/start");
  }

  static synchronized void close() {
    if (active == null) return;
    active.closed = true;
    try { active.server.close(); } catch (Exception ignored) {}
    active = null;
  }

  static void finish(Context context, Uri uri) throws Exception {
    IssuerStore store = new IssuerStore(context);
    JSONObject pending = store.browser();
    GitHubAppProtocol.validateReturn(uri == null ? "" : uri.toString(), pending, System.currentTimeMillis());
    JSONObject app = store.connection();
    GitHubConnection.installationToken(app);
    store.connection(app.put("confirmed", true));
    store.browser(new JSONObject());
    store.clearLegacyToken();
    store.status("Đã kết nối GitHub. Giấy phép sẽ tự đồng bộ khi được cấp.");
    close();
  }

  private void listen() {
    try {
      while (!closed && SystemClock.elapsedRealtime() < deadline) {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(5000);
          handle(socket);
        } catch (SocketTimeoutException ignored) {
        } catch (Exception ignored) {
        }
      }
    } finally {
      try { server.close(); } catch (Exception ignored) {}
    }
  }

  private void handle(Socket socket) throws Exception {
    InputStream input = socket.getInputStream();
    String[] first = line(input).split(" ");
    String host = "";
    int size = 0;
    for (String header; !(header = line(input)).isEmpty();) {
      size += header.length();
      if (size > 16384) throw new IllegalArgumentException();
      if (header.regionMatches(true, 0, "Host:", 0, 5)) host = header.substring(5).trim();
    }
    if (first.length != 3 || !"GET".equals(first[0]) || !first[1].startsWith("/")
        || !host.equals("127.0.0.1:" + server.getLocalPort())) {
      respond(socket, "400 Bad Request", null, "Yêu cầu không hợp lệ.");
      return;
    }
    Uri uri = Uri.parse(base + first[1]);
    String path = uri.getPath();
    if ((route + "/start").equals(path)) {
      JSONObject app = new IssuerStore(context).connection();
      if (app.length() != 0) {
        try {
          ready();
          redirect(socket, GitHubAppProtocol.RETURN_URI + "?state=" + state);
        } catch (Exception e) { redirect(socket, installationUrl(app)); }
      } else {
        String manifest = GitHubAppProtocol.manifest(base, route).toString();
        respond(socket, "200 OK", null, "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\">"
            + "<title>Kết nối GitHub</title><form id=\"setup\" method=\"post\" action=\"https://github.com/settings/apps/new?state="
            + state + "\"><input type=\"hidden\" name=\"manifest\" value=\"" + html(manifest)
            + "\"><button type=\"submit\">Tiếp tục đăng nhập GitHub</button></form>"
            + "<script>document.getElementById('setup').submit()</script></html>");
      }
    } else if ((route + "/created").equals(path) && same(state, uri.getQueryParameter("state"))) {
      String code = uri.getQueryParameter("code");
      if (code == null || !code.matches("[A-Za-z0-9_\\-]{10,200}")) {
        respond(socket, "400 Bad Request", null, "GitHub chưa trả về mã kết nối hợp lệ.");
        return;
      }
      try {
        JSONObject app = SyncCoordinator.WORKER.submit(() -> {
          IssuerStore store = new IssuerStore(context);
          JSONObject existing = store.connection();
          if (existing.length() != 0) return existing;
          JSONObject response = GitHubConnection.request("POST", "/app-manifests/" + code + "/conversions", null, new JSONObject());
          JSONObject credentials = GitHubAppProtocol.credentials(response, server.getLocalPort(), route);
          store.connection(credentials);
          return credentials;
        }).get(60, TimeUnit.SECONDS);
        redirect(socket, installationUrl(app));
      } catch (Exception e) {
        respond(socket, "200 OK", null, "Không hoàn tất được kết nối. Hãy quay lại ứng dụng, kiểm tra tài khoản manh212 và thử đăng nhập lại.");
      }
    } else if ((route + "/installed").equals(path)) {
      try {
        ready();
        redirect(socket, GitHubAppProtocol.RETURN_URI + "?state=" + state);
      } catch (Exception e) {
        respond(socket, "200 OK", null, "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\">"
            + "<p>Chưa xác nhận được quyền. Hãy chọn riêng SandFly-123 hoặc kiểm tra kết nối mạng.</p>"
            + "<a href=\"" + base + route + "/start\">Thử kết nối lại</a></html>");
      }
    } else {
      respond(socket, "404 Not Found", null, "Không tìm thấy phiên kết nối.");
    }
  }

  private void ready() throws Exception {
    SyncCoordinator.WORKER.submit(() -> {
      IssuerStore store = new IssuerStore(context);
      GitHubConnection.installationToken(store.connection());
      JSONObject pending = store.browser();
      if (!same(state, pending.optString("state")) || pending.optLong("expires") < System.currentTimeMillis()) {
        throw new IllegalStateException();
      }
      store.browser(pending.put("ready", true));
      return true;
    }).get(60, TimeUnit.SECONDS);
  }

  private static String installationUrl(JSONObject app) throws Exception {
    String slug = app.getString("slug");
    if (!slug.matches("[a-z0-9-]{1,100}")) throw new IllegalArgumentException();
    return "https://github.com/apps/" + slug + "/installations/new";
  }

  private static String line(InputStream input) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int value; (value = input.read()) != -1;) {
      if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII.name()).replace("\r", "");
      if (bytes.size() >= 8192) throw new IllegalArgumentException();
      bytes.write(value);
    }
    throw new IllegalArgumentException();
  }

  private static void redirect(Socket socket, String location) throws Exception {
    respond(socket, "302 Found", location, "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\">"
        + "<a href=\"" + html(location) + "\">Quay lại ứng dụng</a></html>");
  }

  private static void respond(Socket socket, String status, String location, String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String headers = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
        + "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
        + "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; form-action https://github.com; base-uri 'none'; frame-ancestors 'none'\r\n"
        + "Connection: close\r\nContent-Length: " + bytes.length + "\r\n"
        + (location == null ? "" : "Location: " + location + "\r\n") + "\r\n";
    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().write(bytes);
    socket.getOutputStream().flush();
  }

  private static boolean same(String expected, String supplied) {
    return supplied != null && !expected.isEmpty() && MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  private static String html(String value) {
    return value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;");
  }
}
