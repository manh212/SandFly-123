package io.meridian.licensing;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public final class ForumProtocol {
  private static final List<String> CODES = Collections.unmodifiableList(
      Arrays.asList("general", "questions", "lua", "ideas", "guides"));
  private static final List<String> NAMES = Collections.unmodifiableList(
      Arrays.asList("Thảo luận chung", "Hỏi đáp", "Chia sẻ Lua", "Đề xuất tính năng", "Hướng dẫn sử dụng"));
  private static final SecureRandom RANDOM = new SecureRandom();

  private ForumProtocol() {}

  public static List<String> categories() { return CODES; }
  public static List<String> categoryNames() { return NAMES; }

  public static String categoryName(String code) {
    int index = CODES.indexOf(code);
    return index < 0 ? "Chuyên mục không xác định" : NAMES.get(index);
  }

  public static boolean validCategory(String code) { return CODES.contains(code); }

  public static String id() {
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);
    StringBuilder value = new StringBuilder(String.format(Locale.ROOT, "%013d-", System.currentTimeMillis()));
    for (byte b : random) value.append(String.format(Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }

  public static boolean validId(String value) {
    return value != null && value.matches("[0-9]{13}-[0-9a-f]{20}");
  }

  public static boolean validUid(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
  }

  public static String text(String raw, int min, int max, String label) {
    String value = raw == null ? "" : raw.trim();
    if (value.length() < min || value.length() > max) {
      throw new IllegalArgumentException(label + " phải dài từ " + min + " đến " + max + " ký tự.");
    }
    return value;
  }

  public static List<String> newest(JSONObject records) {
    List<String> keys = new ArrayList<>();
    for (java.util.Iterator<String> iterator = records.keys(); iterator.hasNext();) {
      String key = iterator.next();
      if (validId(key)) keys.add(key);
    }
    keys.sort(Collections.reverseOrder());
    return keys;
  }

  public static String noticeId(String topic, String post) {
    if (!validId(topic) || !validId(post)) throw new IllegalArgumentException("Mã bài viết không hợp lệ.");
    return topic + "_" + post;
  }
}
