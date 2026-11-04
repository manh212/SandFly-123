package com.sandfly.offlinelicenser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.SupportProtocol;
import io.meridian.licensing.SupportAttachment;
import io.meridian.licensing.PrivateLogFiles;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

final class SupportDesk {
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final int DOWNLOAD = 503;
  private final MainActivity activity;
  private LinearLayout root;
  private TextView status;
  private String uid = "", displayName = "Hỗ trợ";
  private boolean owner, loading, downloading;

  SupportDesk(MainActivity activity) { this.activity = activity; }

  void attach(LinearLayout parent) {
    root = column(); parent.addView(root);
    status = text(root, "Đang kiểm tra quyền truy cập…");
    button(root, "Sao chép UID điện thoại này", () -> {
      if (uid.isEmpty()) { refreshRole(); return; }
      ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
      if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("UID", uid));
      status.setText("Đã sao chép UID. Quản trị viên dùng UID này để cấp quyền hỗ trợ.");
    });
    button(root, "Kiểm tra quyền truy cập", this::refreshRole);
    refreshRole();
  }

  private interface Work { void run() throws Exception; }
  private void work(Work action) {
    IO.execute(() -> {
      try { action.run(); }
      catch (Exception e) { message(error(e)); }
    });
  }

  private void ui(Runnable action) {
    activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) action.run(); });
  }

  private String error(Exception e) {
    return e instanceof IllegalArgumentException || e instanceof IllegalStateException
        || e instanceof java.io.IOException ? e.getMessage() : "Không đọc hoặc lưu được phản hồi. Hãy thử lại.";
  }

  private void message(String value) { ui(() -> { status.setText(value); activity.message(value); }); }

  void refreshRole() {
    if (loading) return;
    loading = true;
    IO.execute(() -> {
      try {
        FirebaseRest service = EnrollmentService.create(activity);
        java.util.Set<String> retained = new java.util.HashSet<>();
        retained.add(new IssuerStore(activity).supportDownload().optString("file"));
        new IssuerSecrets(activity).logs().prune(retained, System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(1));
        String identity = service.uid(); uid = identity;
        boolean isOwner = service.flag("admins/" + identity);
        JSONObject profile = isOwner ? new JSONObject() : service.get("supportStaff/" + identity);
        boolean staff = isOwner || profile.optBoolean("enabled");
        new IssuerStore(activity).ownerUiCached(isOwner);
        owner = isOwner;
        displayName = isOwner ? "Quản trị viên" : profile.optString("name", "Hỗ trợ");
        ui(() -> {
          loading = false; resetActions();
          status.setText(isOwner ? "Quyền quản trị đầy đủ. UID: " + identity
              : staff ? "Quyền hỗ trợ phản hồi. UID: " + identity
              : "Chưa được cấp quyền. Sao chép UID này và gửi cho quản trị viên: " + identity);
          if (staff) {
            button(root, "Hộp thư phản hồi", () -> inbox(false, null));
            button(root, "Toàn bộ lịch sử phản hồi", () -> inbox(true, null));
          }
          if (isOwner) button(root, "Người hỗ trợ", this::staff);
          activity.supportOwnerMode(isOwner);
        });
      } catch (Exception e) {
        boolean cached = false;
        try { cached = e instanceof java.io.IOException && new IssuerStore(activity).ownerUiCached(); }
        catch (Exception ignored) {}
        boolean allowed = cached;
        ui(() -> {
          loading = false; owner = allowed; resetActions();
          status.setText((uid.isEmpty() ? "" : "UID: " + uid + "\n") + error(e));
          activity.supportOwnerMode(allowed);
        });
      }
    });
  }

  private void resetActions() { while (root.getChildCount() > 3) root.removeViewAt(3); }

  private LinearLayout column() {
    LinearLayout value = new LinearLayout(activity); value.setOrientation(LinearLayout.VERTICAL);
    int p = (int) (16 * activity.getResources().getDisplayMetrics().density);
    value.setPadding(p, p, p, p); return value;
  }

  private TextView text(LinearLayout column, String value) {
    TextView view = new TextView(activity); view.setText(value); view.setTextSize(16);
    view.setTextIsSelectable(true); view.setPadding(0, 8, 0, 8); column.addView(view); return view;
  }

  private Button button(LinearLayout column, String title, Runnable action) {
    Button view = new Button(activity); view.setAllCaps(false); view.setText(title);
    view.setOnClickListener(v -> action.run()); column.addView(view); return view;
  }

  private EditText input(LinearLayout column, String title, String initial, int limit, boolean multiline) {
    TextView label = text(column, title); label.setTextIsSelectable(false);
    EditText field = new EditText(activity); field.setId(android.view.View.generateViewId()); label.setLabelFor(field.getId());
    field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limit)});
    field.setText(initial); column.addView(field); return field;
  }

  private AlertDialog show(String title, LinearLayout column) {
    ScrollView scroll = new ScrollView(activity); scroll.addView(column);
    AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("Đóng", null).create();
    dialog.show();
    if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    return dialog;
  }

  private String date(long value) { return value == 0 ? "Chưa có dữ liệu" : DateFormat.getDateTimeInstance().format(new Date(value)); }

  private void inbox(boolean history, String before) {
    work(() -> {
      FirebaseRest service = EnrollmentService.create(activity);
      JSONObject items = service.recent("supportIndex", history ? "$key" : "lastAt", 21, before);
      JSONObject states = new JSONObject();
      for (String ticket : SupportProtocol.keys(items)) states.put(ticket, service.get("supportStates/" + ticket));
      ui(() -> {
        LinearLayout list = column();
        List<String> keys = SupportProtocol.keys(items); if (before != null) keys.remove(before);
        if (!history) keys.sort((a, b) -> Long.compare(items.optJSONObject(b).optLong("lastAt"), items.optJSONObject(a).optLong("lastAt")));
        int count = Math.min(20, keys.size());
        for (int i = 0; i < count; i++) {
          String id = keys.get(i); JSONObject header = items.optJSONObject(id); if (header == null) continue;
          JSONObject state = states.optJSONObject(id);
          String label = SupportProtocol.stateName(state == null ? "new" : state.optString("state"));
          button(list, header.optString("subject") + " · " + (header.optString("name").isEmpty() ? "Người dùng" : header.optString("name"))
              + " · " + label, () -> thread(id, null));
        }
        if (count == 0) text(list, "Chưa có phản hồi.");
        if (history && count == 20) {
          String cursor = keys.get(count - 1);
          button(list, "Lịch sử cũ hơn", () -> inbox(true, cursor));
        }
        button(list, "Làm mới", () -> inbox(history, before));
        show(history ? "Lịch sử phản hồi" : "Phản hồi mới nhận dữ liệu", list);
      });
    });
  }

  private void thread(String id, String before) {
    work(() -> {
      FirebaseRest service = EnrollmentService.create(activity);
      JSONObject header = service.get("supportIndex/" + id);
      JSONObject state = service.get("supportStates/" + id);
      JSONObject messages = service.recent("supportMessages/" + id, "$key", 11, before);
      long seen = 0;
      try { seen = service.get("supportContacts/" + header.getString("owner")).optLong("lastSeen"); }
      catch (Exception ignored) {}
      long lastSeen = seen;
      ui(() -> {
        LinearLayout list = column();
        text(list, "Tên: " + header.optString("name") + "\nLoại: " + SupportProtocol.typeName(header.optString("type"))
            + "\nMeridian: " + header.optString("appVersion") + "\nAndroid: " + header.optString("androidVersion")
            + "\nLần liên hệ gần nhất: " + date(lastSeen) + "\n" + header.optString("request") + "\nMã phản hồi: " + id);
        TextView stateLabel = text(list, "Trạng thái xử lý nội bộ");
        Spinner selected = new Spinner(activity); selected.setId(android.view.View.generateViewId()); stateLabel.setLabelFor(selected.getId());
        selected.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, SupportProtocol.STATE_NAMES));
        selected.setSelection(Math.max(0, Arrays.asList(SupportProtocol.STATES).indexOf(state.optString("state", "new"))));
        list.addView(selected);
        button(list, "Lưu trạng thái xử lý", () -> work(() -> {
          FirebaseRest writer = EnrollmentService.create(activity);
          writer.put("supportStates/" + id, new JSONObject().put("state", SupportProtocol.STATES[selected.getSelectedItemPosition()])
              .put("by", writer.uid()).put("name", displayName).put("changedAt", FirebaseRest.timestamp()));
          message("Đã lưu trạng thái xử lý nội bộ.");
        }));
        List<String> ids = SupportProtocol.keys(messages); if (before != null) ids.remove(before);
        if (ids.size() > 10) ids = new ArrayList<>(ids.subList(0, 10));
        String oldest = ids.isEmpty() ? null : ids.get(ids.size() - 1); Collections.reverse(ids);
        for (String messageId : ids) {
          JSONObject entry = messages.optJSONObject(messageId); if (entry == null) continue;
          text(list, (entry.optString("kind").equals("staff") ? "Hỗ trợ" : "Người dùng") + ": " + entry.optString("name")
              + " · " + date(entry.optLong("createdAt")) + "\n" + entry.optString("text"));
          if (SupportAttachment.present(entry)) {
            button(list, "Xem nhật ký", () -> work(() -> {
              String file = loadLog(id, messageId, entry); ui(() -> showLog(file));
            }));
            button(list, "Tải nhật ký về máy", () -> download(id, messageId, entry));
          }
        }
        if (ids.isEmpty()) text(list, "Chưa có nội dung tin nhắn. Nội dung có thể đang được gửi.");
        button(list, "Trả lời phản hồi", () -> reply(id));
        button(list, "Tải tin nhắn mới nhất", () -> thread(id, null));
        if (oldest != null && ids.size() == 10) button(list, "Tin nhắn cũ hơn", () -> thread(id, oldest));
        show(header.optString("subject", "Chi tiết phản hồi"), list);
      });
    });
  }

  private void reply(String ticket) {
    LinearLayout content = column(); EditText body = input(content, "Nội dung trả lời", "", 8000, true);
    String id = SupportProtocol.id();
    AlertDialog[] dialog = new AlertDialog[1];
    Button send = button(content, "Gửi trả lời", () -> {});
    send.setOnClickListener(v -> {
      String text = body.getText().toString();
      try { SupportProtocol.text(text, 8000, true); }
      catch (Exception e) { body.setError(e.getMessage()); return; }
      send.setEnabled(false);
      IO.execute(() -> {
        try {
          FirebaseRest service = EnrollmentService.create(activity);
          JSONObject message = SupportProtocol.message(service.uid(), displayName, "staff", text, "");
          String path = "supportMessages/" + ticket + "/" + id;
          JSONObject old = service.get(path);
          if (old.length() == 0) service.put(path, message);
          else if (!SupportProtocol.sameMessage(old, message)) throw new IllegalStateException("Tin nhắn trước đã được gửi. Mở lại trao đổi để gửi tin nhắn mới.");
          service.put("supportIndex/" + ticket + "/lastAt", FirebaseRest.timestamp());
          ui(() -> { dialog[0].dismiss(); thread(ticket, null); });
        } catch (Exception e) { ui(() -> { send.setEnabled(true); body.setError(error(e)); }); }
      });
    });
    dialog[0] = show("Trả lời người dùng", content);
  }

  private void staff() {
    if (!owner) return;
    work(() -> {
      JSONObject people = EnrollmentService.create(activity).get("supportStaff");
      ui(() -> {
        LinearLayout list = column();
        button(list, "Thêm người hỗ trợ", () -> editStaff("", "", true));
        for (String id : SupportProtocol.keys(people)) {
          JSONObject profile = people.optJSONObject(id); if (profile == null) continue;
          button(list, profile.optString("name") + (profile.optBoolean("enabled") ? " · Có quyền hỗ trợ" : " · Đã ngừng quyền"),
              () -> editStaff(id, profile.optString("name"), profile.optBoolean("enabled")));
        }
        show("Người hỗ trợ", list);
      });
    });
  }

  private void editStaff(String identity, String name, boolean enabled) {
    if (!owner) return;
    LinearLayout content = column();
    EditText id = input(content, "UID người hỗ trợ", identity, 128, false); id.setEnabled(identity.isEmpty());
    EditText label = input(content, "Tên hiển thị của người hỗ trợ", name, 80, false);
    android.widget.CheckBox granted = new android.widget.CheckBox(activity); granted.setText("Cho phép hỗ trợ phản hồi"); granted.setChecked(enabled); content.addView(granted);
    AlertDialog[] dialog = new AlertDialog[1];
    button(content, "Lưu quyền hỗ trợ", () -> {
      String value = id.getText().toString().trim(), title = label.getText().toString().trim();
      if (!value.matches("[A-Za-z0-9_-]{1,128}")) { id.setError("UID không hợp lệ."); return; }
      if (title.isEmpty()) { label.setError("Nhập tên hiển thị."); return; }
      boolean allow = granted.isChecked();
      work(() -> {
        EnrollmentService.create(activity).put("supportStaff/" + value, new JSONObject().put("name", title).put("enabled", allow));
        ui(() -> { dialog[0].dismiss(); staff(); });
        message("Đã lưu quyền hỗ trợ. Người này không được cấp hoặc thu hồi giấy phép.");
      });
    });
    dialog[0] = show("Quyền hỗ trợ phản hồi", content);
  }

  private SupportLogDownload.Store downloadStore() {
    return new SupportLogDownload.Store() {
      @Override public JSONObject read() throws Exception { return new IssuerStore(activity).supportDownload(); }
      @Override public void write(JSONObject value) throws Exception { new IssuerStore(activity).supportDownload(value); }
    };
  }

  private String loadLog(String ticket, String id, JSONObject entry) throws Exception {
    PrivateLogFiles files = new IssuerSecrets(activity).logs();
    if (entry.optInt("logBytes") == 0) return files.write(SupportAttachment.exact(entry.getString("log")));
    return files.write(destination -> SupportAttachment.download(EnrollmentService.create(activity), ticket, id, entry, destination));
  }

  private void showLog(String file) {
    LinearLayout content = column(); TextView pageLabel = text(content, "Đang đọc nhật ký…");
    TextView value = text(content, "");
    Button previous = button(content, "Trang nhật ký trước", () -> {}), next = button(content, "Trang nhật ký tiếp theo", () -> {});
    AlertDialog dialog = show("Nhật ký nguyên bản", content);
    int[] page = {0}; Runnable[] load = new Runnable[1];
    load[0] = () -> {
      previous.setEnabled(false); next.setEnabled(false);
      work(() -> {
        String result = new IssuerSecrets(activity).logs().page(file, page[0]);
        ui(() -> {
          if (!dialog.isShowing()) return;
          pageLabel.setText("Trang nhật ký " + (page[0] + 1));
          value.setText(result.substring(0, Math.min(result.length(), PrivateLogFiles.PAGE_CHARS)));
          if (content.getParent() instanceof ScrollView) ((ScrollView) content.getParent()).scrollTo(0, 0);
          previous.setEnabled(page[0] > 0); next.setEnabled(result.length() > PrivateLogFiles.PAGE_CHARS);
        });
      });
    };
    previous.setOnClickListener(v -> { page[0]--; load[0].run(); }); next.setOnClickListener(v -> { page[0]++; load[0].run(); });
    dialog.setOnDismissListener(d -> work(() -> new IssuerSecrets(activity).logs().delete(file))); load[0].run();
  }

  private void download(String ticket, String id, JSONObject entry) {
    if (downloading) { message("Hãy hoàn tất hoặc hủy lần lưu nhật ký đang mở trước."); return; }
    downloading = true;
    IO.execute(() -> {
      try {
        String file = loadLog(ticket, id, entry);
        SupportLogDownload.prepare(downloadStore(), new IssuerSecrets(activity).logs(), file, System.currentTimeMillis());
        ui(() -> {
          Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "Meridian-log-" + id + ".txt");
          intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
              DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
          try { activity.startActivityForResult(intent, DOWNLOAD); }
          catch (RuntimeException e) {
            result(DOWNLOAD, Activity.RESULT_CANCELED, null);
            message("Không mở được trình chọn nơi lưu tệp.");
          }
        });
      } catch (Exception e) { ui(() -> downloading = false); message(error(e)); }
    });
  }

  boolean result(int request, int result, Intent data) {
    if (request != DOWNLOAD) return false;
    Uri destination = data == null ? null : data.getData();
    downloading = true;
    work(() -> {
      try {
        boolean saved = SupportLogDownload.save(downloadStore(), new IssuerSecrets(activity).logs(), result != Activity.RESULT_OK || destination == null
            ? null : () -> activity.getContentResolver().openOutputStream(destination, "w"), System.currentTimeMillis());
        message(saved ? "Đã tải nhật ký vào tệp bạn chọn." : "Đã hủy tải nhật ký.");
      } finally { ui(() -> downloading = false); }
    });
    return true;
  }
}
