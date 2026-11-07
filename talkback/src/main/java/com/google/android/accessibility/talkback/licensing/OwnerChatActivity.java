package com.google.android.accessibility.talkback.licensing;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class OwnerChatActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout root, chatLines;
  private TextView status;
  private EditText message;
  private Button send, inboxButton, backButton;
  private volatile String currentChatUid;

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
    inboxButton = button("Hộp thư thành viên", () -> openInbox(null));
    inboxButton.setVisibility(View.GONE);
    backButton = button("Tin nhắn của tôi", () -> {
      currentChatUid = null;
      backButton.setVisibility(View.GONE);
      setTitle("Nhắn tin");
      refresh(null);
    });
    backButton.setVisibility(View.GONE);
    chatLines = new LinearLayout(this);
    chatLines.setOrientation(LinearLayout.VERTICAL);
    root.addView(chatLines);
    checkOwnerAccess();
    refresh(null);
  }

  private TextView text(String value) {
    return text(root, value);
  }

  private TextView text(LinearLayout target, String value) {
    TextView item = new TextView(this);
    item.setTextSize(16);
    item.setText(value);
    item.setPadding(0, 8, 0, 8);
    target.addView(item);
    return item;
  }

  private Button button(String label, Runnable action) {
    return button(root, label, action);
  }

  private Button button(LinearLayout target, String label, Runnable action) {
    Button result = new Button(this);
    result.setText(label);
    result.setAllCaps(false);
    result.setOnClickListener(view -> action.run());
    target.addView(result);
    return result;
  }

  private void status(String value) {
    runOnUiThread(() -> {
      if (!isFinishing() && !isDestroyed()) status.setText(value);
    });
  }


  private void checkOwnerAccess() {
    worker.execute(() -> {
      try {
        boolean owner = new ForumCommunityService(this).isOwner();
        runOnUiThread(() -> {
          if (!isFinishing() && !isDestroyed()) {
            inboxButton.setVisibility(owner ? View.VISIBLE : View.GONE);
          }
        });
      } catch (Exception ignored) {
        // Do not expose the forum-wide inbox to ordinary members.
      }
    });
  }

  private void openInbox(Long before) {
    status("Đang tải hộp thư diễn đàn…");
    worker.execute(() -> {
      try {
        if (!new ForumCommunityService(this).isOwner()) {
          throw new SecurityException("Không có quyền xem hộp thư diễn đàn.");
        }
        FirebaseRest service = Enrollment.service(this);
        JSONObject records = service.activity("forumChatIndex", 50, before);
        List<String> uids = new ArrayList<>();
        for (java.util.Iterator<String> it = records.keys(); it.hasNext();) {
          String uid = it.next();
          if (ForumProtocol.validUid(uid)) uids.add(uid);
        }
        uids.sort((left, right) -> Long.compare(
            records.optJSONObject(right).optLong("lastAt"),
            records.optJSONObject(left).optLong("lastAt")));
        List<String> labels = new ArrayList<>();
        long oldest = Long.MAX_VALUE;
        for (String uid : uids) {
          JSONObject index = records.optJSONObject(uid);
          oldest = Math.min(oldest, index.optLong("lastAt", Long.MAX_VALUE));
          String name = service.get("forumProfiles/" + uid)
              .optString("name", "Thành viên");
          labels.add(name + " · " + uid);
        }
        boolean more = records.length() >= 50 && oldest > 1;
        Long next = more ? oldest - 1 : null;
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          if (uids.isEmpty()) {
            status.setText("Chưa có cuộc trò chuyện nào.");
            return;
          }
          List<String> options = new ArrayList<>(labels);
          if (more) options.add("Xem cuộc trò chuyện cũ hơn");
          new AlertDialog.Builder(this).setTitle("Hộp thư diễn đàn")
              .setItems(options.toArray(new String[0]), (dialog, index) -> {
                if (index == uids.size()) {
                  openInbox(next);
                } else {
                  currentChatUid = uids.get(index);
                  backButton.setVisibility(View.VISIBLE);
                  setTitle("Nhắn tin với " + labels.get(index));
                  refresh(null);
                }
              }).setNegativeButton("Đóng", null).show();
          status.setText("Có " + uids.size() + " cuộc trò chuyện.");
        });
      } catch (Exception error) {
        status(error instanceof FirebaseRest.PermissionDenied
            ? "Firebase chưa cho phép tài khoản xem hộp thư diễn đàn."
            : "Không tải được hộp thư. Kiểm tra quyền và kết nối.");
      }
    });
  }

  private void refresh(String before) {
    String selectedUid = currentChatUid;
    status("Đang tải cuộc trò chuyện…");
    worker.execute(() -> {
      try {
        FirebaseRest service = Enrollment.service(this);
        String uid = service.uid();
        String recipient = selectedUid == null ? uid : selectedUid;
        if (!uid.equals(recipient) && !new ForumCommunityService(this).isOwner()) {
          throw new SecurityException("Không có quyền xem cuộc trò chuyện.");
        }
        JSONObject records = service.recent("forumChats/" + recipient + "/messages",
            "$key", 30, before);
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
              String name = "Người gửi";
              try { name = service.get("forumProfiles/" + from).optString("name", "Người gửi"); }
              catch (Exception ignored) {}
              String badge = ForumCommunityService.publicBadge(service, from);
              who = name + (badge.isEmpty() ? " · Phản hồi diễn đàn" : badge);
              names.put(from, who);
            }
          }
          String when = DateFormat.getDateTimeInstance().format(new Date(entry.optLong("createdAt")));
          lines.add(who + ", " + when + ": " + entry.optString("text"));
        }
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()
              || !Objects.equals(selectedUid, currentChatUid)) return;
          chatLines.removeAllViews();
          if (lines.isEmpty()) text(chatLines, "Chưa có tin nhắn.");
          for (String line : lines) text(chatLines, line);
          if (oldest != null && ids.size() == 30) {
            button(chatLines, "Xem tin nhắn cũ hơn", () -> refresh(oldest));
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
    String selectedUid = currentChatUid;
    send.setEnabled(false);
    status("Đang gửi tin nhắn…");
    worker.execute(() -> {
      try {
        ForumCommunityService forum = new ForumCommunityService(this);
        forum.ensureCanWrite();
        FirebaseRest service = Enrollment.service(this);
        String uid = service.uid();
        String recipient = selectedUid == null ? uid : selectedUid;
        if (!uid.equals(recipient) && !forum.isOwner()) {
          throw new SecurityException("Không có quyền gửi tin nhắn đến thành viên này.");
        }
        String id = ForumProtocol.id();
        JSONObject payload = new JSONObject().put("from", uid).put("text", value)
            .put("createdAt", FirebaseRest.timestamp());
        service.put("forumChats/" + recipient + "/messages/" + id, payload);
        boolean indexed = true;
        try {
          JSONObject saved = service.get("forumChats/" + recipient + "/messages/" + id);
          service.put("forumChatIndex/" + recipient,
              new JSONObject().put("lastAt", saved.getLong("createdAt"))
                  .put("lastMessage", id).put("from", uid));
        } catch (Exception indexError) { indexed = false; }
        boolean finalIndexed = indexed;
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          send.setEnabled(true);
          message.setText("");
          status.setText(finalIndexed ? "Đã gửi tin nhắn."
              : "Đã gửi tin nhắn nhưng chưa cập nhật hộp thư.");
          if (Objects.equals(selectedUid, currentChatUid)) refresh(null);
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
