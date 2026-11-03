package com.sandfly.offlinelicenser;

import android.content.Context;
import android.content.Intent;
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
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class BrowserConnection {
  private static BrowserConnection active;
  private static WeakReference<MainActivity> observer = new WeakReference<>(null);
  private final Context context;
  private final ServerSocket server;
  private final String route, state, base;
  private final long deadline;
  private volatile boolean closed;
  private volatile boolean watching;

  private BrowserConnection(Context context, JSONObject app, JSONObject pending) throws Exception {
    this.context = context.getApplicationContext();
    long now = System.currentTimeMillis();
    boolean restoring = BrowserSession.restorable(pending, now);
    route = restoring ? pending.getString("route") : app.optString("route", "/" + GitHubAppProtocol.random());
    state = restoring ? pending.getString("state") : GitHubAppProtocol.random();
    long expires = restoring ? pending.getLong("expires") : now + BrowserSession.LIFETIME;
    deadline = SystemClock.elapsedRealtime() + Math.max(1, expires - now);
    server = new ServerSocket();
    server.setReuseAddress(true);
    try {
      server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
          restoring ? pending.getInt("port") : app.optInt("port", 0)), 4);
      server.setSoTimeout(1000);
      base = "http://127.0.0.1:" + server.getLocalPort();
      JSONObject session = restoring ? new JSONObject(pending.toString()) : new JSONObject();
      session.put("state", state).put("expires", expires).put("port", server.getLocalPort()).put("route", route);
      if (!restoring) session.put("ready", false);
      IssuerStore store = new IssuerStore(context);
      store.browser(session);
      store.status(app.length() == 0 ? "Đang chờ GitHub trả mã tạo kết nối về điện thoại."
          : "Đã lưu cấu hình kết nối. Đang chờ xác nhận quyền GitHub.");
    } catch (Exception e) { server.close(); throw e; }
  }

  static synchronized Uri begin(Context context) throws Exception {
    close();
    IssuerStore store = new IssuerStore(context);
    BrowserConnection flow = new BrowserConnection(context, store.connection(), store.browser());
    active = flow;
    try { context.startForegroundService(new Intent(context, BrowserConnectionService.class)); }
    catch (Exception e) { close(); throw e; }
    flow.start();
    return Uri.parse(flow.base + flow.route + "/start");
  }

  private void start() {
    Thread thread = new Thread(this::listen, "meridian-browser-return");
    thread.setDaemon(true);
    thread.start();
  }

  static synchronized boolean restore(Context context) throws Exception {
    if (active != null && !active.closed) return true;
    IssuerStore store = new IssuerStore(context);
    JSONObject pending = store.browser();
    if (!BrowserSession.restorable(pending, System.currentTimeMillis())) return false;
    active = new BrowserConnection(context, store.connection(), pending);
    active.start();
    watch();
    return true;
  }

  static void stop(Context context) {
    close();
    context.stopService(new Intent(context, BrowserConnectionService.class));
  }

  static void cancel(Context context) throws Exception {
    stop(context);
    IssuerStore store = new IssuerStore(context);
    store.browser(new JSONObject());
    store.status("Đã hủy phiên đăng nhập GitHub.");
  }

  static Uri continueFromLink(Context context, String value) throws Exception {
    IssuerStore store = new IssuerStore(context);
    JSONObject pending = store.browser();
    JSONObject callback = BrowserSession.created(value, pending, System.currentTimeMillis());
    store.browser(pending.put("port", callback.getInt("port")).put("route", callback.getString("route")));
    JSONObject app = convert(context, callback.getString("code"), callback.getInt("port"), callback.getString("route"));
    begin(context);
    return Uri.parse(installationUrl(app));
  }

  private static JSONObject convert(Context context, String code, int port, String route) throws Exception {
    IssuerStore store = new IssuerStore(context);
    JSONObject existing = store.connection();
    if (existing.length() != 0) return existing;
    JSONObject response = GitHubConnection.request("POST", "/app-manifests/" + code + "/conversions", null, new JSONObject());
    JSONObject credentials = GitHubAppProtocol.credentials(response, port, route);
    store.connection(credentials);
    store.status("Đã nhận mã tạo kết nối. Hãy xác nhận quyền cho SandFly-123 trong GitHub.");
    return credentials;
  }

  static synchronized void observe(MainActivity activity) {
    observer = new WeakReference<>(activity);
  }

  static synchronized void watch() {
    if (active == null || active.watching) return;
    active.watching = true;
    Thread thread = new Thread(active::watchInstallation, "meridian-installation-check");
    thread.setDaemon(true);
    thread.start();
  }

  private static synchronized void completed(String state) {
    MainActivity activity = observer.get();
    if (activity != null) activity.browserReady(Uri.parse(GitHubAppProtocol.RETURN_URI + "?state=" + state));
  }

  static void resume(Context context) throws Exception {
    IssuerStore store = new IssuerStore(context);
    JSONObject pending = store.browser();
    if (pending.optLong("expires") < System.currentTimeMillis()) return;
    String state = pending.optString("state");
    if (!state.matches("[A-Za-z0-9_-]{43}") || store.connection().length() == 0) return;
    if (!pending.optBoolean("ready")) {
      GitHubConnection.installationToken(store.connection());
      store.browser(pending.put("ready", true));
    }
    completed(state);
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
    stop(context);
  }

  private void listen() {
    try {
      while (!closed && SystemClock.elapsedRealtime() < deadline) {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(5000);
          handle(socket);
        } catch (SocketTimeoutException ignored) {
        } catch (Exception ignored) {
          if (server.isClosed()) break;
        }
      }
    } finally {
      try { server.close(); } catch (Exception ignored) {}
      if (!closed) SyncCoordinator.WORKER.execute(() -> {
        synchronized (BrowserConnection.class) {
          if (active != this) return;
          try {
            IssuerStore store = new IssuerStore(context);
            store.browser(new JSONObject());
            store.status("Phiên nhận mã GitHub đã kết thúc. Hãy đăng nhập GitHub lại.");
          } catch (Exception ignored) {}
          stop(context);
        }
      });
    }
  }

  private void watchInstallation() {
    while (!closed && SystemClock.elapsedRealtime() < deadline) {
      try { Thread.sleep(5000); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      if (closed) return;
      try {
        boolean available = SyncCoordinator.WORKER.submit(() -> {
          if (closed || new IssuerStore(context).connection().length() == 0) return false;
          markReady();
          return true;
        }).get(45, TimeUnit.SECONDS);
        if (available && !closed) {
          completed(state);
        }
      } catch (Exception ignored) {}
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
          returnToApp(socket);
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
      try {
        JSONObject app = SyncCoordinator.WORKER.submit(() -> {
          JSONObject callback = BrowserSession.created(uri.toString(), new IssuerStore(context).browser(), System.currentTimeMillis());
          return convert(context, callback.getString("code"), callback.getInt("port"), callback.getString("route"));
        }).get(60, TimeUnit.SECONDS);
        redirect(socket, installationUrl(app));
      } catch (Exception e) {
        respond(socket, "200 OK", null, "Không hoàn tất được kết nối. Hãy quay lại ứng dụng, kiểm tra tài khoản manh212 và thử đăng nhập lại.");
      }
    } else if ((route + "/installed").equals(path)) {
      try {
        ready();
        returnToApp(socket);
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
      markReady();
      return true;
    }).get(60, TimeUnit.SECONDS);
  }

  private void markReady() throws Exception {
    if (closed) throw new IllegalStateException();
    IssuerStore store = new IssuerStore(context);
    JSONObject pending = store.browser();
    if (!same(state, pending.optString("state")) || pending.optLong("expires") < System.currentTimeMillis()) {
      throw new IllegalStateException();
    }
    if (pending.optBoolean("ready")) return;
    GitHubConnection.installationToken(store.connection());
    if (closed) throw new IllegalStateException();
    store.browser(pending.put("ready", true));
  }

  private void returnToApp(Socket socket) throws Exception {
    completed(state);
    respond(socket, "200 OK", null, "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\">"
        + "<title>Đã kết nối GitHub</title><p>Đã kết nối. Ứng dụng đang tự đồng bộ.</p>"
        + "<a href=\"" + GitHubAppProtocol.RETURN_URI + "?state=" + state
        + "\">Trở về Meridian Quản trị</a></html>");
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
