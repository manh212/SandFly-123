package com.sandfly.offlinelicenser;

import io.meridian.licensing.SupportProtocol;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class SupportLogDownload {
  interface Store {
    JSONObject read() throws Exception;
    void write(JSONObject value) throws Exception;
  }
  interface Target { OutputStream open() throws Exception; }

  static void prepare(Store store, String log, long now) throws Exception {
    if (log == null || log.isEmpty() || log.length() > SupportProtocol.LOG_LIMIT) {
      throw new IllegalArgumentException("Nhật ký không hợp lệ.");
    }
    store.write(new JSONObject().put("text", log).put("created", now));
  }

  static boolean save(Store store, Target target, long now) throws Exception {
    JSONObject pending = store.read();
    try {
      if (target == null) return false;
      long age = now - pending.optLong("created");
      if (pending.length() == 0 || age < 0 || age > 600_000L) {
        throw new IllegalStateException("Phiên tải đã hết hạn. Chọn lại nhật ký cần tải.");
      }
      String log = pending.getString("text");
      if (log.isEmpty() || log.length() > SupportProtocol.LOG_LIMIT) throw new IllegalStateException("Nhật ký không hợp lệ.");
      try (OutputStream stream = target.open()) {
        if (stream == null) throw new IllegalStateException("Không mở được tệp để lưu.");
        stream.write(log.getBytes(StandardCharsets.UTF_8));
      }
      return true;
    } finally { store.write(new JSONObject()); }
  }

  private SupportLogDownload() {}
}
