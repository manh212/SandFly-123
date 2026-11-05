package com.sandfly.offlinelicenser;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import io.meridian.licensing.CatalogPublisher;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.LicensePolicy;
import io.meridian.licensing.RecoveryProtocol;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

final class AdminPanel {
  private static final String[] FEATURE_IDS = {"ai", "ocr", "translation", "dictation", "scripts"};
  private static final String[] FEATURE_NAMES = {"Trí tuệ nhân tạo", "Nhận dạng chữ OCR", "Dịch văn bản", "Nhập bằng giọng nói", "Kịch bản Lua"};
  private final MainActivity activity;
  private LinearLayout devices;
  private final List<AlertDialog> screens = new ArrayList<>();

  AdminPanel(MainActivity activity) { this.activity = activity; }

  void attach(LinearLayout root) {
    button(root, "Thiết lập dịch vụ đăng ký", this::setup);
    button(root, "Yêu cầu chờ duyệt", this::requests);
    button(root, "Quản lý gói tính năng", this::packages);
    button(root, "Tải danh sách thiết bị từ Firebase", this::restoreDevices);
    devices = new LinearLayout(activity);
    devices.setOrientation(LinearLayout.VERTICAL);
    root.addView(devices);
  }

  void render(JSONObject records) {
    devices.removeAllViews();
    Iterator<String> ids = records.keys();
    while (ids.hasNext()) {
      String id = ids.next();
      JSONObject entry = records.optJSONObject(id);
      if (entry == null) continue;
      String name = entry.optString("name");
      boolean pending = !entry.optBoolean("synced") || !entry.optBoolean("privateSynced");
      button(devices, (name.isEmpty() ? "Thiết bị" : name) + " · " + id.substring(0, 12)
          + (pending ? " · Chờ đồng bộ" : ""), () -> detail(id));
    }
  }

  void clear() { devices.removeAllViews(); }

  private void read(Work work) {
    SyncCoordinator.READS.execute(() -> {
      try { work.run(); }
      catch (Exception e) {
        while (e instanceof java.util.concurrent.ExecutionException && e.getCause() instanceof Exception) e = (Exception) e.getCause();
        activity.message(e.getMessage() == null ? "Chưa tải được dữ liệu mới nhất." : e.getMessage());
      }
    });
  }

  private void task(Work work) {
    SyncCoordinator.WORKER.execute(() -> {
      try { work.run(); }
      catch (Exception e) {
        activity.message(e instanceof IllegalArgumentException || e instanceof IllegalStateException
            || e instanceof java.io.IOException ? e.getMessage() : "Không đọc hoặc lưu được dữ liệu. Hãy thử lại.");
      }
    });
  }

