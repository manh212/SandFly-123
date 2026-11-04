package com.sandfly.offlinelicenser;

import io.meridian.licensing.SupportAttachment;
import io.meridian.licensing.PrivateLogFiles;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class SupportLogDownload {
  interface Store {
    JSONObject read() throws Exception;
    void write(JSONObject value) throws Exception;
  }
  interface Target { OutputStream open() throws Exception; }
  interface Loader { String load(String ticket, String message, JSONObject entry) throws Exception; }

  static void prepare(Store store, PrivateLogFiles files, String file, long now) throws Exception {
    SupportAttachment.inspect(files.source(file));
    replace(store, files, new JSONObject().put("file", file).put("created", now));
  }

  static void prepareRemote(Store store, PrivateLogFiles files, String ticket, String message,
      JSONObject entry, long now) throws Exception {
    long bytes = entry.getLong("logBytes");
    String sha256 = entry.getString("logSha256");
    SupportAttachment.validate(new JSONObject().put("bytes", bytes).put("sha256", sha256)
        .put("parts", (bytes + SupportAttachment.CHUNK_BYTES - 1) / SupportAttachment.CHUNK_BYTES));
    if (!ticket.matches("[A-Za-z0-9_-]{1,128}") || !message.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new IllegalArgumentException("Thông tin nhật ký không hợp lệ.");
    }
    replace(store, files, new JSONObject().put("ticket", ticket).put("message", message)
        .put("entry", new JSONObject().put("logBytes", bytes).put("logSha256", sha256))
        .put("created", now));
  }

  private static void replace(Store store, PrivateLogFiles files, JSONObject pending) throws Exception {
    String previous = store.read().optString("file");
    store.write(pending);
    if (!previous.isEmpty() && !previous.equals(pending.optString("file"))) files.delete(previous);
  }

  static boolean save(Store store, PrivateLogFiles files, Target target, long now) throws Exception {
    return save(store, files, target, now, null);
  }

  static boolean save(Store store, PrivateLogFiles files, Target target, long now, Loader loader) throws Exception {
    JSONObject pending = store.read();
    try {
      if (target == null) return false;
      long age = now - pending.optLong("created");
      if (pending.length() == 0 || age < 0 || age > 600_000L) {
        throw new IllegalStateException("Phiên tải đã hết hạn. Chọn lại nhật ký cần tải.");
      }
      String file = pending.optString("file");
      if (pending.has("entry")) {
        if (loader == null) throw new IllegalStateException("Chưa tải được nhật ký. Hãy thử lại.");
        file = loader.load(pending.getString("ticket"), pending.getString("message"), pending.getJSONObject("entry"));
        pending.put("file", file);
      }
      if (!file.isEmpty()) SupportAttachment.inspect(files.source(file));
      else if (SupportAttachment.exact(pending.optString("text")).isEmpty()) throw new IllegalStateException("Nhật ký không hợp lệ.");
      try (OutputStream stream = target.open()) {
        if (stream == null) throw new IllegalStateException("Không mở được tệp để lưu.");
        if (file.isEmpty()) stream.write(pending.getString("text").getBytes(StandardCharsets.UTF_8));
        else try (InputStream input = files.source(file).open()) {
          byte[] bytes = new byte[64 * 1024]; int count;
          while ((count = input.read(bytes)) != -1) stream.write(bytes, 0, count);
        }
      }
      return true;
    } finally {
      try { store.write(new JSONObject()); }
      finally { if (!pending.optString("file").isEmpty()) files.delete(pending.getString("file")); }
    }
  }

  private SupportLogDownload() {}
}
