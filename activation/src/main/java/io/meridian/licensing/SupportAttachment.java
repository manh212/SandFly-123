package io.meridian.licensing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

public final class SupportAttachment {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  public static final int CHUNK_BYTES = 512 * 1024;
  public static final int MAX_PARTS = MAX_BYTES / CHUNK_BYTES;
  public interface Source { InputStream open() throws Exception; }

  public static String exact(String value) {
    String result = value == null ? "" : value;
    if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw tooLarge();
    return result;
  }

  public static IllegalArgumentException tooLarge() {
    return new IllegalArgumentException("Nhật ký vượt quá 10 MB. Tệp chưa được đính kèm; hãy xuất và kiểm tra tệp trên máy.");
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }

  public static JSONObject inspect(Source source) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    int size = 0, count; byte[] buffer = new byte[64 * 1024];
    try (InputStream stream = source.open()) {
      while ((count = stream.read(buffer)) != -1) {
        size += count; if (size > MAX_BYTES) throw tooLarge(); digest.update(buffer, 0, count);
      }
    }
    if (size == 0) throw new IllegalArgumentException("Nhật ký trống.");
    return new JSONObject().put("bytes", size).put("sha256", hex(digest.digest()))
        .put("parts", (size + CHUNK_BYTES - 1) / CHUNK_BYTES);
  }

  public static void validate(JSONObject info) throws Exception {
    long size = info.getLong("bytes"), parts = info.getLong("parts");
    if (size < 1 || size > MAX_BYTES || parts != (size + CHUNK_BYTES - 1) / CHUNK_BYTES
        || !info.getString("sha256").matches("[0-9a-f]{64}")) {
      throw new IllegalStateException("Thông tin tệp nhật ký không hợp lệ.");
    }
  }

  private static boolean same(JSONObject first, JSONObject second) {
    return first.optLong("bytes") == second.optLong("bytes")
        && first.optInt("parts") == second.optInt("parts")
        && first.optString("sha256").equals(second.optString("sha256"));
  }

  public static JSONObject upload(FirebaseRest service, String ticket, String message,
      Source source, BooleanSupplier stopped) throws Exception {
    JSONObject info = inspect(source); String uid = service.uid();
    String metadata = "supportAttachments/" + ticket + "/" + message;
    JSONObject saved = service.get(metadata);
    if (saved.length() == 0) service.put(metadata, new JSONObject(info.toString())
        .put("uid", uid).put("createdAt", FirebaseRest.timestamp()));
    else if (!same(saved, info) || !saved.optString("uid").equals(uid)) {
      throw new IllegalStateException("Tệp nhật ký trước không khớp. Nội dung vẫn được giữ trên máy.");
    }
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    int total = 0;
    try (InputStream stream = source.open()) {
      for (int index = 0; index < info.getInt("parts"); index++) {
        if (stopped.getAsBoolean()) throw new IOException("Đã tạm dừng gửi nhật ký.");
        int length = Math.min(CHUNK_BYTES, info.getInt("bytes") - total);
        byte[] bytes = new byte[length]; readFully(stream, bytes); digest.update(bytes); total += length;
        String data = Base64.getEncoder().encodeToString(bytes);
        String path = "supportLogChunks/" + ticket + "/" + message + "/chunk" + index;
        JSONObject previous = service.get(path);
        if (previous.length() == 0) service.put(path, new JSONObject().put("index", index).put("bytes", length).put("data", data));
        else if (previous.optInt("index", -1) != index || previous.optInt("bytes") != length
            || !previous.optString("data").equals(data)) {
          throw new IllegalStateException("Một phần nhật ký không khớp. Nội dung vẫn được giữ trên máy.");
        }
      }
      if (stream.read() != -1 || !hex(digest.digest()).equals(info.getString("sha256"))) {
        throw new IllegalStateException("Nhật ký đã thay đổi trong lúc gửi. Hãy tạo lại tệp đính kèm.");
      }
    }
    return info;
  }

  public static void download(FirebaseRest service, String ticket, String message,
      JSONObject entry, OutputStream destination) throws Exception {
    JSONObject info = service.get("supportAttachments/" + ticket + "/" + message);
    if (info.length() == 0) throw new IllegalStateException("Nhật ký đã được dọn khỏi Firebase.");
    validate(info);
    if (entry.optInt("logBytes") != info.getInt("bytes")
        || !entry.optString("logSha256").equals(info.getString("sha256"))) {
      throw new IllegalStateException("Thông tin nhật ký không khớp tin nhắn.");
    }
    MessageDigest digest = MessageDigest.getInstance("SHA-256"); int total = 0;
    for (int index = 0; index < info.getInt("parts"); index++) {
      JSONObject part = service.get("supportLogChunks/" + ticket + "/" + message + "/chunk" + index);
      int expected = Math.min(CHUNK_BYTES, info.getInt("bytes") - total);
      if (part.optInt("index", -1) != index || part.optInt("bytes") != expected
          || part.optString("data").length() > 4 * ((CHUNK_BYTES + 2) / 3)) {
        throw new IllegalStateException("Nhật ký thiếu dữ liệu hoặc không hợp lệ. Hãy tải lại.");
      }
      byte[] bytes;
      try { bytes = Base64.getDecoder().decode(part.getString("data")); }
      catch (IllegalArgumentException e) { throw new IllegalStateException("Dữ liệu nhật ký không hợp lệ."); }
      if (bytes.length != expected) throw new IllegalStateException("Kích thước nhật ký không khớp.");
      total += bytes.length; digest.update(bytes); destination.write(bytes);
    }
    if (!hex(digest.digest()).equals(info.getString("sha256"))) throw new IllegalStateException("Nhật ký bị thiếu hoặc thay đổi. Hãy tải lại.");
  }

  public static boolean present(JSONObject entry) {
    return entry.optLong("logBytes") > 0 || !entry.optString("log").isEmpty();
  }

  private static void readFully(InputStream stream, byte[] bytes) throws IOException {
    int position = 0;
    while (position < bytes.length) {
      int count = stream.read(bytes, position, bytes.length - position);
      if (count == -1) throw new IOException("Nhật ký trên máy bị thiếu dữ liệu.");
      position += count;
    }
  }

  private SupportAttachment() {}
}
