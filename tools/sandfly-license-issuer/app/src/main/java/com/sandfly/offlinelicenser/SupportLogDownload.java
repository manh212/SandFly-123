package com.sandfly.offlinelicenser;

import io.meridian.licensing.SupportAttachment;
import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

final class SupportLogDownload {
  interface Store {
    JSONObject read() throws Exception;
    void write(JSONObject value) throws Exception;
  }
  interface Target {
    OutputStream open() throws Exception;
    default void discard() throws Exception {}
  }
  interface Loader { byte[] load(String ticket, String message, JSONObject entry) throws Exception; }

  static synchronized void prepare(Store store, String ticket, String message, JSONObject entry, long now) throws Exception {
    JSONObject pending = new JSONObject().put("id", UUID.randomUUID().toString()).put("created", now);
    if (entry.optLong("logBytes") > 0) {
      long bytes = entry.getLong("logBytes");
      String hash = entry.getString("logSha256");
      SupportAttachment.validate(new JSONObject().put("bytes", bytes).put("sha256", hash)
          .put("parts", (bytes + SupportAttachment.CHUNK_BYTES - 1) / SupportAttachment.CHUNK_BYTES));
      if (!ticket.matches("[A-Za-z0-9_-]{1,128}") || !message.matches("[A-Za-z0-9_-]{1,128}")) {
        throw new IllegalArgumentException("Thông tin nhật ký không hợp lệ.");
      }
      pending.put("ticket", ticket).put("message", message)
          .put("entry", new JSONObject().put("logBytes", bytes).put("logSha256", hash));
    } else {
      String text = SupportAttachment.exact(entry.getString("log"));
      if (text.isEmpty()) throw new IllegalArgumentException("Nhật ký trống.");
      pending.put("text", text);
    }
    store.write(pending);
  }

  static boolean save(Store store, Target target, long now, Loader loader) throws Exception {
    JSONObject pending;
    synchronized (SupportLogDownload.class) { pending = store.read(); }
    boolean complete = false;
    try {
      if (target == null) return false;
      long age = now - pending.optLong("created");
      if (pending.length() == 0 || age < 0 || age > 600_000L) {
        throw new IllegalStateException("Phiên tải đã hết hạn. Chọn lại nhật ký cần tải.");
      }
      byte[] data;
      if (pending.has("entry")) {
        JSONObject entry = pending.getJSONObject("entry");
        data = loader.load(pending.getString("ticket"), pending.getString("message"), entry);
        if (data == null || data.length != entry.getLong("logBytes")) throw new IllegalStateException("Kích thước nhật ký không khớp.");
        byte[] received = data;
        if (!SupportAttachment.inspect(() -> new ByteArrayInputStream(received)).getString("sha256").equals(entry.getString("logSha256"))) {
          throw new IllegalStateException("Nhật ký bị thiếu hoặc thay đổi. Hãy tải lại.");
        }
      } else {
        data = SupportAttachment.exact(pending.getString("text")).getBytes(StandardCharsets.UTF_8);
      }
      try (OutputStream stream = target.open()) {
        if (stream == null) throw new IllegalStateException("Không mở được tệp để lưu.");
        for (int offset = 0; offset < data.length; offset += 64 * 1024) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Đã hủy tải nhật ký.");
          stream.write(data, offset, Math.min(64 * 1024, data.length - offset));
        }
      }
      complete = true;
      return true;
    } finally {
      try { if (!complete && target != null) target.discard(); }
      catch (Exception ignored) {}
      synchronized (SupportLogDownload.class) {
        if (pending.optString("id").equals(store.read().optString("id"))) store.write(new JSONObject());
      }
    }
  }

  private SupportLogDownload() {}
}
