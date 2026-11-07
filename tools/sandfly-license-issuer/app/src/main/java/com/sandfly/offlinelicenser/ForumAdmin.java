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
import java.util.List;
import org.json.JSONObject;

final class ForumAdmin {
  private final MainActivity activity;
  private AlertDialog dialog;
  private LinearLayout root;
  private TextView status;

  ForumAdmin(MainActivity activity) { this.activity = activity; }

  private FirebaseRest authorized() throws Exception {
    FirebaseRest service = EnrollmentService.create(activity);
    if (!service.flag("admins/" + service.uid())) {
      throw new SecurityException("Chỉ chủ sở hữu được quản lý diễn đàn.");
    }
    return service;
  }

  private LinearLayout column() {
    LinearLayout value = new LinearLayout(activity);
    value.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
    value.setPadding(padding, padding, padding, padding);
    return value;
  }

  private TextView text(LinearLayout parent, String message) {
    TextView view = new TextView(activity);
    view.setText(message);
    view.setTextSize(16);
    view.setPadding(0, 8, 0, 8);
    parent.addView(view);
    return view;
  }

  private Button button(LinearLayout parent, String name, Runnable action) {
    Button view = new Button(activity);
    view.setText(name);
    view.setAllCaps(false);
    view.setOnClickListener(v -> action.run());
    parent.addView(view);
    return view;
  }

  private void ui(Runnable operation) {
    activity.runOnUiThread(() -> {
      if (!activity.isFinishing() && !activity.isDestroyed() && dialog != null && dialog.isShowing()) operation.run();
    });
  }

  private interface Task { void run() throws Exception; }

  private void work(String progress, Task task) {
    status.setText(progress);
    SyncCoordinator.READS.execute(() -> {
      try { task.run(); }
      catch (Exception error) {
        String message = error instanceof SecurityException || error instanceof IllegalStateException
            || error instanceof java.io.IOException || error instanceof IllegalArgumentException
            ? error.getMessage() : "Không thực hiện được thao tác với diễn đàn.";
        ui(() -> status.setText(message));
      }
    });
  }

