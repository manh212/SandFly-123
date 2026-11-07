package com.sandfly.offlinelicenser;

import android.app.AlertDialog;
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
import org.json.JSONObject;

final class ForumOwnerChat {
  private final MainActivity activity;
  private TextView status;
  private AlertDialog dialog;

  ForumOwnerChat(MainActivity activity) { this.activity = activity; }

  private FirebaseRest authorized() throws Exception {
    FirebaseRest client = EnrollmentService.create(activity);
    if (!client.flag("admins/" + client.uid())) {
      throw new SecurityException("Chỉ chủ sở hữu được xem tin nhắn riêng.");
    }
    return client;
  }

  private LinearLayout column() {
    LinearLayout result = new LinearLayout(activity);
    result.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
    result.setPadding(padding, padding, padding, padding);
    return result;
  }

  private TextView text(LinearLayout parent, String content) {
    TextView result = new TextView(activity);
    result.setTextSize(16);
    result.setText(content);
    result.setPadding(0, 8, 0, 8);
    parent.addView(result);
    return result;
  }

  private Button button(LinearLayout parent, String label, Runnable action) {
    Button result = new Button(activity);
    result.setAllCaps(false);
    result.setText(label);
    result.setOnClickListener(view -> action.run());
    parent.addView(result);
    return result;
  }

  private interface Action { void call() throws Exception; }

  private void work(String loading, Action action) {
    status.setText(loading);
    SyncCoordinator.READS.execute(() -> {
      try { action.call(); }
      catch (Exception error) {
        String message = error instanceof FirebaseRest.PermissionDenied
            ? "Firebase từ chối quyền truy cập tin nhắn riêng."
            : error instanceof IllegalArgumentException || error instanceof SecurityException
                ? error.getMessage() : "Không thực hiện được yêu cầu. Kiểm tra kết nối mạng.";
        activity.runOnUiThread(() -> {
          if (dialog != null && dialog.isShowing()) status.setText(message);
        });
      }
    });
  }

  private void ui(Runnable action) {
    activity.runOnUiThread(() -> {
      if (!activity.isFinishing() && !activity.isDestroyed() && dialog != null && dialog.isShowing()) action.run();
    });
  }

  void open() {
    LinearLayout layout = column();
    status = text(layout, "");
    button(layout, "Tải lại hộp thư", this::inbox);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(layout);
    dialog = new AlertDialog.Builder(activity).setTitle("Nhắn tin riêng")
        .setView(scroll).setNegativeButton("Đóng", null).create();
    dialog.show();
    inbox();
  }

