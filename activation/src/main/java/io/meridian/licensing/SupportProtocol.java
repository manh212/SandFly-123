package io.meridian.licensing;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

public final class SupportProtocol {
  public static final int TEXT_LIMIT = 8000;
  public static final int INLINE_LOG_LIMIT = 32000;
  public static final String[] STATES = {"new", "working", "need_info", "resolved"};
  public static final String[] STATE_NAMES = {"Mới nhận", "Đang xử lý", "Cần thêm thông tin", "Đã giải quyết"};
  private static final SecureRandom RANDOM = new SecureRandom();

  private SupportProtocol() {}

  public static String id() {
    byte[] bytes = new byte[10]; RANDOM.nextBytes(bytes);
    StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%013d-", System.currentTimeMillis()));
    for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }

  public static String text(String value, int limit, boolean required) {
    String result = value == null ? "" : value.trim();
    if ((required && result.isEmpty()) || result.length() > limit
        || result.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
      throw new IllegalArgumentException("Nội dung trống hoặc vượt giới hạn " + limit + " ký tự.");
    }
    return result;
  }

  public static String typeName(String type) {
    return type.equals("bug") ? "Báo lỗi" : type.equals("log") ? "Nhật ký" : "Góp ý";
  }

  public static String stateName(String state) {
    int index = Arrays.asList(STATES).indexOf(state);
    return index < 0 ? STATE_NAMES[0] : STATE_NAMES[index];
  }

  public static JSONObject header(String uid, JSONObject draft) throws Exception {
    RecoveryProtocol.deviceId(draft.getString("request"));
    String type = draft.getString("type");
    if (!Arrays.asList("feedback", "bug", "log").contains(type)) throw new IllegalArgumentException("Loại phản hồi không hợp lệ.");
    return new JSONObject().put("owner", text(uid, 128, true))
        .put("name", text(draft.optString("name"), 80, false))
        .put("subject", text(draft.getString("subject"), 160, true)).put("type", type)
        .put("request", draft.getString("request"))
        .put("appVersion", text(draft.optString("appVersion"), 100, true))
        .put("androidVersion", text(draft.optString("androidVersion"), 100, true))
        .put("createdAt", FirebaseRest.timestamp()).put("lastAt", FirebaseRest.timestamp());
  }

  public static JSONObject message(String uid, String name, String kind, String text, String log) throws Exception {
    return message(uid, name, kind, text, log, null);
  }

  public static JSONObject message(String uid, String name, String kind, String text, String log, JSONObject attachment) throws Exception {
    if (!kind.equals("user") && !kind.equals("staff")) throw new IllegalArgumentException("Người gửi không hợp lệ.");
    String body = text(text, TEXT_LIMIT, false);
    String inline = text(log, INLINE_LOG_LIMIT, false);
    if (attachment != null) SupportAttachment.validate(attachment);
    if (body.isEmpty() && inline.isEmpty() && attachment == null) throw new IllegalArgumentException("Chưa nhập nội dung gửi.");
    JSONObject result = new JSONObject().put("uid", text(uid, 128, true)).put("name", text(name, 80, false))
        .put("kind", kind).put("text", body).put("log", inline).put("createdAt", FirebaseRest.timestamp());
    if (attachment != null) result.put("logBytes", attachment.getInt("bytes")).put("logSha256", attachment.getString("sha256"));
    return result;
  }

  public static boolean sameMessage(JSONObject existing, JSONObject expected) {
    for (String key : new String[]{"uid", "name", "kind", "text", "log", "logBytes", "logSha256"}) {
      if (!existing.optString(key).equals(expected.optString(key))) return false;
    }
    return true;
  }

  public static List<String> keys(JSONObject object) {
    List<String> keys = new ArrayList<>();
    Iterator<String> iterator = object.keys();
    while (iterator.hasNext()) keys.add(iterator.next());
    Collections.sort(keys, Collections.reverseOrder());
    return keys;
  }

  public static void publishUser(FirebaseRest service, JSONObject draft) throws Exception {
    publishUser(service, draft, null, () -> false);
  }

  public static void publishUser(FirebaseRest service, JSONObject draft, SupportAttachment.Source source,
      BooleanSupplier stopped) throws Exception {
    String uid = service.uid(), ticket = draft.getString("ticket"), messageId = draft.getString("message");
    String path = "supportIndex/" + ticket;
    JSONObject header = service.get(path);
    if (header.length() == 0) {
      header = header(uid, draft);
      service.put(path, header);
      header = service.get(path);
    } else if (!header.optString("owner").equals(uid)) {
      throw new IllegalStateException("Hộp thư không khớp danh tính hiện tại.");
    }
    String indexPath = "supportUsers/" + uid + "/" + ticket;
    if (service.get(indexPath).length() == 0) {
      JSONObject index = new JSONObject().put("subject", header.getString("subject"))
          .put("type", header.getString("type")).put("createdAt", header.get("createdAt"));
      service.put(indexPath, index);
    }
    JSONObject attachment = source == null ? null : SupportAttachment.upload(service, ticket, messageId, source, stopped);
    if (stopped.getAsBoolean()) throw new java.io.IOException("Đã tạm dừng gửi phản hồi.");
    JSONObject message = message(uid, draft.optString("name"), "user", draft.optString("text"), draft.optString("log"), attachment);
    String messagePath = "supportMessages/" + ticket + "/" + messageId;
    JSONObject existing = service.get(messagePath);
    if (existing.length() == 0) service.put(messagePath, message);
    else if (!sameMessage(existing, message)) throw new IllegalStateException("Nội dung gửi trùng mã nhưng không khớp. Dữ liệu vẫn giữ trên máy.");
    service.put(path + "/lastAt", FirebaseRest.timestamp());
    service.put("supportContacts/" + uid, new JSONObject().put("request", draft.getString("request"))
        .put("lastSeen", FirebaseRest.timestamp()));
  }
}
