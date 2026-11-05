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
import io.meridian.licensing.SupportInbox;
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
  private static final ExecutorService LIST_IO = Executors.newFixedThreadPool(2);
  private static final ExecutorService DOWNLOAD_IO = Executors.newCachedThreadPool();
  private static final ExecutorService QUERIES = Executors.newFixedThreadPool(4);
  private static final java.util.concurrent.atomic.AtomicLong LAST_PRUNE = new java.util.concurrent.atomic.AtomicLong();
  private static final int DOWNLOAD = 503;
  private final MainActivity activity;
  private LinearLayout root;
  private TextView status;
  private String uid = "", displayName = "Hỗ trợ";
  private boolean owner, loading, downloading;
  private Button downloadButton;
  private TextView downloadStatus;
  private final android.os.Handler downloadHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private SaveOperation activeSave;
  private final List<InboxView> inboxes = new ArrayList<>();

  private static final class SaveOperation {
    final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
    volatile FirebaseRest service;
    volatile java.io.OutputStream output;
    volatile String stage = "tải";
    SupportLogDownload.Target target;
    java.util.concurrent.Future<?> task;
    Runnable timeout;
  }

  private static final class InboxView {
    final boolean history;
    final String cursor;
    final LinearLayout list;
    final AlertDialog dialog;
    int generation;
    boolean loading, reload;
    volatile FirebaseRest service;
    InboxView(boolean history, String cursor, LinearLayout list, AlertDialog dialog) {
      this.history = history; this.cursor = cursor; this.list = list; this.dialog = dialog;
    }
  }

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
  private void read(Work action) {
    LIST_IO.execute(() -> {
      try { action.run(); }
      catch (Exception e) { message(error(e)); }
    });
  }
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
    while ((e instanceof java.util.concurrent.ExecutionException || e instanceof java.util.concurrent.CompletionException)
        && e.getCause() instanceof Exception) e = (Exception) e.getCause();
    String detail = e.getMessage();
    return detail != null && !detail.trim().isEmpty()
        && (e instanceof IllegalArgumentException || e instanceof IllegalStateException
            || e instanceof java.io.IOException) ? detail : "Không đọc hoặc lưu được phản hồi. Hãy thử lại.";
  }

  private void message(String value) { ui(() -> { status.setText(value); activity.message(value); }); }

  void refreshRole() {
    if (loading) return;
    loading = true;
    LIST_IO.execute(() -> {
      try {
        FirebaseRest service = EnrollmentService.create(activity);
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
        long now = System.currentTimeMillis(), previous = LAST_PRUNE.get();
        if ((previous == 0 || now - previous > 86_400_000L) && LAST_PRUNE.compareAndSet(previous, now)) {
          DOWNLOAD_IO.execute(() -> {
            try {
              java.util.Set<String> retained = new java.util.HashSet<>();
              retained.add(new IssuerStore(activity).supportDownload().optString("file"));
              new IssuerSecrets(activity).logs().prune(retained, now - 86_400_000L);
            } catch (Exception ignored) {}
          });
        }
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
    LinearLayout list = column();
    text(list, "Đang tải phản hồi…");
    AlertDialog dialog = show(history ? "Toàn bộ lịch sử phản hồi" : "Hộp thư phản hồi", list);
    InboxView view = new InboxView(history, before, list, dialog);
    inboxes.add(view);
    dialog.setOnDismissListener(closed -> {
      inboxes.remove(view);
      view.generation++;
      FirebaseRest closing = view.service;
      if (closing != null) DOWNLOAD_IO.execute(closing::cancelRequests);
    });
    refreshInbox(view);
  }

  private void refreshInboxes() {
    ui(() -> { for (InboxView view : new ArrayList<>(inboxes)) refreshInbox(view); });
  }

  private void refreshInbox(InboxView view) {
    view.generation++;
    if (view.loading) { view.reload = true; return; }
    if (!view.dialog.isShowing()) return;
    int generation = view.generation;
    view.loading = true;
    view.list.removeAllViews();
    text(view.list, "Đang tải phản hồi mới nhất…");
    read(() -> {
      try {
        if (!view.dialog.isShowing()) return;
        FirebaseRest service = EnrollmentService.create(activity);
        view.service = service;
        if (!view.dialog.isShowing()) { service.cancelRequests(); return; }
        SupportInbox.Page page = SupportInbox.load(service, view.history, view.cursor);
        ui(() -> {
          if (!view.dialog.isShowing() || view.generation != generation) return;
          view.list.removeAllViews();
          for (String id : page.ids) {
            JSONObject header = page.items.optJSONObject(id);
            JSONObject state = page.states.optJSONObject(id);
            String label = SupportProtocol.stateName(state == null ? "new" : state.optString("state"));
            button(view.list, header.optString("subject") + " · "
                + (header.optString("name").isEmpty() ? "Người dùng" : header.optString("name"))
                + " · " + label + " · " + date(SupportInbox.activityAt(header)), () -> thread(id, null));
          }
          if (page.ids.isEmpty()) text(view.list, "Chưa có phản hồi.");
          if (page.next != null) button(view.list, "Phản hồi cũ hơn", () -> inbox(view.history, page.next));
          button(view.list, "Làm mới", () -> refreshInbox(view));
        });
      } catch (Exception e) {
        ui(() -> {
          if (!view.dialog.isShowing() || generation != view.generation) return;
          view.list.removeAllViews();
          text(view.list, "Chưa tải được phản hồi mới nhất. " + error(e));
          button(view.list, "Thử lại", () -> refreshInbox(view));
        });
      } finally {
        ui(() -> {
          view.service = null;
          view.loading = false;
          if (view.reload && view.dialog.isShowing()) { view.reload = false; refreshInbox(view); }
        });
      }
    });
  }

  private void thread(String id, String before) {
    LinearLayout list = column();
    text(list, "Đang tải hội thoại mới nhất…");
    AlertDialog dialog = show("Chi tiết phản hồi", list);
    java.util.concurrent.atomic.AtomicReference<FirebaseRest> connection = new java.util.concurrent.atomic.AtomicReference<>();
    dialog.setOnDismissListener(closed -> {
      FirebaseRest closing = connection.get();
      if (closing != null) DOWNLOAD_IO.execute(closing::cancelRequests);
    });
    read(() -> {
      try {
        if (!dialog.isShowing()) return;
        FirebaseRest service = EnrollmentService.create(activity);
        connection.set(service);
        if (!dialog.isShowing()) { service.cancelRequests(); return; }
        java.util.concurrent.Future<JSONObject> headerRead = QUERIES.submit(() -> service.get("supportIndex/" + id));
        java.util.concurrent.Future<JSONObject> stateRead = QUERIES.submit(() -> service.get("supportStates/" + id));
        java.util.concurrent.Future<JSONObject> messagesRead = QUERIES.submit(() -> service.recent("supportMessages/" + id, "$key", 11, before));
        JSONObject header = headerRead.get();
        java.util.concurrent.Future<JSONObject> contactRead = QUERIES.submit(() -> service.get("supportContacts/" + header.getString("owner")));
        JSONObject state = stateRead.get(), messages = messagesRead.get();
        long lastSeen = contactRead.get().optLong("lastSeen");
        ui(() -> {
          if (!dialog.isShowing()) return;
          list.removeAllViews();
          dialog.setTitle(header.optString("subject", "Chi tiết phản hồi"));
          text(list, "Tên: " + header.optString("name") + "\nLoại: " + SupportProtocol.typeName(header.optString("type"))
              + "\nMeridian: " + header.optString("appVersion") + "\nAndroid: " + header.optString("androidVersion")
              + "\nLần liên hệ gần nhất: " + date(lastSeen) + "\n" + header.optString("request") + "\nMã phản hồi: " + id);
          TextView stateLabel = text(list, "Trạng thái yêu cầu");
          Spinner selected = new Spinner(activity); selected.setId(android.view.View.generateViewId()); stateLabel.setLabelFor(selected.getId());
          selected.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, SupportProtocol.STATE_NAMES));
          selected.setSelection(Math.max(0, Arrays.asList(SupportProtocol.STATES).indexOf(state.optString("state", "new"))));
          list.addView(selected);
          button(list, "Lưu trạng thái xử lý", () -> {
            String chosenState = SupportProtocol.STATES[selected.getSelectedItemPosition()];
            work(() -> {
              FirebaseRest writer = EnrollmentService.create(activity);
              SupportProtocol.changeState(writer, id, chosenState, displayName);
              refreshInboxes();
              message("Đã lưu trạng thái và cập nhật cho người gửi.");
            });
          });
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
              Button saveLog = button(list, "Tải nhật ký về máy", () -> {});
              TextView saveStatus = text(list, "");
              saveStatus.setVisibility(android.view.View.GONE);
              saveStatus.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
              saveLog.setOnClickListener(v -> download(id, messageId, entry, saveLog, saveStatus));
            }
          }
          if (ids.isEmpty()) text(list, "Chưa có nội dung tin nhắn. Nội dung có thể đang được gửi.");
          button(list, "Trả lời phản hồi", () -> reply(id));
          button(list, "Tải tin nhắn mới nhất", () -> thread(id, null));
          if (oldest != null && ids.size() == 10) button(list, "Tin nhắn cũ hơn", () -> thread(id, oldest));
        });
      } catch (Exception e) {
        ui(() -> {
          if (!dialog.isShowing()) return;
          list.removeAllViews();
          text(list, "Chưa tải được hội thoại mới nhất. " + error(e));
          button(list, "Thử lại", () -> { dialog.dismiss(); thread(id, before); });
        });
      }
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
          SupportProtocol.publishReply(service, ticket, id, displayName, text);
          refreshInboxes();
          ui(() -> { dialog[0].dismiss(); thread(ticket, null); });
        } catch (Exception e) { ui(() -> { send.setEnabled(true); body.setError(error(e)); }); }
      });
    });
    dialog[0] = show("Trả lời người dùng", content);
  }

  private void staff() {
    if (!owner) return;
    read(() -> {
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
    android.content.SharedPreferences preferences = activity.getSharedPreferences("support_log_download", Activity.MODE_PRIVATE);
    return new SupportLogDownload.Store() {
      @Override public JSONObject read() throws Exception { return new JSONObject(preferences.getString("pending", "{}")); }
      @Override public void write(JSONObject value) throws Exception {
        if (!preferences.edit().putString("pending", value.toString()).commit()) throw new java.io.IOException("Không lưu được phiên tải nhật ký.");
      }
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

  private void downloadProgress(String value) {
    ui(() -> {
      if (downloadStatus != null) {
        downloadStatus.setVisibility(android.view.View.VISIBLE);
        downloadStatus.setText(value);
      }
      activity.message(value);
    });
  }

  private void downloadFinished(String value) {
    ui(() -> {
      downloading = false;
      if (downloadButton != null) downloadButton.setEnabled(true);
      downloadProgress(value);
      LinearLayout content = column();
      text(content, value);
      show("Tải nhật ký về máy", content);
    });
  }

  private void download(String ticket, String id, JSONObject entry, Button button, TextView progress) {
    if (downloading) {
      LinearLayout content = column();
      text(content, "Hãy hoàn tất hoặc hủy lần lưu nhật ký đang mở trước.");
      show("Tải nhật ký về máy", content);
      return;
    }
    downloading = true;
    downloadButton = button;
    downloadStatus = progress;
    button.setEnabled(false);
    downloadProgress("Đang mở nơi lưu nhật ký…");
    DOWNLOAD_IO.execute(() -> {
      try {
        SupportLogDownload.prepare(downloadStore(), ticket, id, entry, System.currentTimeMillis());
        ui(() -> {
          Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "Meridian-log-" + id + ".txt");
          intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
              DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
          try { activity.startActivityForResult(intent, DOWNLOAD); }
          catch (RuntimeException e) {
            DOWNLOAD_IO.execute(() -> {
              try { SupportLogDownload.save(downloadStore(), null, System.currentTimeMillis(), null); }
              catch (Exception ignored) {}
              downloadFinished("Không mở được trình chọn nơi lưu tệp. Kiểm tra ứng dụng Tệp trên điện thoại rồi thử lại.");
            });
          }
        });
      } catch (Exception e) { downloadFinished("Không chuẩn bị được nhật ký. " + error(e)); }
    });
  }

  boolean result(int request, int result, Intent data) {
    if (request != DOWNLOAD) return false;
    Uri destination = data == null ? null : data.getData();
    downloading = true;
    boolean cancelled = result != Activity.RESULT_OK || destination == null;
    downloadProgress(cancelled ? "Đã hủy tải nhật ký." : "Đang kết nối để tải nhật ký…");
    SaveOperation operation = new SaveOperation();
    activeSave = operation;
    operation.target = cancelled ? null : new SupportLogDownload.Target() {
      @Override public java.io.OutputStream open() throws Exception {
        if (operation.cancelled.get()) throw new java.io.InterruptedIOException("Đã dừng tải nhật ký.");
        operation.stage = "ghi";
        saveProgress(operation, "Đang ghi tệp nhật ký…");
        java.io.OutputStream stream = activity.getContentResolver().openOutputStream(destination, "wt");
        if (stream == null) throw new java.io.IOException("Không mở được tệp để ghi.");
        operation.output = stream;
        if (operation.cancelled.get()) { stream.close(); throw new java.io.InterruptedIOException("Đã dừng tải nhật ký."); }
        return new java.io.FilterOutputStream(stream) {
          @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
            if (operation.cancelled.get()) throw new java.io.InterruptedIOException("Đã dừng tải nhật ký.");
            out.write(bytes, offset, length);
          }
        };
      }
      @Override public void discard() throws Exception {
        try { if (DocumentsContract.deleteDocument(activity.getContentResolver(), destination)) return; }
        catch (Exception ignored) {}
        try (java.io.OutputStream stream = activity.getContentResolver().openOutputStream(destination, "wt")) {}
      }
    };
    operation.timeout = () -> {
      if (activeSave != operation || !operation.finished.compareAndSet(false, true)) return;
      operation.cancelled.set(true);
      if (operation.task != null) operation.task.cancel(true);
      DOWNLOAD_IO.execute(() -> {
        if (operation.service != null) operation.service.cancelRequests();
        try { if (operation.output != null) operation.output.close(); } catch (Exception ignored) {}
        try { if (operation.target != null) operation.target.discard(); } catch (Exception ignored) {}
      });
      showSaveResult(operation, operation.stage.equals("ghi")
          ? "Ghi tệp quá thời gian chờ. Hãy chọn thư mục Download trong bộ nhớ điện thoại rồi thử lại."
          : "Tải nhật ký quá thời gian chờ. Kiểm tra mạng rồi thử lại.");
    };
    operation.task = DOWNLOAD_IO.submit(() -> {
      try {
        boolean saved = SupportLogDownload.save(downloadStore(), operation.target, System.currentTimeMillis(),
            (ticket, message, entry) -> {
              FirebaseRest service = EnrollmentService.create(activity);
              operation.service = service;
              if (operation.cancelled.get()) { service.cancelRequests(); throw new java.io.InterruptedIOException("Đã dừng tải nhật ký."); }
              SupportLogTransfer.Reader reader = new SupportLogTransfer.Reader() {
                @Override public JSONObject get(String path) throws Exception { return service.get(path); }
                @Override public void cancel() { service.cancelRequests(); }
              };
              int[] previous = {-1};
              return SupportLogTransfer.download(reader, ticket, message, entry, (received, total) -> {
                int percent = (int) (100L * received / total);
                if (percent / 10 != previous[0] / 10 || previous[0] < 0 || received == total) {
                  previous[0] = percent;
                  saveProgress(operation, "Đang tải nhật ký: " + percent + "%");
                }
              }, operation.cancelled::get, 90_000L);
            });
        finishSave(operation, saved ? "Đã tải nhật ký vào tệp bạn chọn." : "Đã hủy tải nhật ký.");
      } catch (Exception e) {
        finishSave(operation, "Không lưu được nhật ký. " + error(e) + " Chọn Tải nhật ký về máy để thử lại.");
      }
    });
    downloadHandler.postDelayed(operation.timeout, 120_000L);
    return true;
  }

  private void saveProgress(SaveOperation operation, String value) {
    ui(() -> { if (activeSave == operation) downloadProgress(value); });
  }

  private void finishSave(SaveOperation operation, String value) {
    if (!operation.finished.compareAndSet(false, true)) return;
    showSaveResult(operation, value);
  }

  private void showSaveResult(SaveOperation operation, String value) {
    downloadHandler.removeCallbacks(operation.timeout);
    ui(() -> {
      if (activeSave != operation) return;
      activeSave = null;
      downloadFinished(value);
    });
  }
}
