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
import io.meridian.licensing.SupportProtocol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class DataCleanup {
  private static final String[] SUPPORT = {
      "supportLogChunks", "supportAttachments", "supportMessages",
      "supportUsers", "supportStates", "supportIndex"
  };
  private static final String[] FORUM = {
      "forumPosts", "forumTopics", "forumCategories", "forumProfiles", "forumModerators"
  };
  private final MainActivity activity;
  private AlertDialog dialog;
  private LinearLayout root;
  private TextView status;

  private static final class Plan {
    final String title;
    final Set<String> paths = new LinkedHashSet<>();
    long originalBytes;
    int records;
    Plan(String title) { this.title = title; }
    Plan add(String path) { paths.add(path); return this; }
    Plan addAll(String[] items) { for (String item : items) add(item); return this; }
  }

  private static final class LogEntry {
    final String ticket, message;
    final long bytes, createdAt;
    LogEntry(String ticket, String message, long bytes, long createdAt) {
      this.ticket = ticket;
      this.message = message;
      this.bytes = bytes;
      this.createdAt = createdAt;
    }
  }

  DataCleanup(MainActivity activity) { this.activity = activity; }

  private void ui(Runnable action) {
    activity.runOnUiThread(() -> {
      if (!activity.isFinishing() && !activity.isDestroyed()) action.run();
    });
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
    layout.setPadding(padding, padding, padding, padding);
    return layout;
  }

  private TextView text(LinearLayout layout, String value) {
    TextView label = new TextView(activity);
    label.setText(value);
    label.setTextSize(16);
    label.setPadding(0, 8, 0, 8);
    layout.addView(label);
    return label;
  }

  private Button button(LinearLayout layout, String value, Runnable action) {
    Button view = new Button(activity);
    view.setText(value);
    view.setAllCaps(false);
    view.setOnClickListener(v -> action.run());
    layout.addView(view);
    return view;
  }

  private FirebaseRest authorized() throws Exception {
    FirebaseRest firebase = EnrollmentService.create(activity);
    String uid = firebase.uid();
    if (!firebase.flag("admins/" + uid)) {
      throw new SecurityException("Chỉ chủ sở hữu được quản lý và xóa dữ liệu Firebase.");
    }
    return firebase;
  }

  void open() {
    root = column();
    text(root, "Chỉ xóa dữ liệu Firebase được phép. Dữ liệu giấy phép và quyền quản trị luôn được bảo vệ.");
    status = text(root, "Chọn mục cần thống kê hoặc dọn dẹp.");
    button(root, "Thống kê dữ liệu", this::summary);
    button(root, "Xem nhật ký đính kèm", this::showLogs);
    button(root, "Xóa nhật ký theo thời gian", this::chooseLogAge);
    button(root, "Xóa từng cuộc trò chuyện", this::showTickets);
    button(root, "Xóa cuộc trò chuyện cũ", this::chooseTicketAge);
    button(root, "Xóa toàn bộ lịch sử hỗ trợ và nhật ký", () -> prepare("Xóa lịch sử hỗ trợ", service -> {
      Plan plan = new Plan("Xóa toàn bộ lịch sử hỗ trợ và nhật ký").addAll(SUPPORT);
      plan.records = service.shallowKeys("supportIndex").length();
      return plan;
    }));
    button(root, "Xóa yêu cầu đăng ký chưa có quyết định", () -> prepare("Xóa yêu cầu chưa duyệt", this::pendingRequests));
    button(root, "Xóa thông tin liên hệ hỗ trợ", () -> prepare("Xóa liên hệ", service -> {
      Plan plan = new Plan("Xóa thông tin liên hệ").add("supportContacts");
      plan.records = service.shallowKeys("supportContacts").length();
      return plan;
    }));
    button(root, "Xóa danh sách người hỗ trợ", () -> prepare("Xóa người hỗ trợ", service -> {
      Plan plan = new Plan("Xóa danh sách người hỗ trợ").add("supportStaff");
      plan.records = service.shallowKeys("supportStaff").length();
      return plan;
    }));
    button(root, "Xóa toàn bộ dữ liệu diễn đàn", () -> prepare("Xóa diễn đàn", service -> {
      Plan plan = new Plan("Xóa dữ liệu diễn đàn").addAll(FORUM);
      for (String path : FORUM) plan.records += service.shallowKeys(path).length();
      return plan;
    }));
    button(root, "Xóa mọi dữ liệu có thể dọn", () -> prepare("Xóa mọi dữ liệu được phép", service -> {
      Plan plan = new Plan("Xóa mọi dữ liệu được phép").addAll(SUPPORT).add("supportContacts")
          .add("supportStaff").addAll(FORUM);
      plan.records = service.shallowKeys("supportIndex").length();
      Plan pending = pendingRequests(service);
      plan.paths.addAll(pending.paths);
      plan.records += pending.records;
      return plan;
    }));
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);
    dialog = new AlertDialog.Builder(activity).setTitle("Quản lý dữ liệu Firebase")
        .setView(scroll).setNegativeButton("Đóng", null).create();
    dialog.show();
    if (dialog.getWindow() != null) dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
  }

  private interface Planner { Plan prepare(FirebaseRest service) throws Exception; }

  private void work(String text, Runnable task) {
    if (status != null) status.setText(text);
    SyncCoordinator.READS.execute(task);
  }

  private void summary() {
    work("Đang thống kê dữ liệu…", () -> {
      try {
        FirebaseRest service = authorized();
        int threads = service.shallowKeys("supportIndex").length();
        int contacts = service.shallowKeys("supportContacts").length();
        int requests = service.shallowKeys("requests").length();
        int helpers = service.shallowKeys("supportStaff").length();
        List<LogEntry> logs = logs(service);
        long bytes = 0;
        for (LogEntry entry : logs) bytes += entry.bytes;
        final long size = bytes;
        int forumItems = 0;
        for (String group : FORUM) forumItems += service.shallowKeys(group).length();
        int forum = forumItems;
        ui(() -> status.setText("Hội thoại hỗ trợ: " + threads + "\nNhật ký: " + logs.size()
            + ", tệp gốc: " + size(size) + " (Base64 và dữ liệu phụ làm dung lượng Firebase lớn hơn)"
            + "\nLiên hệ: " + contacts + "\nYêu cầu đăng ký: " + requests
            + "\nNgười hỗ trợ: " + helpers + "\nBản ghi diễn đàn: " + forum
            + "\nĐây không phải tổng dung lượng Firebase chính xác. Xem Usage trong Firebase Console để biết hạn mức."));
      } catch (Exception e) { problem(e); }
    });
  }

  private List<LogEntry> logs(FirebaseRest service) throws Exception {
    List<LogEntry> result = new ArrayList<>();
    JSONObject groups = service.shallowKeys("supportAttachments");
    for (String ticket : SupportProtocol.keys(groups)) {
      if (!ticket.matches("[0-9]{13}-[0-9a-f]{20}")) continue;
      JSONObject messages = service.get("supportAttachments/" + ticket);
      for (String message : SupportProtocol.keys(messages)) {
        JSONObject value = messages.optJSONObject(message);
        if (value == null || !message.matches("[0-9]{13}-[0-9a-f]{20}")) continue;
        result.add(new LogEntry(ticket, message, value.optLong("bytes"), value.optLong("createdAt")));
      }
    }
    result.sort(Comparator.comparingLong((LogEntry entry) -> entry.createdAt).reversed());
    return result;
  }

  private static void addLog(Plan plan, LogEntry log) {
    plan.add("supportLogChunks/" + log.ticket + "/" + log.message);
    plan.add("supportAttachments/" + log.ticket + "/" + log.message);
    plan.records++;
    plan.originalBytes += Math.max(0, log.bytes);
  }

  private void showLogs() {
    work("Đang đọc danh sách nhật ký…", () -> {
      try {
        List<LogEntry> all = logs(authorized());
        ui(() -> {
          if (dialog == null || !dialog.isShowing()) return;
          LinearLayout list = column();
          text(list, "Chỉ hiển thị 30 nhật ký gần nhất. Nội dung báo lỗi được giữ nguyên khi xóa tệp.");
          int count = Math.min(all.size(), 30);
          for (int i = 0; i < count; i++) {
            LogEntry entry = all.get(i);
            button(list, "Xóa nhật ký " + entry.message + " · " + size(entry.bytes), () -> {
              Plan plan = new Plan("Xóa một nhật ký");
              addLog(plan, entry);
              confirm(plan);
            });
          }
          if (all.isEmpty()) text(list, "Chưa có nhật ký đính kèm.");
          ScrollView scroll = new ScrollView(activity);
          scroll.addView(list);
          new AlertDialog.Builder(activity).setTitle("Nhật ký đính kèm")
              .setView(scroll).setNegativeButton("Đóng", null).show();
          status.setText("Đã đọc " + all.size() + " nhật ký.");
        });
      } catch (Exception e) { problem(e); }
    });
  }

  private void chooseLogAge() {
    String[] choices = {"Cũ hơn 7 ngày", "Cũ hơn 30 ngày", "Cũ hơn 60 ngày", "Cũ hơn 90 ngày", "Tất cả nhật ký"};
    int[] ages = {7, 30, 60, 90, 0};
    new AlertDialog.Builder(activity).setTitle("Chọn nhật ký cần xóa")
        .setItems(choices, (dialog, selected) -> prepare("Tìm nhật ký", service -> {
          Plan plan = new Plan(choices[selected]);
          long now = System.currentTimeMillis();
          for (LogEntry log : logs(service)) {
            if (ages[selected] == 0 || (log.createdAt > 0
                && log.createdAt <= now - TimeUnit.DAYS.toMillis(ages[selected]))) addLog(plan, log);
          }
          return plan;
        })).setNegativeButton("Hủy", null).show();
  }

  private void showTickets() {
    work("Đang đọc danh sách hội thoại…", () -> {
      try {
        FirebaseRest service = authorized();
        JSONObject headers = service.activity("supportIndex", 30, null);
        List<String> ids = SupportProtocol.keys(headers);
        ids.sort((a, b) -> Long.compare(headers.optJSONObject(b).optLong("lastAt"),
            headers.optJSONObject(a).optLong("lastAt")));
        ui(() -> {
          if (dialog == null || !dialog.isShowing()) return;
          LinearLayout entries = column();
          text(entries, "Chọn một hội thoại để xóa cả tin nhắn và các tệp nhật ký liên quan.");
          for (String ticket : ids) {
            JSONObject item = headers.optJSONObject(ticket);
            if (item == null) continue;
            button(entries, "Xóa: " + item.optString("subject") + " · " + ticket, () -> {
              Plan plan = new Plan("Xóa một cuộc trò chuyện");
              addTicket(plan, ticket, item.optString("owner"));
              confirm(plan);
            });
          }
          if (ids.isEmpty()) text(entries, "Không có cuộc trò chuyện.");
          ScrollView scroll = new ScrollView(activity);
          scroll.addView(entries);
          new AlertDialog.Builder(activity).setTitle("30 hội thoại mới nhất")
              .setView(scroll).setNegativeButton("Đóng", null).show();
          status.setText("Đã đọc danh sách hội thoại.");
        });
      } catch (Exception e) { problem(e); }
    });
  }

  private void chooseTicketAge() {
    String[] choices = {"Cũ hơn 30 ngày", "Cũ hơn 90 ngày", "Cũ hơn 180 ngày"};
    int[] ages = {30, 90, 180};
    new AlertDialog.Builder(activity).setTitle("Xóa cuộc trò chuyện cũ")
        .setItems(choices, (dialog, selected) -> prepare("Tìm hội thoại cũ", service -> {
          Plan plan = new Plan("Xóa hội thoại " + choices[selected]);
          long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ages[selected]);
          JSONObject index = service.shallowKeys("supportIndex");
          for (String ticket : SupportProtocol.keys(index)) {
            JSONObject item = service.get("supportIndex/" + ticket);
            long last = item.optLong("lastAt");
            if (last <= 0 || last > cutoff) continue;
            addTicket(plan, ticket, item.optString("owner"));
          }
          return plan;
        })).setNegativeButton("Hủy", null).show();
  }

  private static void addTicket(Plan plan, String ticket, String owner) {
    if (!ticket.matches("[0-9]{13}-[0-9a-f]{20}") || !owner.matches("[A-Za-z0-9_-]{1,128}")) return;
    plan.add("supportLogChunks/" + ticket).add("supportAttachments/" + ticket)
        .add("supportMessages/" + ticket).add("supportStates/" + ticket)
        .add("supportUsers/" + owner + "/" + ticket).add("supportIndex/" + ticket);
    plan.records++;
  }

  private Plan pendingRequests(FirebaseRest service) throws Exception {
    Plan plan = new Plan("Xóa yêu cầu chưa có quyết định");
    JSONObject keys = service.shallowKeys("requests");
    for (String uid : SupportProtocol.keys(keys)) {
      if (!uid.matches("[A-Za-z0-9_-]{1,128}")) continue;
      if (service.get("decisions/" + uid).length() == 0) {
        plan.add("requests/" + uid);
        plan.records++;
      }
    }
    return plan;
  }

  private void prepare(String label, Planner planner) {
    work("Đang kiểm tra: " + label + "…", () -> {
      try {
        FirebaseRest service = authorized();
        Plan plan = planner.prepare(service);
        ui(() -> confirm(plan));
      } catch (Exception e) { problem(e); }
    });
  }

  private void confirm(Plan plan) {
    if (plan.paths.isEmpty()) {
      status.setText("Không có dữ liệu phù hợp để xóa.");
      return;
    }
    LinearLayout content = column();
    text(content, plan.title + "\nMục dự kiến ảnh hưởng: " + plan.records
        + "\nĐường dẫn cần dọn: " + plan.paths.size()
        + (plan.originalBytes > 0 ? "\nDung lượng tệp gốc: " + size(plan.originalBytes) : "")
        + "\nKhông thể khôi phục sau khi xóa. Dữ liệu giấy phép và quyền quản trị không nằm trong danh sách xóa.");
    TextView label = text(content, "Nhập XOA để xác nhận xóa vĩnh viễn");
    EditText input = new EditText(activity);
    input.setId(View.generateViewId());
    label.setLabelFor(input.getId());
    input.setSingleLine(true);
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
    content.addView(input);
    AlertDialog warning = new AlertDialog.Builder(activity).setTitle("Xác nhận dọn dữ liệu")
        .setView(content).setNegativeButton("Hủy", null).setPositiveButton("Xóa dữ liệu", null).create();
    warning.setOnShowListener(v -> warning.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
      if (!"XOA".equalsIgnoreCase(input.getText().toString().trim())) {
        input.setError("Nhập XOA để xác nhận.");
        return;
      }
      warning.dismiss();
      execute(plan);
    }));
    warning.show();
    if (warning.getWindow() != null) warning.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
  }

  private void execute(Plan plan) {
    work("Đang dọn " + plan.paths.size() + " đường dẫn…", () -> {
      int deleted = 0;
      try {
        FirebaseRest service = authorized();
        for (String path : plan.paths) {
          service.deleteCleanupData(path);
          deleted++;
          final int done = deleted;
          ui(() -> status.setText("Đã xử lý " + done + "/" + plan.paths.size() + " đường dẫn."));
        }
        ui(() -> status.setText("Đã hoàn tất dọn dữ liệu. Có thể kiểm tra lại thống kê."));
      } catch (Exception error) {
        int completed = deleted;
        ui(() -> status.setText("Đã xóa " + completed + "/" + plan.paths.size()
            + " đường dẫn. Phần còn lại chưa hoàn tất, có thể thử lại. " + safeError(error)));
      }
    });
  }

  private static String safeError(Exception error) {
    if (error instanceof SecurityException || error instanceof IllegalArgumentException
        || error instanceof IllegalStateException || error instanceof java.io.IOException) {
      return error.getMessage() == null ? "Không thể hoàn tất thao tác." : error.getMessage();
    }
    return "Không thể hoàn tất thao tác.";
  }

  private void problem(Exception error) {
    ui(() -> status.setText(safeError(error)));
  }

  private static String size(long bytes) {
    if (bytes >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1048576.0);
    return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
  }
}