  private void inbox() {
    work("Đang tải hộp thư…", () -> {
      FirebaseRest client = authorized();
      JSONObject records = client.recent("forumChatIndex", "lastAt", 50, null);
      List<String> uids = new ArrayList<>();
      for (java.util.Iterator<String> it = records.keys(); it.hasNext();) {
        String value = it.next();
        if (ForumProtocol.validUid(value)) uids.add(value);
      }
      uids.sort((left, right) -> Long.compare(records.optJSONObject(right).optLong("lastAt"),
          records.optJSONObject(left).optLong("lastAt")));
      List<String> labels = new ArrayList<>();
      for (String uid : uids) {
        String name = client.get("forumProfiles/" + uid).optString("name", "Thành viên");
        labels.add(name + publicBadge(client, uid) + " · " + uid);
      }
      ui(() -> {
        LinearLayout result = column();
        if (uids.isEmpty()) text(result, "Chưa có cuộc trò chuyện nào.");
        for (int i = 0; i < uids.size(); i++) {
          String uid = uids.get(i);
          button(result, "Mở cuộc trò chuyện: " + labels.get(i), () -> openConversation(uid));
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(result);
        new AlertDialog.Builder(activity).setTitle("Danh sách cuộc trò chuyện")
            .setView(scroll).setNegativeButton("Đóng", null).show();
        status.setText("Có " + uids.size() + " cuộc trò chuyện gần đây.");
      });
    });
  }

  private void openConversation(String uid) {
    work("Đang tải tin nhắn…", () -> {
      FirebaseRest client = authorized();
      JSONObject records = client.recent("forumChats/" + uid + "/messages", "$key", 40, null);
      List<String> keys = new ArrayList<>();
      for (java.util.Iterator<String> it = records.keys(); it.hasNext();) {
        String key = it.next();
        if (ForumProtocol.validId(key)) keys.add(key);
      }
      Collections.sort(keys);
      String name = client.get("forumProfiles/" + uid).optString("name", "Thành viên");
      String senderBadge = publicBadge(client, uid);
      String developerName = client.get("forumProfiles/" + client.uid())
          .optString("name", "Chủ sở hữu");
      String badge = publicBadge(client, client.uid());
      String senderRole = badge.isEmpty() ? " · Chủ sở hữu" : badge;
      JSONObject identity = client.get("forumIdentity/" + uid);
      String fingerprint = identity.optString("fingerprint");
      boolean banned = client.flag("forumBans/" + uid)
          || (fingerprint.matches("[0-9a-f]{64}") && client.flag("forumDeviceBans/" + fingerprint));
      ui(() -> {
        LinearLayout content = column();
        text(content, name + senderBadge + ", UID: " + uid);
        if (keys.isEmpty()) text(content, "Cuộc trò chuyện chưa có tin nhắn.");
        for (String key : keys) {
          JSONObject item = records.optJSONObject(key);
          if (item == null) continue;
          String author = uid.equals(item.optString("from")) ? name + senderBadge
              : developerName + senderRole;
          String when = DateFormat.getDateTimeInstance().format(new Date(item.optLong("createdAt")));
          text(content, author + ", " + when + ": " + item.optString("text"));
        }
        TextView label = text(content, "Trả lời tin nhắn");
        EditText reply = new EditText(activity);
        reply.setId(View.generateViewId());
        reply.setMinLines(2);
        reply.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3000)});
        label.setLabelFor(reply.getId());
        content.addView(reply);
        button(content, "Gửi trả lời", () -> send(uid, reply.getText().toString()));
        button(content, banned ? "Bỏ chặn thành viên" : "Chặn thành viên",
            () -> setBanned(uid, !banned));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("Nhắn tin với " + name)
            .setView(scroll).setNegativeButton("Đóng", null).show();
        status.setText("Đã tải " + keys.size() + " tin nhắn.");
      });
    });
  }

  private String publicBadge(FirebaseRest client, String uid) {
    try {
      String role = client.get("forumPublicRoles/" + uid).optString("badge");
      if ("moderator".equals(role)) return " · Thẻ Quản trị viên";
      if ("developer".equals(role)) return " · Thẻ Nhà phát triển";
    } catch (Exception ignored) {}
    return "";
  }

  private void send(String recipient, String draft) {
    String value;
    try { value = ForumProtocol.text(draft, 1, 3000, "Tin nhắn"); }
    catch (IllegalArgumentException error) { status.setText(error.getMessage()); return; }
    work("Đang trả lời…", () -> {
      FirebaseRest client = authorized();
      String id = ForumProtocol.id();
      client.put("forumChats/" + recipient + "/messages/" + id,
          new JSONObject().put("from", client.uid()).put("text", value)
              .put("createdAt", FirebaseRest.timestamp()));
      JSONObject saved = client.get("forumChats/" + recipient + "/messages/" + id);
      client.put("forumChatIndex/" + recipient,
          new JSONObject().put("lastAt", saved.getLong("createdAt"))
              .put("lastMessage", id).put("from", client.uid()));
      ui(() -> {
        status.setText("Đã gửi phản hồi.");
        openConversation(recipient);
      });
    });
  }

  private void setBanned(String uid, boolean blocked) {
    work("", () -> {
      FirebaseRest client = authorized();
      JSONObject identity = client.get("forumIdentity/" + uid);
      String fingerprint = identity.optString("fingerprint");
      if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalStateException("Không thực hiện được.");
      JSONObject changes = new JSONObject().put("forumBans/" + uid, blocked ? true : JSONObject.NULL)
          .put("forumDeviceBans/" + fingerprint, blocked ? true : JSONObject.NULL);
      client.update(changes);
      ui(() -> status.setText(blocked ? "Đã chặn." : "Đã bỏ chặn."));
    });
  }
}