  void open() {
    root = column();
    status = text(root, "Chỉ chủ sở hữu được phép quản lý diễn đàn.");
    button(root, "Danh sách chủ đề mới", this::topics);
    button(root, "Hạn chế một thành viên bằng UID", this::ban);
    button(root, "Danh sách thành viên bị hạn chế", this::banned);
    text(root, "Để xóa toàn bộ dữ liệu diễn đàn, dùng mục Quản lý dữ liệu Firebase.");
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);
    dialog = new AlertDialog.Builder(activity).setTitle("Quản trị diễn đàn")
        .setView(scroll).setNegativeButton("Đóng", null).create();
    dialog.show();
  }

  private void topics() {
    work("Đang tải chủ đề…", () -> {
      FirebaseRest service = authorized();
      JSONObject topics = service.recent("forumTopics", "$key", 30, null);
      List<String> ids = ForumProtocol.newest(topics);
      ui(() -> {
        LinearLayout content = column();
        if (ids.isEmpty()) text(content, "Chưa có chủ đề.");
        for (String id : ids) {
          JSONObject topic = topics.optJSONObject(id);
          if (topic == null) continue;
          String label = topic.optString("title") + " · " + topic.optString("name")
              + (topic.optBoolean("locked") ? " · Đã khóa" : "");
          button(content, label, () -> topic(id, topic));
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("Chủ đề mới nhất")
            .setView(scroll).setNegativeButton("Đóng", null).show();
        status.setText("Đã tải " + ids.size() + " chủ đề gần đây.");
      });
    });
  }

  private void topic(String id, JSONObject item) {
    LinearLayout content = column();
    text(content, item.optString("title") + "\n" + item.optString("body")
        + "\nUID tác giả: " + item.optString("uid"));
    final boolean[] locked = {item.optBoolean("locked")};
    final Button[] toggle = new Button[1];
    toggle[0] = button(content, locked[0] ? "Mở khóa chủ đề" : "Khóa chủ đề", () -> {
      boolean next = !locked[0];
      work("Đang thay đổi trạng thái chủ đề…", () -> {
        authorized().putBoolean("forumTopics/" + id + "/locked", next);
        ui(() -> {
          locked[0] = next;
          toggle[0].setText(next ? "Mở khóa chủ đề" : "Khóa chủ đề");
          status.setText(next ? "Đã khóa chủ đề." : "Đã mở khóa chủ đề.");
        });
      });
    });
    button(content, "Xem và quản lý bình luận", () -> comments(id));
    button(content, "Hạn chế tác giả chủ đề", () -> askBan(item.optString("uid")));
    button(content, "Xóa chủ đề và toàn bộ bình luận", () -> confirm(
        "Xóa chủ đề này và tất cả bình luận?", () -> {
          FirebaseRest service = authorized();
          service.putBoolean("forumTopics/" + id + "/locked", true);
          service.deleteCleanupData("forumPosts/" + id);
          service.deleteCleanupData("forumTopics/" + id);
        }));
    ScrollView scroll = new ScrollView(activity); scroll.addView(content);
    new AlertDialog.Builder(activity).setTitle("Quản lý chủ đề")
        .setView(scroll).setNegativeButton("Đóng", null).show();
  }

  private void comments(String topic) {
    work("Đang đọc bình luận…", () -> {
      JSONObject records = authorized().recent("forumPosts/" + topic, "$key", 40, null);
      List<String> ids = ForumProtocol.newest(records);
      ui(() -> {
        LinearLayout content = column();
        if (ids.isEmpty()) text(content, "Chưa có bình luận.");
        for (String id : ids) {
          JSONObject item = records.optJSONObject(id);
          if (item == null) continue;
          text(content, item.optString("name") + " · " + item.optString("text"));
          button(content, "Xóa bình luận của " + item.optString("name"), () -> confirm(
              "Xóa bình luận đã chọn?", () -> authorized().deleteCleanupData("forumPosts/" + topic + "/" + id)));
          button(content, "Hạn chế " + item.optString("name"), () -> askBan(item.optString("uid")));
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("Bình luận gần đây")
            .setView(scroll).setNegativeButton("Đóng", null).show();
        status.setText("Đã tải " + ids.size() + " bình luận.");
      });
    });
  }

  private void ban() { askBan(""); }

  private void askBan(String suggested) {
    LinearLayout content = column();
    TextView name = text(content, "UID của thành viên cần hạn chế");
    EditText input = new EditText(activity);
    input.setId(View.generateViewId());
    name.setLabelFor(input.getId());
    input.setSingleLine(true);
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
    input.setText(suggested);
    content.addView(input);
    new AlertDialog.Builder(activity).setTitle("Hạn chế thành viên")
        .setView(content).setPositiveButton("Hạn chế", (d, which) -> {
          String uid = input.getText().toString().trim();
          if (!ForumProtocol.validUid(uid)) { status.setText("UID không hợp lệ."); return; }
          confirm("Không cho UID " + uid + " đăng chủ đề hoặc bình luận?", () -> {
            authorized().putBoolean("forumBans/" + uid, true);
          });
        }).setNegativeButton("Hủy", null).show();
  }

  private void banned() {
    work("Đang tải thành viên bị hạn chế…", () -> {
      JSONObject records = authorized().shallowKeys("forumBans");
      ui(() -> {
        LinearLayout content = column();
        if (records.length() == 0) text(content, "Chưa hạn chế thành viên nào.");
        for (java.util.Iterator<String> keys = records.keys(); keys.hasNext();) {
          String uid = keys.next();
          button(content, "Bỏ hạn chế UID " + uid, () -> confirm(
              "Bỏ hạn chế thành viên này?", () -> authorized().deleteCleanupData("forumBans/" + uid)));
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("Thành viên bị hạn chế")
            .setView(scroll).setNegativeButton("Đóng", null).show();
        status.setText("Đang hạn chế " + records.length() + " thành viên.");
      });
    });
  }

  private void confirm(String prompt, Task action) {
    new AlertDialog.Builder(activity).setTitle("Xác nhận quản trị")
        .setMessage(prompt).setNegativeButton("Hủy", null)
        .setPositiveButton("Xác nhận", (dialog, which) -> work("Đang xử lý…", () -> {
          action.run();
          ui(() -> status.setText("Đã hoàn tất thao tác quản trị."));
        })).show();
  }
}
