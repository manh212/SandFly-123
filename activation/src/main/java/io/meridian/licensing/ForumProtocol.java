package io.meridian.licensing;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ForumProtocol {
  private static final long DAY_MILLIS = 86_400_000L;
  private static final long VIETNAM_OFFSET_MILLIS = 25_200_000L;
  private static final List<String> CODES = Collections.unmodifiableList(
      Arrays.asList("general", "questions", "lua", "ideas", "guides"));
  private static final List<String> NAMES = Collections.unmodifiableList(
      Arrays.asList("Thảo luận chung", "Hỏi đáp", "Chia sẻ", "Đề xuất tính năng", "Hướng dẫn sử dụng"));
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
    String input = raw == null ? "" : raw;
    String value = input.trim();
    if (value.length() < min || value.length() > max || input.chars().anyMatch(ch -> Character.isISOControl(ch)
        && ch != '\n' && ch != '\r' && ch != '\t')) {
      throw new IllegalArgumentException(label + " không hợp lệ.");
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

  public static long ratingOffset(int stars) {
    if (stars < 1 || stars > 5) throw new IllegalArgumentException("Đánh giá không hợp lệ.");
    return (stars - 3L) * 604_800_000L;
  }

  public static long vietnamDay(long trustedServerMillis) {
    if (trustedServerMillis <= 0 || trustedServerMillis > Long.MAX_VALUE - VIETNAM_OFFSET_MILLIS) {
      throw new IllegalArgumentException("Chưa xác minh được thời gian máy chủ.");
    }
    return Math.floorDiv(trustedServerMillis + VIETNAM_OFFSET_MILLIS, DAY_MILLIS);
  }

  public static long nextVietnamMidnight(long trustedServerMillis) {
    long day = vietnamDay(trustedServerMillis);
    return Math.multiplyExact(day + 1, DAY_MILLIS) - VIETNAM_OFFSET_MILLIS;
  }

  public static boolean canUseDailySlot(int alreadyUsed) {
    return alreadyUsed >= 0 && alreadyUsed < 3;
  }

  public static String validDisplayName(String value) {
    String name = text(value, 2, 60, "Tên hiển thị");
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.contains("quản trị viên") || lower.contains("người điều hành")
        || lower.contains("nhà phát triển")) {
      throw new IllegalArgumentException("Tên hiển thị không hợp lệ.");
    }
    return name;
  }

  public static JSONArray pollOptions(List<String> values) {
    if (values == null || values.size() < 2 || values.size() > 6) {
      throw new IllegalArgumentException("Bình chọn không hợp lệ.");
    }
    JSONArray result = new JSONArray();
    Set<String> unique = new HashSet<>();
    for (String item : values) {
      String option = text(item, 1, 120, "Phương án bình chọn");
      if (!unique.add(option.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Bình chọn không hợp lệ.");
      }
      result.put(option);
    }
    return result;
  }
}
