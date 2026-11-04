package io.meridian.licensing;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONObject;

public final class SupportProtocol {
  public static final int TEXT_LIMIT = 8000;
  public static final int LOG_LIMIT = 32000;
  public static final String[] STATES = {"new", "working", "need_info", "resolved"};
  public static final String[] STATE_NAMES = {"Mới nhận", "Đang xử lý", "Cần thêm thông tin", "Đã giải quyết"};
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern SECRET = Pattern.compile(
      "(?i)token|api.?key|password|secret|bearer|authorization|SFLIC[123]\\.|SFPUB1\\.|SFREQ[12]\\.|github_pat_|gh[pousr]_|AIza|https?://|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");

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
    if (!kind.equals("user") && !kind.equals("staff")) throw new IllegalArgumentException("Người gửi không hợp lệ.");
    String body = text(text, TEXT_LIMIT, false);
    String attachment = text(log, LOG_LIMIT, false);
    if (body.isEmpty() && attachment.isEmpty()) throw new IllegalArgumentException("Chưa nhập nội dung gửi.");
    return new JSONObject().put("uid", text(uid, 128, true)).put("name", text(name, 80, false))
        .put("kind", kind).put("text", body).put("log", attachment).put("createdAt", FirebaseRest.timestamp());
  }

  public static boolean sameMessage(JSONObject existing, JSONObject expected) {
    for (String key : new String[]{"uid", "name", "kind", "text", "log"}) {
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

  public static String diagnostic(String raw) {
    StringBuilder out = new StringBuilder("Meridian: nhật ký chẩn đoán đã lọc\n");
    int removed = 0;
    for (String line : (raw == null ? "" : raw).split("\\r?\\n")) {
      String value = line.trim();
      if (SECRET.matcher(value).find()) { removed++; continue; }
      boolean allowed = value.matches("(?:Generated|Manufacturer|Model|Android|Meridian|Source commit): [A-Za-z0-9 ._():+/-]{1,160}")
          || value.matches("at [A-Za-z0-9_.$<>]+\\([A-Za-z0-9_.$: -]+\\)")
          || value.matches("(?:Debug log|Low-latency audio setting|Low-latency playback for this engine): (?:on|off)")
          || value.matches("(?:Sample rate|Burst|Resumed PCM shortages|End-of-stream PCM waits|Interrupted PCM shortages|System output sample rate|System output frames per buffer): [0-9 .-]+(?: Hz| frames)?")
          || value.matches("pref_[a-z0-9_]+: (?:true|false|[0-9.]+)");
      if (allowed) out.append(value).append('\n'); else if (!value.isEmpty()) removed++;
    }
    out.append("Đã loại bỏ dòng ngoài danh sách chẩn đoán an toàn: ").append(removed).append('\n');
    if (out.length() > LOG_LIMIT) return out.substring(out.length() - LOG_LIMIT);
    return out.toString();
  }

  public static void publishUser(FirebaseRest service, JSONObject draft) throws Exception {
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
    JSONObject message = message(uid, draft.optString("name"), "user", draft.optString("text"), draft.optString("log"));
    String messagePath = "supportMessages/" + ticket + "/" + messageId;
    JSONObject existing = service.get(messagePath);
    if (existing.length() == 0) service.put(messagePath, message);
    else if (!sameMessage(existing, message)) throw new IllegalStateException("Nội dung gửi trùng mã nhưng không khớp. Dữ liệu vẫn giữ trên máy.");
    service.put(path + "/lastAt", FirebaseRest.timestamp());
    service.put("supportContacts/" + uid, new JSONObject().put("request", draft.getString("request"))
        .put("lastSeen", FirebaseRest.timestamp()));
  }
}
