package com.google.android.accessibility.talkback.licensing;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.ForumProtocol;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class OwnerChatActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout root;
  private TextView status;
  private EditText message;
  private Button send;

  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    ScrollView scroll = new ScrollView(this);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);
    scroll.addView(root);
    setContentView(scroll);
    setTitle("Nhắn tin");
    status = text("");
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    button("Làm mới cuộc trò chuyện", () -> refresh(null));
    TextView label = text("Nội dung tin nhắn");
    message = new EditText(this);
    message.setId(View.generateViewId());
    message.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    message.setMinLines(2);
    message.setMaxLines(5);
    message.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3000)});
    label.setLabelFor(message.getId());
    root.addView(message);
    send = button("Gửi tin nhắn", this::sendMessage);
    refresh(null);
  }

  private TextView text(String value) {
    TextView item = new TextView(this);
    item.setTextSize(16);
    item.setText(value);
    item.setPadding(0, 8, 0, 8);
    root.addView(item);
    return item;
  }

  private Button button(String label, Runnable action) {
    Button result = new Button(this);
    result.setText(label);
    result.setAllCaps(false);
    result.setOnClickListener(view -> action.run());
    root.addView(result);
    return result;
  }

  private void status(String value) {
    runOnUiThread(() -> {
      if (!isFinishing() && !isDestroyed()) status.setText(value);
    });
  }

  private void refresh(String before) {
    status("Đang tải cuộc trò chuyện…");
    worker.execute(() -> {
      try {
        FirebaseRest service = Enrollment.service(this);
        String uid = service.uid();
        JSONObject records = service.recent("forumChats/" + uid + "/messages", "$key", 30, before);
        List<String> ids = new ArrayList<>();
        for (java.util.Iterator<String> keys = records.keys(); keys.hasNext();) {
          String id = keys.next();
          if (ForumProtocol.validId(id)) ids.add(id);
        }
        Collections.sort(ids);
        String oldest = ids.isEmpty() ? null : ids.get(0);
        List<String> lines = new ArrayList<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (String id : ids) {
          JSONObject entry = records.optJSONObject(id);
          if (entry == null) continue;
          String from = entry.optString("from");
          String who = "Bạn";
          if (!uid.equals(from)) {
            who = names.get(from);
            if (who == null) {
              String name = "Chủ sở hữu";
              try { name = service.get("forumProfiles/" + from).optString("name", "Chủ sở hữu"); }
              catch (Exception ignored) {}
              who = name + " · Nhà phát triển";
              names.put(from, who);
            }
          }
          String when = DateFormat.getDateTimeInstance().format(new Date(entry.optLong("createdAt")));
          lines.add(who + ", " + when + ": " + entry.optString("text"));
        }
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          while (root.getChildCount() > 5) root.removeViewAt(5);
          if (lines.isEmpty()) text("Chưa có tin nhắn.");
          for (String line : lines) text(line);
          if (oldest != null && ids.size() == 30) {
            button("Xem tin nhắn cũ hơn", () -> refresh(oldest));
          }
          status.setText("Đã tải " + ids.size() + " tin nhắn.");
        });
      } catch (Exception error) {
        status(error instanceof FirebaseRest.PermissionDenied
            ? "Chưa tải được tin nhắn. Thử lại."
            : "Chưa tải được tin nhắn. Kiểm tra Internet rồi thử lại.");
      }
    });
  }

  private void sendMessage() {
    String value;
    try { value = ForumProtocol.text(message.getText().toString(), 1, 3000, "Tin nhắn"); }
    catch (IllegalArgumentException error) { message.setError(error.getMessage()); return; }
    send.setEnabled(false);
    status("Đang gửi tin nhắn…");
    worker.execute(() -> {
      try {
        new ForumCommunityService(this).ensureCanWrite();
        FirebaseRest service = Enrollment.service(this);
        String uid = service.uid();
        String id = ForumProtocol.id();
        JSONObject payload = new JSONObject().put("from", uid).put("text", value)
            .put("createdAt", FirebaseRest.timestamp());
        service.put("forumChats/" + uid + "/messages/" + id, payload);
        boolean indexed = true;
        try {
          JSONObject saved = service.get("forumChats/" + uid + "/messages/" + id);
          service.put("forumChatIndex/" + uid,
              new JSONObject().put("lastAt", saved.getLong("createdAt"))
                  .put("lastMessage", id).put("from", uid));
        } catch (Exception indexError) { indexed = false; }
        boolean finalIndexed = indexed;
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          send.setEnabled(true);
          message.setText("");
          status.setText(finalIndexed ? "Đã gửi tin nhắn." : "Đã gửi tin nhắn.");
          refresh(null);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          send.setEnabled(true);
          status.setText(error instanceof FirebaseRest.PermissionDenied
              ? "Không gửi được tin nhắn. Thử lại."
              : "Chưa gửi được tin nhắn. Kiểm tra Internet rồi thử lại.");
        });
      }
    });
  }

  @Override protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }
}
