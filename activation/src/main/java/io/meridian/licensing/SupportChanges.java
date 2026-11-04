package io.meridian.licensing;

import java.util.Arrays;
import org.json.JSONObject;

public final class SupportChanges {
  public interface Sink { void deliver(String ticket, JSONObject item, boolean reply) throws Exception; }
  private JSONObject items = new JSONObject();
  private final JSONObject seen;
  private boolean initialized;

  public SupportChanges(JSONObject saved) {
    seen = saved.optJSONObject("seen") == null ? new JSONObject() : saved.optJSONObject("seen");
    initialized = saved.optBoolean("initialized");
  }

  public JSONObject saved() throws Exception {
    return new JSONObject().put("initialized", initialized).put("seen", seen);
  }

  public void accept(String event, JSONObject update, Sink sink) throws Exception {
    String path = update.optString("path", "");
    if (!path.startsWith("/") || path.length() > 512) return;
    Object data = update.opt("data");
    if (path.equals("/")) {
      if (event.equals("put")) items = data instanceof JSONObject ? new JSONObject(data.toString()) : new JSONObject();
      else if (data instanceof JSONObject) {
        JSONObject patch = (JSONObject) data;
        for (String key : SupportProtocol.keys(patch)) apply("/" + key, patch.opt(key));
      }
    } else if (event.equals("patch") && data instanceof JSONObject) {
      JSONObject patch = (JSONObject) data;
      for (String key : SupportProtocol.keys(patch)) apply(path + "/" + key, patch.opt(key));
    } else apply(path, data);
    for (String ticket : SupportProtocol.keys(items)) {
      if (!ticket.matches("[0-9]{13}-[0-9a-f]{20}")) continue;
      JSONObject item = items.optJSONObject(ticket);
      if (item == null) continue;
      JSONObject previous = seen.optJSONObject(ticket);
      if (previous == null) previous = new JSONObject();
      String reply = item.optString("lastReplyId");
      String status = item.optString("status", "new");
      long statusAt = item.optLong("statusAt");
      if (initialized && reply.matches("[0-9]{13}-[0-9a-f]{20}")
          && !reply.equals(previous.optString("reply"))) sink.deliver(ticket, item, true);
      if (initialized && statusAt > 0 && statusAt != previous.optLong("statusAt")
          && !status.equals(previous.optString("status", "new"))
          && Arrays.asList(SupportProtocol.STATES).contains(status)) sink.deliver(ticket, item, false);
      seen.put(ticket, new JSONObject().put("reply", reply).put("status", status).put("statusAt", statusAt));
    }
    initialized = true;
  }

  private void apply(String path, Object value) throws Exception {
    String[] parts = path.substring(1).split("/");
    if (parts.length < 1 || parts.length > 3) return;
    JSONObject parent = items;
    for (int index = 0; index < parts.length - 1; index++) {
      if (!parts[index].matches("[A-Za-z0-9_-]{1,128}")) return;
      JSONObject child = parent.optJSONObject(parts[index]);
      if (child == null) { child = new JSONObject(); parent.put(parts[index], child); }
      parent = child;
    }
    String key = parts[parts.length - 1];
    if (!key.matches("[A-Za-z0-9_-]{1,128}")) return;
    if (value == null || value == JSONObject.NULL) parent.remove(key);
    else parent.put(key, value);
  }
}
