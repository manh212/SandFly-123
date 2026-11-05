package io.meridian.licensing;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

public final class FirebaseEvents implements Closeable {
  public interface Listener { void changed(String event, JSONObject value) throws Exception; }
  private volatile HttpURLConnection connection;
  private volatile boolean closed;
  private volatile long serverMillis, serverNanos;

  public static final class AuthenticationExpired extends IOException {
    public AuthenticationExpired() { super("Phiên nhận cập nhật cần được làm mới."); }
  }

  public long serverTimeMillis() {
    long anchor = serverMillis;
    return anchor <= 0 ? 0 : anchor + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - serverNanos);
  }

  public void read(String address, Listener listener) throws Exception {
    URL target = new URL(address);
    for (int redirects = 0; redirects < 5 && !closed; redirects++) {
      if (!target.getProtocol().equals("https") || target.getUserInfo() != null
          || !(target.getHost().endsWith(".firebaseio.com") || target.getHost().endsWith(".firebasedatabase.app"))) {
        throw new IOException("Địa chỉ cập nhật hỗ trợ không hợp lệ.");
      }
      HttpURLConnection opened = (HttpURLConnection) target.openConnection();
      connection = opened;
      try {
        if (closed) return;
        opened.setConnectTimeout(8_000);
        opened.setReadTimeout(45_000);
        opened.setUseCaches(false);
        opened.setInstanceFollowRedirects(false);
        opened.setRequestProperty("Accept", "text/event-stream");
        int status = opened.getResponseCode();
        if (status == 307 || status == 302) {
          String location = opened.getHeaderField("Location");
          if (location == null) throw new IOException("Không nhận được địa chỉ cập nhật hỗ trợ.");
          target = new URL(target, location);
          continue;
        }
        if (status == 401) {
          try (java.io.InputStream error = opened.getErrorStream()) {
            if (error != null) {
              java.io.ByteArrayOutputStream detail = new java.io.ByteArrayOutputStream();
              byte[] buffer = new byte[1024];
              int count;
              while (detail.size() < 8192 && (count = error.read(buffer)) != -1) detail.write(buffer, 0, count);
              if (detail.toString("UTF-8").toLowerCase(java.util.Locale.ROOT).contains("permission denied")) {
                throw new IOException("Firebase chưa cho phép nhận cập nhật. Kiểm tra quy tắc cơ sở dữ liệu.");
              }
            }
          }
          throw new AuthenticationExpired();
        }
        if (status != 200) throw new IOException("Chưa nhận được cập nhật hỗ trợ (HTTP " + status + ").");
        serverMillis = opened.getDate();
        serverNanos = System.nanoTime();
        try (Reader input = new InputStreamReader(opened.getInputStream(), StandardCharsets.UTF_8)) {
          parse(input, listener, () -> closed);
        }
        return;
      } finally {
        opened.disconnect();
        connection = null;
      }
    }
    if (!closed) throw new IOException("Không kết nối được luồng cập nhật hỗ trợ.");
  }

  public static void parse(Reader source, Listener listener, BooleanSupplier stopped) throws Exception {
    BufferedReader input = new BufferedReader(source);
    String event = "";
    StringBuilder data = new StringBuilder();
    StringBuilder line = new StringBuilder();
    while (!stopped.getAsBoolean()) {
      int next = input.read();
      if (next < 0) return;
      if (next == '\r') continue;
      if (next != '\n') {
        if (line.length() >= 524288) throw new IOException("Cập nhật hỗ trợ vượt giới hạn.");
        line.append((char) next);
        continue;
      }
      String value = line.toString();
      line.setLength(0);
      if (value.isEmpty()) {
        if (event.equals("auth_revoked")) throw new AuthenticationExpired();
        if (event.equals("cancel")) {
          throw new IOException("Kết nối cập nhật hỗ trợ cần được xác thực lại.");
        }
        if ((event.equals("put") || event.equals("patch")) && data.length() > 0) {
          listener.changed(event, new JSONObject(data.toString()));
        }
        event = "";
        data.setLength(0);
      } else if (value.startsWith("event:")) {
        event = value.substring(6).trim();
      } else if (value.startsWith("data:")) {
        if (data.length() + value.length() > 524288) throw new IOException("Cập nhật hỗ trợ vượt giới hạn.");
        if (data.length() > 0) data.append('\n');
        String payload = value.substring(5);
        data.append(payload.startsWith(" ") ? payload.substring(1) : payload);
      }
    }
  }

  @Override public void close() {
    closed = true;
    HttpURLConnection current = connection;
    if (current != null) current.disconnect();
  }
}
