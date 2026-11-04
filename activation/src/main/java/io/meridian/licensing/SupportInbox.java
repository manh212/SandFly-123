package io.meridian.licensing;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class SupportInbox {
  private SupportInbox() {}

  public static long activityAt(JSONObject item) {
    return Math.max(item.optLong("createdAt"), Math.max(item.optLong("lastAt"),
        Math.max(item.optLong("statusAt"), item.optLong("lastReplyAt"))));
  }

  public static List<String> newest(JSONObject items) {
    List<String> keys = SupportProtocol.keys(items);
    keys.removeIf(key -> items.optJSONObject(key) == null);
    keys.sort((first, second) -> {
      int time = Long.compare(activityAt(items.optJSONObject(second)), activityAt(items.optJSONObject(first)));
      return time == 0 ? second.compareTo(first) : time;
    });
    return keys;
  }

  public static final class Page {
    public final JSONObject items = new JSONObject();
    public final List<String> ids = new ArrayList<>();
    public String next;
  }

  public static Page load(FirebaseRest service, JSONObject states, boolean history, String cursor) throws Exception {
    Page result = new Page();
    Long end = null;
    if (cursor != null) {
      int split = cursor.indexOf(':');
      if (split < 1 || !cursor.substring(split + 1).matches("[0-9]{13}-[0-9a-f]{20}")) {
        throw new IllegalArgumentException("Trang phản hồi không hợp lệ.");
      }
      long time = Long.parseLong(cursor.substring(0, split));
      collect(result, service.activityAt("supportIndex", time), states, history, cursor.substring(split + 1));
      end = time - 1;
    }
    while (result.ids.size() <= 20 && (end == null || end >= 0)) {
      JSONObject batch = service.activity("supportIndex", 100, end);
      List<String> keys = newest(batch);
      collect(result, batch, states, history, null);
      if (keys.size() < 100 || result.ids.size() > 20) break;
      String last = keys.get(keys.size() - 1);
      long time = batch.getJSONObject(last).optLong("lastAt");
      collect(result, service.activityAt("supportIndex", time), states, history, last);
      end = time - 1;
    }
    if (result.ids.size() > 20) {
      result.ids.remove(20);
      String last = result.ids.get(19);
      result.next = result.items.getJSONObject(last).optLong("lastAt") + ":" + last;
    }
    return result;
  }

  private static void collect(Page page, JSONObject items, JSONObject states, boolean history, String before) throws Exception {
    for (String id : newest(items)) {
      if (page.ids.size() > 20) return;
      if (before != null && id.compareTo(before) >= 0) continue;
      JSONObject state = states.optJSONObject(id);
      if (!history && state != null && "resolved".equals(state.optString("state"))) continue;
      if (page.ids.contains(id)) continue;
      page.ids.add(id);
      page.items.put(id, items.getJSONObject(id));
    }
  }
}