  private interface Work { void run() throws Exception; }
  private void ui(Runnable action) {
    activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) action.run(); });
  }

  private LinearLayout column() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);
    return root;
  }

  private AlertDialog show(String title, LinearLayout root) {
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);
    AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("Đóng", null).create();
    screens.add(dialog);
    dialog.setOnDismissListener(v -> screens.remove(dialog));
    dialog.show();
    return dialog;
  }

  void closeScreens() {
    for (AlertDialog dialog : new ArrayList<>(screens)) dialog.dismiss();
  }

  private void text(LinearLayout root, String value) {
    TextView text = new TextView(activity);
    text.setText(value); text.setTextSize(16); text.setTextIsSelectable(true);
    text.setPadding(0, 8, 0, 8); root.addView(text);
  }

  private Button button(LinearLayout root, String title, Runnable action) {
    Button button = new Button(activity);
    button.setText(title); button.setAllCaps(false); button.setOnClickListener(v -> action.run());
    root.addView(button); return button;
  }

  private EditText input(LinearLayout root, String title, String value, int limit) {
    TextView label = new TextView(activity); label.setText(title); root.addView(label);
    EditText field = new EditText(activity); field.setId(android.view.View.generateViewId());
    label.setLabelFor(field.getId()); field.setText(value);
    field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limit)});
    root.addView(field); return field;
  }

  private Spinner choices(LinearLayout root, String title, List<String> values) {
    TextView label = new TextView(activity); label.setText(title); root.addView(label);
    Spinner spinner = new Spinner(activity); spinner.setId(android.view.View.generateViewId());
    label.setLabelFor(spinner.getId());
    spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, values));
    root.addView(spinner); return spinner;
  }

  private static LicensePolicy policy(JSONObject entry) throws Exception {
    String value = entry.optString("value");
    return value.startsWith("SFLIC3.") ? LicensePolicy.verify(entry.getString("request"), value,
        RecoveryProtocol.publicKey(IssuerKeys.publicKey())) : null;
  }

  private static long revision(JSONObject entry) throws Exception {
    LicensePolicy previous = policy(entry);
    return previous == null ? 1 : previous.revision + 1;
  }

  void issue(String request, String name, String description, String uid) {
    read(() -> {
      RecoveryProtocol.deviceId(request);
      if (name.trim().isEmpty() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Nhập tên người nhận từ 1–80 ký tự.");
      }
      IssuerStore store = new IssuerStore(activity);
      JSONObject templates = store.packages();
      JSONObject existing = store.records().optJSONObject(RecoveryProtocol.deviceId(request));
      JSONObject old = existing == null ? new JSONObject() : existing;
      String details = description.isEmpty() ? old.optString("description") : description;
      String recipient = uid.isEmpty() ? old.optString("uid") : uid;
      long revision = revision(old);
      List<String> ids = new ArrayList<>(), names = new ArrayList<>();
      Iterator<String> keys = templates.keys();
      while (keys.hasNext()) { String id = keys.next(); ids.add(id); names.add(templates.getJSONObject(id).getString("name")); }
      ui(() -> {
        LinearLayout root = column();
        text(root, name + "\n" + details + "\n" + request);
        Spinner selected = choices(root, "Gói tính năng", names);
        Spinner kind = choices(root, "Loại giấy phép", Arrays.asList("Chính thức", "Dùng thử"));
        EditText days = input(root, "Số ngày sử dụng, 0 là không thời hạn", "0", 5);
        days.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        text(root, "Thời hạn mới tính từ lúc ký. Dùng thử phải có thời hạn. Cấp lại sẽ thay giấy phép hiện tại của thiết bị.");
        ScrollView scroll = new ScrollView(activity); scroll.addView(root);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Cấp giấy phép")
            .setView(scroll).setNegativeButton("Hủy", null).setPositiveButton("Xác nhận và ký", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
          try {
            long duration = Long.parseLong(days.getText().toString());
            if (duration < 0 || duration > 36500 || (kind.getSelectedItemPosition() == 1 && duration == 0)) {
              throw new IllegalArgumentException("Chọn 1–36500 ngày cho dùng thử, hoặc 0 cho giấy phép chính thức không thời hạn.");
            }
            String id = ids.get(selected.getSelectedItemPosition());
            JSONArray enabled = templates.getJSONObject(id).getJSONArray("features");
            Set<String> features = new TreeSet<>();
            for (int i = 0; i < enabled.length(); i++) features.add(enabled.getString(i));
            long now = System.currentTimeMillis() / 1000L;
            LicensePolicy next = new LicensePolicy(request, revision, now,
                duration == 0 ? 0 : now + duration * 86400L, "active",
                kind.getSelectedItemPosition() == 1 ? "trial" : "standard", id,
                templates.getJSONObject(id).getString("name"), features);
            dialog.dismiss(); activity.preparePolicy(next, request, name, details, recipient);
          } catch (Exception e) { days.setError(e instanceof NumberFormatException ? "Nhập số ngày hợp lệ." : e.getMessage()); }
        }));
        dialog.show();
      });
    });
  }

  private String features(LicensePolicy policy) {
    List<String> labels = new ArrayList<>();
    for (int i = 0; i < FEATURE_IDS.length; i++) if (policy.features.contains(FEATURE_IDS[i])) labels.add(FEATURE_NAMES[i]);
    return "Đọc màn hình cơ bản" + (labels.isEmpty() ? "" : ", " + String.join(", ", labels));
  }

  private String date(long seconds) {
    return seconds == 0 ? "Không thời hạn" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(seconds * 1000L));
  }

  private void detail(String id) {
    LinearLayout root = column();
    text(root, "Đang tải thông tin thiết bị mới nhất…");
    AlertDialog dialog = show("Chi tiết thiết bị", root);
    java.util.concurrent.atomic.AtomicReference<FirebaseRest> connection = new java.util.concurrent.atomic.AtomicReference<>();
    dialog.setOnDismissListener(closed -> {
      screens.remove(dialog);
      FirebaseRest closing = connection.get();
      if (closing != null) SyncCoordinator.NETWORK.execute(closing::cancelRequests);
    });
    read(() -> {
      try {
        FirebaseRest service = EnrollmentService.create(activity);
        connection.set(service);
        if (!dialog.isShowing()) { service.cancelRequests(); return; }
        java.util.concurrent.Future<JSONObject> contactRead = SyncCoordinator.NETWORK.submit(
            () -> service.matching("supportContacts", "request", "SFREQ2." + id));
        JSONObject remote = service.get("devices/" + id);
        if (remote.length() == 0) throw new IllegalStateException("Thiết bị chưa có dữ liệu trên Firebase. Hoàn tất đồng bộ rồi thử lại.");
        IssuerStore store = new IssuerStore(activity);
        store.importRecord(remote);
        JSONObject entry = store.records().getJSONObject(id);
        LicensePolicy current = policy(entry);
        String description = entry.optString("description");
        String report = "Họ tên: " + entry.optString("name") + "\nMô tả: " + description
            + "\nĐịnh danh: " + id + "\nYêu cầu: " + entry.getString("request")
            + "\nGitHub: " + (entry.optBoolean("synced") ? "Đã lưu" : "Chờ đồng bộ")
            + "\nThông tin riêng và kết quả duyệt: " + (entry.optBoolean("privateSynced") ? "Đã lưu Firebase" : "Chờ lưu Firebase");
        if (current == null) report += "\nGiấy phép cũ · Đầy đủ tính năng · Không thời hạn";
        else report += "\nTrạng thái: " + (current.state.equals("revoked") ? "Đã thu hồi"
            : current.state.equals("suspended") ? "Tạm khóa"
            : current.expiresAt != 0 && current.expiresAt <= System.currentTimeMillis() / 1000L ? "Đã hết hạn" : "Đang hoạt động")
            + "\nLoại: " + (current.kind.equals("trial") ? "Dùng thử" : "Chính thức")
            + "\nGói: " + current.packageName + " (" + current.packageId + ")\nQuyền: " + features(current)
            + "\nNgày cấp: " + date(current.issuedAt) + "\nHết hạn: " + date(current.expiresAt)
            + "\nPhiên bản trạng thái: " + current.revision;
        long lastSeen = 0;
        JSONObject contacts = contactRead.get();
        for (String identity : io.meridian.licensing.SupportProtocol.keys(contacts)) {
          JSONObject contact = contacts.optJSONObject(identity);
          if (contact != null) lastSeen = Math.max(lastSeen, contact.optLong("lastSeen"));
        }
        report += "\nLần liên hệ gần nhất: " + (lastSeen == 0 ? "Chưa có dữ liệu"
            : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(lastSeen)))
            + "\nThời điểm này chỉ cho biết lần nhận dữ liệu gần nhất, không xác nhận thiết bị đang trực tuyến.";
        String info = report;
        ui(() -> {
          if (!dialog.isShowing()) return;
          root.removeAllViews(); text(root, info);
          button(root, "Cấp lại, gia hạn hoặc đổi gói", () -> issue(entry.optString("request"),
              entry.optString("name"), description, entry.optString("uid")));
          if (current == null || current.state.equals("active")) button(root, "Tạm khóa thiết bị", () -> change(entry, current, "suspended"));
          if (current != null && current.state.equals("suspended")) button(root, "Mở lại thiết bị", () -> change(entry, current, "active"));
          if (current == null || !current.state.equals("revoked")) button(root, "Thu hồi giấy phép", () ->
              new AlertDialog.Builder(activity).setTitle("Thu hồi giấy phép?")
                  .setMessage("Thiết bị sẽ nhận lệnh khi có mạng. Muốn sử dụng lại phải cấp giấy phép mới.")
                  .setNegativeButton("Hủy", null).setPositiveButton("Thu hồi", (d, w) -> change(entry, current, "revoked")).show());
          button(root, "Tải trạng thái mới nhất từ GitHub", () -> refreshRemote(entry));
          text(root, entry.optString("value"));
          button(root, "Sao chép giấy phép", () -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Giấy phép", entry.optString("value")));
          });
        });
      } catch (Exception e) {
        ui(() -> {
          if (!dialog.isShowing()) return;
          root.removeAllViews(); text(root, "Chưa tải được thông tin thiết bị mới nhất. Hãy thử lại.");
          button(root, "Thử lại", () -> { dialog.dismiss(); detail(id); });
        });
      }
    });
  }

  private void change(JSONObject entry, LicensePolicy current, String state) {
    try {
      String request = entry.getString("request");
      LicensePolicy next = new LicensePolicy(request, current == null ? 1 : current.revision + 1,
          current == null ? System.currentTimeMillis() / 1000L : current.issuedAt,
          current == null ? 0 : current.expiresAt, state, current == null ? "standard" : current.kind,
          current == null ? "full" : current.packageId, current == null ? "Đầy đủ tính năng" : current.packageName,
          current == null ? LicensePolicy.FEATURES : current.features);
      activity.preparePolicy(next, request, entry.optString("name"), entry.optString("description"), entry.optString("uid"));
    } catch (Exception e) { activity.message(e.getMessage()); }
  }

  private void refreshRemote(JSONObject entry) {
    read(() -> {
      IssuerStore store = new IssuerStore(activity);
      CatalogPublisher.RemoteFile remote = CatalogPublisher.github(GitHubConnection.token(store))
          .get(RecoveryProtocol.recordPath(entry.getString("request")));
      if (remote == null) throw new IllegalStateException("Chưa tìm thấy trạng thái trên GitHub.");
      JSONObject copy = new JSONObject(entry.toString());
      copy.put("value", new JSONObject(remote.content).getString("value"));
      store.importRecord(copy, false);
      SyncCoordinator.schedule(activity.getApplicationContext());
      activity.message("Đã tải trạng thái GitHub. Mở lại chi tiết thiết bị để xem.");
      activity.refreshPanel();
    });
  }

  private void setup() {
    read(() -> {
      FirebaseRest service = EnrollmentService.create(activity);
      String uid = service.uid();
      ui(() -> {
        LinearLayout root = column();
        text(root, "UID điện thoại quản trị:\n" + uid
            + "\nThêm UID này tại admins trong Firebase Console một lần để cho phép quản trị. UID không phải mật khẩu.");
        button(root, "Sao chép UID quản trị", () -> {
          ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
          if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("UID quản trị", uid));
        });
        button(root, "Kiểm tra quyền quản trị", () -> task(() -> {
          EnrollmentService.create(activity).get("requests");
          activity.message("Đã kết nối Firebase và có quyền quản trị.");
        }));
        show("Thiết lập Firebase", root);
      });
    });
  }

  private void requests() {
    read(() -> {
      FirebaseRest service = EnrollmentService.create(activity);
      java.util.concurrent.Future<JSONObject> requestRead = SyncCoordinator.NETWORK.submit(() -> service.get("requests"));
      JSONObject decisions = service.get("decisions"), requests = requestRead.get();
      ui(() -> {
        LinearLayout root = column();
        int pending = 0;
        Iterator<String> keys = requests.keys();
        while (keys.hasNext()) {
          String uid = keys.next();
          if (decisions.has(uid)) continue;
          JSONObject request = requests.optJSONObject(uid);
          if (request == null) continue;
          pending++;
          text(root, "Họ tên: " + request.optString("name") + "\nMô tả: " + request.optString("description")
              + "\n" + request.optString("request"));
          button(root, "Duyệt: " + request.optString("name"), () -> issue(request.optString("request"),
              request.optString("name"), request.optString("description"), uid));
          button(root, "Từ chối: " + request.optString("name"), () -> new AlertDialog.Builder(activity)
              .setTitle("Từ chối yêu cầu?").setNegativeButton("Hủy", null)
              .setPositiveButton("Từ chối", (d, w) -> task(() -> {
                EnrollmentService.create(activity).put("decisions/" + uid, new JSONObject()
                    .put("request", request.getString("request")).put("state", "rejected")
                    .put("policy", "").put("decidedAt", FirebaseRest.timestamp()));
                activity.message("Đã từ chối yêu cầu.");
                ui(this::closeScreens);
              })).show());
        }
        if (pending == 0) text(root, "Không có yêu cầu chờ duyệt.");
        show("Yêu cầu đăng ký", root);
      });
    });
  }

  private void restoreDevices() {
    activity.refreshPanel();
  }

  private void packages() {
    read(() -> {
      JSONObject templates = new IssuerStore(activity).packages();
      ui(() -> {
        LinearLayout root = column();
        text(root, "Thay đổi mẫu chỉ áp dụng cho lần cấp sau. Đọc màn hình cơ bản luôn có trong mọi gói đã cấp.");
        button(root, "Tạo gói mới", () -> editPackage(templates, "pkg_" + java.util.UUID.randomUUID().toString().replace("-", "")));
        Iterator<String> ids = templates.keys();
        while (ids.hasNext()) {
          String id = ids.next();
          button(root, templates.optJSONObject(id).optString("name"), () -> editPackage(templates, id));
        }
        show("Gói tính năng", root);
      });
    });
  }

  private void editPackage(JSONObject templates, String id) {
    JSONObject existing = templates.optJSONObject(id);
    LinearLayout root = column();
    EditText name = input(root, "Tên gói", existing == null ? "" : existing.optString("name"), 80);
    List<CheckBox> fields = new ArrayList<>();
    JSONArray previous = existing == null ? new JSONArray() : existing.optJSONArray("features");
    for (int i = 0; i < FEATURE_IDS.length; i++) {
      CheckBox field = new CheckBox(activity); field.setText(FEATURE_NAMES[i]);
      if (previous != null) for (int n = 0; n < previous.length(); n++) if (FEATURE_IDS[i].equals(previous.optString(n))) field.setChecked(true);
      root.addView(field); fields.add(field);
    }
    ScrollView scroll = new ScrollView(activity); scroll.addView(root);
    AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Sửa gói tính năng").setView(scroll)
        .setNegativeButton("Hủy", null).setPositiveButton("Lưu gói", null).create();
    dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
      String label = name.getText().toString().trim();
      if (label.isEmpty() || label.chars().anyMatch(Character::isISOControl)) { name.setError("Nhập tên gói hợp lệ."); return; }
      JSONArray features = new JSONArray();
      for (int i = 0; i < fields.size(); i++) if (fields.get(i).isChecked()) features.put(FEATURE_IDS[i]);
      task(() -> {
        IssuerStore store = new IssuerStore(activity);
        JSONObject current = store.packages();
        current.put(id, new JSONObject().put("name", label).put("features", features));
        store.packages(current);
        activity.message("Đã lưu mẫu gói. Giấy phép đã cấp giữ nguyên quyền.");
        ui(() -> { dialog.dismiss(); closeScreens(); packages(); });
      });
    }));
    dialog.show();
  }
}
