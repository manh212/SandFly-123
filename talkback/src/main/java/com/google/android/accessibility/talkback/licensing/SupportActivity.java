package com.google.android.accessibility.talkback.licensing;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.accessibility.talkback.BuildConfig;
import com.google.android.accessibility.talkback.report.DiagnosticsExport;
import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.SupportProtocol;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.json.JSONObject;

public final class SupportActivity extends Activity {
  private LinearLayout root;
  private TextView status;
  private EditText name, subject, description, steps;
  private CheckBox attach;
  private Spinner type;
  private Button send;
  private String snapshot = "", replyTicket;
  private JSONObject replyHeader;
  private boolean editing, preparing, logReady;
  private int screen, generation;
  private final ThreadLocal<Integer> sourceGeneration = new ThreadLocal<>();

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (!ApkSignatureVerifier.isOfficial(this)) {
      page("Nhật ký và báo lỗi"); status.setText("Bản cài đặt chưa được xác thực."); return;
    }
    if (saved != null && saved.getBoolean("editing")) {
      try {
        JSONObject header = saved.containsKey("replyHeader") ? new JSONObject(saved.getString("replyHeader")) : null;
        compose(saved.getBoolean("attached"), saved.getString("replyTicket"), header, saved);
      } catch (Exception e) { home(); }
    } else if (getIntent().getBooleanExtra("compose", false)) {
      compose(getIntent().getBooleanExtra("latestLog", false), null, null, null);
    } else home();
  }

  @Override protected void onResume() {
    super.onResume();
    ActivationReminder.setActivationVisible(true);
    SupportClient.opened(this);
  }

  @Override protected void onPause() {
    ActivationReminder.setActivationVisible(false); super.onPause();
  }

  @Override protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (!editing) return;
    out.putBoolean("editing", true); out.putBoolean("attached", attach.isChecked());
    out.putString("name", name.getText().toString()); out.putString("subject", subject.getText().toString());
    out.putString("description", description.getText().toString()); out.putString("steps", steps == null ? "" : steps.getText().toString());
    out.putInt("type", type.getSelectedItemPosition()); out.putString("snapshot", snapshot);
    out.putString("replyTicket", replyTicket);
    if (replyHeader != null) out.putString("replyHeader", replyHeader.toString());
  }

  private void ui(Runnable action) {
    Integer expected = sourceGeneration.get();
    runOnUiThread(() -> {
      if (!isFinishing() && !isDestroyed() && (expected == null || expected == generation)) action.run();
    });
  }

  private interface Work { void run() throws Exception; }
  private void work(Work action) { work(SupportClient.WORKER, action); }

  private void work(java.util.concurrent.ExecutorService executor, Work action) {
    int expected = generation;
    executor.execute(() -> {
      sourceGeneration.set(expected);
      try { action.run(); }
      catch (Exception e) { ui(() -> status.setText(SupportClient.error(e))); }
      finally { sourceGeneration.remove(); }
    });
  }

  private void page(String title) {
    generation++; editing = false;
    ScrollView scroll = new ScrollView(this);
    root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (20 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding); scroll.addView(root); setContentView(scroll);
    TextView heading = text(title); heading.setTextSize(22);
    status = text("");
  }

  private TextView text(String value) {
    TextView view = new TextView(this); view.setText(value); view.setTextSize(16);
    view.setPadding(0, 10, 0, 10); view.setTextIsSelectable(true); root.addView(view); return view;
  }

  private Button button(String title, Runnable action) {
    Button button = new Button(this); button.setAllCaps(false); button.setText(title);
    button.setOnClickListener(v -> action.run()); root.addView(button); return button;
  }

  private EditText field(String title, String value, int limit, boolean multiline) {
    TextView label = text(title); label.setTextIsSelectable(false);
    EditText input = new EditText(this); input.setId(android.view.View.generateViewId()); label.setLabelFor(input.getId());
    input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)}); input.setText(value); root.addView(input); return input;
  }

  private String date(long value) { return DateFormat.getDateTimeInstance().format(new Date(value)); }

  private void home() {
    page("Nhật ký và báo lỗi"); screen = 0;
    button("Gửi góp ý hoặc báo lỗi", () -> compose(false, null, null, null));
    button("Gửi nhật ký mới nhất", () -> compose(true, null, null, null));
    button("Hàng chờ gửi", this::queue);
    button("Làm mới hộp thư", this::home);
    status.setText("Đang tải hộp thư…");
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      JSONObject items = service.recent("supportUsers/" + service.uid(), "$key", 21, null);
      ui(() -> { if (screen == 0 && !editing) { status.setText("Hộp thư của lần cài đặt này."); tickets(items, null); } });
    });
  }

  private void tickets(JSONObject items, String before) {
    List<String> keys = SupportProtocol.keys(items);
    if (before != null) keys.remove(before);
    int count = Math.min(20, keys.size());
    for (int i = 0; i < count; i++) {
      String id = keys.get(i); JSONObject item = items.optJSONObject(id);
      if (item == null) continue;
      button(SupportProtocol.typeName(item.optString("type")) + ": " + item.optString("subject"), () -> thread(id, null));
    }
    if (count == 0) text("Chưa có phản hồi đã gửi trong hộp thư này.");
    if (count == 20) {
      String cursor = keys.get(count - 1);
      button("Phản hồi cũ hơn", () -> { generation++; work(() -> {
        FirebaseRest service = Enrollment.service(this);
        JSONObject older = service.recent("supportUsers/" + service.uid(), "$key", 21, cursor);
        ui(() -> { page("Phản hồi cũ hơn"); screen = 0; button("Về hộp thư", this::home); tickets(older, cursor); });
      }); });
    }
  }

  private void thread(String id, String before) {
    generation++; status.setText("Đang tải trao đổi…");
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      JSONObject header = service.get("supportIndex/" + id);
      JSONObject messages = service.recent("supportMessages/" + id, "$key", 11, before);
      ui(() -> {
        if (editing) return;
        page(header.optString("subject", "Trao đổi hỗ trợ")); screen = 1;
        text("Mã phản hồi: " + id);
        List<String> ids = SupportProtocol.keys(messages); if (before != null) ids.remove(before);
        if (ids.size() > 10) ids = new ArrayList<>(ids.subList(0, 10));
        String oldest = ids.isEmpty() ? null : ids.get(ids.size() - 1);
        Collections.reverse(ids);
        for (String messageId : ids) {
          JSONObject message = messages.optJSONObject(messageId); if (message == null) continue;
          String author = message.optString("kind").equals("staff") ? "Hỗ trợ: " + message.optString("name") : "Bạn";
          text(author + " · " + date(message.optLong("createdAt")) + "\n" + message.optString("text"));
          if (!message.optString("log").isEmpty()) button("Xem nhật ký đã gửi", () -> preview(message.optString("log")));
        }
        if (ids.isEmpty()) text("Nội dung đang được đồng bộ.");
        button("Trả lời", () -> compose(false, id, header, null));
        button("Tải tin nhắn mới nhất", () -> thread(id, null));
        if (oldest != null && ids.size() == 10) button("Tin nhắn cũ hơn", () -> thread(id, oldest));
        button("Về hộp thư", this::home);
      });
    });
  }

  private void compose(boolean latestLog, String ticket, JSONObject header, Bundle saved) {
    page(ticket == null ? "Gửi góp ý, nhật ký hoặc báo lỗi" : "Trả lời hỗ trợ");
    editing = true; screen = 2; replyTicket = ticket; replyHeader = header;
    snapshot = saved == null ? "" : saved.getString("snapshot", ""); logReady = !snapshot.isEmpty(); preparing = false;
    name = field("Tên hiển thị", saved == null ? "" : saved.getString("name", ""), 80, false);
    TextView kindLabel = text("Loại phản hồi"); kindLabel.setTextIsSelectable(false);
    type = new Spinner(this); type.setId(android.view.View.generateViewId()); kindLabel.setLabelFor(type.getId());
    type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
        new String[]{"Góp ý", "Báo lỗi", "Nhật ký"})); root.addView(type);
    type.setSelection(saved != null ? saved.getInt("type", 0) : latestLog ? 2 : 0);
    subject = field("Tiêu đề", saved != null ? saved.getString("subject", "")
        : header != null ? header.optString("subject") : latestLog ? "Nhật ký mới nhất" : "", 160, false);
    subject.setEnabled(ticket == null); type.setEnabled(ticket == null);
    description = field("Nội dung hoặc mô tả lỗi", saved == null ? "" : saved.getString("description", ""), 6000, true);
    steps = ticket == null ? field("Các bước tái hiện lỗi, nếu biết", saved == null ? "" : saved.getString("steps", ""), 1800, true) : null;
    attach = new CheckBox(this); attach.setText("Đính kèm nhật ký chẩn đoán mới nhất"); root.addView(attach);
    text("Nhật ký gửi trực tiếp chỉ gồm thông tin chẩn đoán đã lọc, tối đa 32.000 ký tự. Bạn có thể xem trước khi gửi.");
    button("Xem trước nhật ký đính kèm", () -> { if (logReady) preview(snapshot); else status.setText("Chọn đính kèm và chờ nhật ký được chuẩn bị."); });
    send = button("Gửi", this::send);
    button("Hàng chờ gửi", this::queue); button("Về hộp thư", this::home);
    attach.setOnCheckedChangeListener((button, checked) -> {
      send.setEnabled(!checked || logReady);
      if (checked && !logReady) prepareLog();
    });
    attach.setChecked(latestLog);
    if (saved == null) work(SupportClient.LOCAL, () -> {
      String previous = new SupportStore(this).name();
      ui(() -> { if (editing && name.getText().length() == 0) name.setText(previous); });
    });
  }

  private void prepareLog() {
    if (preparing) return;
    preparing = true; status.setText("Đang chuẩn bị nhật ký mới nhất…");
    work(() -> {
      try {
        String result = SupportProtocol.diagnostic(DiagnosticsExport.INSTANCE.build(getApplicationContext()));
        ui(() -> {
          snapshot = result; logReady = true; preparing = false; send.setEnabled(true);
          status.setText("Đã chuẩn bị nhật ký. Bạn có thể xem trước rồi bấm Gửi.");
        });
      } catch (Exception e) {
        ui(() -> {
          preparing = false; attach.setChecked(false); send.setEnabled(true);
          status.setText("Chưa đọc được nhật ký. Chọn đính kèm để thử lại.");
        });
      }
    });
  }

  private void send() {
    try {
      String body = description.getText().toString().trim();
      if (steps != null && !steps.getText().toString().trim().isEmpty()) body += "\n\nCác bước tái hiện:\n" + steps.getText().toString().trim();
      String log = attach.isChecked() ? snapshot : "";
      SupportProtocol.text(body, SupportProtocol.TEXT_LIMIT, log.isEmpty());
      String title = SupportProtocol.text(subject.getText().toString(), 160, true);
      JSONObject draft = new JSONObject().put("ticket", replyTicket == null ? SupportProtocol.id() : replyTicket)
          .put("message", SupportProtocol.id()).put("name", name.getText().toString().trim())
          .put("subject", title).put("type", replyHeader == null
              ? new String[]{"feedback", "bug", "log"}[type.getSelectedItemPosition()] : replyHeader.getString("type"))
          .put("request", DeviceLicenseManager.recoveryRequest(this)).put("appVersion", BuildConfig.MERIDIAN_VERSION)
          .put("androidVersion", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
          .put("text", body).put("log", log);
      send.setEnabled(false);
      int expected = generation;
      SupportClient.enqueue(this, draft, (saved, message) -> ui(() -> {
        if (generation != expected) return;
        if (saved) queue(); else send.setEnabled(true);
        status.setText(message);
      }));
    } catch (Exception e) { status.setText(SupportClient.error(e)); }
  }

  private void preview(String value) {
    ScrollView scroll = new ScrollView(this); TextView text = new TextView(this);
    text.setText(value); text.setTextIsSelectable(true); text.setPadding(20, 20, 20, 20); scroll.addView(text);
    new AlertDialog.Builder(this).setTitle("Nhật ký chẩn đoán").setView(scroll).setPositiveButton("Đóng", null).show();
  }

  private void queue() {
    page("Hàng chờ gửi"); screen = 3;
    button("Làm mới hàng chờ", this::queue); button("Về hộp thư", this::home);
    work(SupportClient.LOCAL, () -> {
      JSONObject queue = new SupportStore(this).queue();
      ui(() -> {
        if (screen != 3 || editing) return;
        if (queue.length() == 0) text("Không có nội dung chờ gửi.");
        for (String id : SupportProtocol.keys(queue)) {
          JSONObject item = queue.optJSONObject(id); if (item == null) continue;
          boolean sending = SupportClient.sending(id);
          text(item.optString("subject") + (sending ? " · Đang gửi" : " · Chờ gửi")
              + (item.optString("error").isEmpty() ? "" : "\n" + item.optString("error")));
          button("Xem nội dung chờ gửi", () -> preview(item.optString("text") + "\n\n" + item.optString("log")));
          button("Hủy nội dung chờ gửi", () -> {
            int expected = generation;
            SupportClient.cancel(this, id, message -> ui(() -> {
              if (expected != generation) return;
              queue(); status.setText(message);
            }));
          }).setEnabled(!sending);
        }
      });
    });
  }
}
