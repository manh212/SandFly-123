package io.meridian.licensing;

import org.json.JSONObject;

public final class LicenseEventState {
  private JSONObject record = new JSONObject();

  public String accept(String event, JSONObject update, String request) throws Exception {
    String path = update.getString("path");
    Object data = update.opt("data");
    if (path.equals("/")) {
      if (event.equals("put")) record = data instanceof JSONObject ? new JSONObject(data.toString()) : new JSONObject();
      else if (data instanceof JSONObject) merge((JSONObject) data);
    } else if (path.matches("/(request|value|updatedAt)")) {
      record.put(path.substring(1), data == null ? JSONObject.NULL : data);
    } else {
      throw new IllegalArgumentException("Đường dẫn trạng thái giấy phép không hợp lệ.");
    }
    if (record.length() == 0 || record.isNull("request") || record.isNull("value")) return null;
    if (!request.equals(record.getString("request"))) throw new IllegalArgumentException("Trạng thái thuộc thiết bị khác.");
    String value = record.getString("value");
    if (value.length() > 2048 || !(value.startsWith("SFLIC2.") || value.startsWith("SFLIC3."))) {
      throw new IllegalArgumentException("Trạng thái giấy phép sai định dạng.");
    }
    return value;
  }

  private void merge(JSONObject next) throws Exception {
    java.util.Iterator<String> fields = next.keys();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!field.matches("request|value|updatedAt")) throw new IllegalArgumentException("Trạng thái giấy phép sai định dạng.");
      record.put(field, next.get(field));
    }
  }
}
