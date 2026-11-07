package com.google.android.accessibility.talkback.licensing;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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

public final class ForumActivity extends Activity {
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private LinearLayout root;
  private TextView status;
  private int generation, screen;
  private String category = "", topic = "";
  private final ThreadLocal<Integer> currentTask = new ThreadLocal<>();

  private interface Task { void run() throws Exception; }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if (!ApkSignatureVerifier.isOfficial(this)) {
      page("Diễn đàn"); status.setText("Bản cài đặt Meridian chưa được xác thực."); return;
    }
    String requested = getIntent().getStringExtra("forumTopic");
    if (ForumProtocol.validId(requested)) thread(requested, null);
    else home();
  }

  @Override public void onBackPressed() {
    if (screen == 2 || screen == 3) topics(category, null);
    else if (screen == 1 || screen == 4 || screen == 5) home();
    else super.onBackPressed();
  }

  private void ui(Runnable action) {
    Integer expected = currentTask.get();
    ui(expected == null ? generation : expected, action);
  }

  private void ui(int target, Runnable action) {
    runOnUiThread(() -> {
      if (!isFinishing() && !isDestroyed() && generation == target) action.run();
    });
  }

  private void work(Task task) { work(task, null); }

  private void work(Task task, Runnable onError) {
    int target = generation;
    IO.execute(() -> {
      currentTask.set(target);
      try { task.run(); }
      catch (Exception error) {
        String message = safeError(error);
        ui(target, () -> {
          status.setText(message);
          if (onError != null) onError.run();
        });
      } finally { currentTask.remove(); }
    });
  }

  private String safeError(Exception error) {
    if (error instanceof IllegalArgumentException || error instanceof IllegalStateException
        || error instanceof java.io.IOException || error instanceof SecurityException) {
      return error.getMessage() == null ? "Không kết nối được diễn đàn." : error.getMessage();
    }
    return "Không kết nối được diễn đàn. Kiểm tra mạng và quy tắc Firebase.";
  }

  private void page(String name) {
    generation++;
    ScrollView scroll = new ScrollView(this);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);
    scroll.addView(root);
    setContentView(scroll);
    TextView heading = text(name);
    heading.setTextSize(22);
    heading.setFocusable(true);
    status = text("");
  }

  private TextView text(String value) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(16);
    view.setPadding(0, 8, 0, 8);
    view.setTextIsSelectable(true);
    root.addView(view);
    return view;
  }

  private Button button(String label, Runnable action) {
    Button view = new Button(this);
    view.setAllCaps(false);
    view.setText(label);
    view.setOnClickListener(v -> action.run());
    root.addView(view);
    return view;
  }

  private EditText input(String label, int limit, boolean multiline) {
    TextView caption = text(label);
    caption.setTextIsSelectable(false);
    EditText value = new EditText(this);
    value.setId(View.generateViewId());
    caption.setLabelFor(value.getId());
    value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
    value.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    if (multiline) value.setMinLines(4);
    root.addView(value);
    return value;
  }

  private String date(long time) {
    return DateFormat.getDateTimeInstance().format(new Date(time));
  }

  private void home() {
    screen = 0; page("Diễn đàn cộng đồng");
    text("Thảo luận và chia sẻ kinh nghiệm sử dụng Meridian. Nội dung diễn đàn hiển thị cho các thành viên.");
    button("Hồ sơ và tên hiển thị", this::profile);
    button("Thông báo của tôi", this::notices);
    button("Tất cả chủ đề", () -> topics("", null));
    List<String> codes = ForumProtocol.categories();
    List<String> names = ForumProtocol.categoryNames();
    for (int i = 0; i < codes.size(); i++) {
      String selected = codes.get(i);
      button(names.get(i), () -> topics(selected, null));
    }
    button("Tạo chủ đề mới", this::composeTopic);
  }

  private void profile() {
    screen = 5; page("Tên hiển thị diễn đàn");
    text("Tên này công khai trên diễn đàn, không lấy từ thông tin đăng ký thiết bị.");
    EditText name = input("Tên hiển thị (2 đến 60 ký tự)", 60, false);
    status.setText("Đang tải hồ sơ…");
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      JSONObject saved = service.get("forumProfiles/" + service.uid());
      ui(() -> {
        name.setText(saved.optString("name"));
        status.setText("Bạn có thể thay đổi tên hiển thị bất cứ lúc nào.");
      });
    });
    button("Lưu tên hiển thị", () -> {
      String value;
      try { value = ForumProtocol.text(name.getText().toString(), 2, 60, "Tên hiển thị"); }
      catch (IllegalArgumentException error) { name.setError(error.getMessage()); return; }
      status.setText("Đang lưu…");
      work(() -> {
        FirebaseRest service = Enrollment.service(this);
        service.put("forumProfiles/" + service.uid(), new JSONObject().put("name", value));
        ui(() -> status.setText("Đã cập nhật tên hiển thị."));
      });
    });
    button("Quay lại diễn đàn", this::home);
  }

  private void topics(String selected, String before) {
    category = selected;
    screen = 1;
    page(selected.isEmpty() ? "Tất cả chủ đề" : ForumProtocol.categoryName(selected));
    button("Tạo chủ đề mới", this::composeTopic);
    button("Làm mới", () -> topics(selected, null));
    button("Chọn chuyên mục khác", this::home);
    status.setText("Đang tải chủ đề…");
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      JSONObject all = service.recent("forumTopics", "$key", 50, before);
      List<String> keys = ForumProtocol.newest(all);
      keys.remove(before);
      String oldest = keys.isEmpty() ? null : keys.get(keys.size() - 1);
      ui(() -> {
        status.setText("Chủ đề mới nhất trước. Chọn một chủ đề để đọc và tham gia.");
        int found = 0;
        for (String id : keys) {
          JSONObject entry = all.optJSONObject(id);
          if (entry == null || (!selected.isEmpty() && !selected.equals(entry.optString("category")))) continue;
          found++;
          String title = entry.optString("title", "Chủ đề") + " · " + entry.optString("name", "Thành viên")
              + " · " + ForumProtocol.categoryName(entry.optString("category"))
              + (entry.optBoolean("locked") ? " · Đã khóa" : "");
          button(title, () -> thread(id, null));
        }
        if (found == 0) text("Không có chủ đề thuộc chuyên mục này trên trang hiện tại.");
        if (oldest != null && keys.size() >= 48) {
          button("Xem chủ đề cũ hơn", () -> topics(selected, oldest));
        }
      });
    });
  }

  private void composeTopic() {
    screen = 3; page("Tạo chủ đề mới");
    text("Cần đặt tên hiển thị trước khi đăng bài. Không đưa thông tin riêng tư vào nội dung công khai.");
    Spinner selected = new Spinner(this);
    selected.setId(View.generateViewId());
    TextView label = text("Chuyên mục");
    label.setLabelFor(selected.getId());
    selected.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
        ForumProtocol.categoryNames()));
    int index = ForumProtocol.categories().indexOf(category);
    selected.setSelection(Math.max(0, index));
    root.addView(selected);
    EditText title = input("Tiêu đề", 140, false);
    EditText body = input("Nội dung chủ đề", 5000, true);
    Button submit = button("Đăng chủ đề", () -> {
      String heading, content;
      try {
        heading = ForumProtocol.text(title.getText().toString(), 4, 140, "Tiêu đề");
        content = ForumProtocol.text(body.getText().toString(), 5, 5000, "Nội dung");
      } catch (IllegalArgumentException error) { status.setText(error.getMessage()); return; }
      submitTopic(selected.getSelectedItemPosition(), heading, content);
    });
    button("Đặt tên hiển thị", this::profile);
  }

  private void submitTopic(int selected, String heading, String content) {
    status.setText("Đang đăng chủ đề…");
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      String uid = service.uid();
      String name = requireProfile(service, uid);
      String id = ForumProtocol.id();
      JSONObject entry = new JSONObject().put("uid", uid).put("name", name)
          .put("category", ForumProtocol.categories().get(selected))
          .put("title", heading).put("body", content).put("locked", false)
          .put("createdAt", FirebaseRest.timestamp());
      service.put("forumTopics/" + id, entry);
      ui(() -> thread(id, null));
    });
  }

  private static String requireProfile(FirebaseRest service, String uid) throws Exception {
    String name = service.get("forumProfiles/" + uid).optString("name").trim();
    if (name.length() < 2) throw new IllegalStateException("Hãy mở Hồ sơ và đặt tên hiển thị trước khi đăng bài.");
    return name;
  }

  private void thread(String id, String before) {
    if (!ForumProtocol.validId(id)) return;
    topic = id;
    screen = 2; page("Đọc chủ đề");
    button("Quay về danh sách chủ đề", () -> topics(category, null));
    button("Làm mới chủ đề", () -> thread(id, null));
    status.setText("Đang tải chủ đề và bình luận…");
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      JSONObject entry = service.get("forumTopics/" + id);
      JSONObject all = service.recent("forumPosts/" + id, "$key", 21, before);
      List<String> keys = ForumProtocol.newest(all);
      keys.remove(before);
      String oldest = keys.isEmpty() ? null : keys.get(keys.size() - 1);
      Collections.reverse(keys);
      ui(() -> {
        if (entry.length() == 0) {
          status.setText("Chủ đề này đã bị xóa hoặc không còn tồn tại.");
          return;
        }
        status.setText(ForumProtocol.categoryName(entry.optString("category")));
        text("Tiêu đề: " + entry.optString("title"));
        text("Người đăng: " + entry.optString("name") + " · " + date(entry.optLong("createdAt")));
        text(entry.optString("body"));
        copyAuthor(entry.optString("uid"));
        boolean locked = entry.optBoolean("locked");
        text(locked ? "Chủ đề đã bị khóa. Không thể viết bình luận mới." : "Chủ đề đang mở để thảo luận.");
        if (!locked) button("Bình luận chủ đề", () -> composePost(id, "", ""));
        text("Bình luận");
        if (keys.isEmpty()) text("Chưa có bình luận trong trang này.");
        for (String post : keys) {
          JSONObject item = all.optJSONObject(post);
          if (item == null) continue;
          String author = item.optString("name", "Thành viên");
          String parent = item.optString("parent");
          text(author + " · " + date(item.optLong("createdAt"))
              + (parent.isEmpty() ? "" : " · Phản hồi một bình luận") + "\n" + item.optString("text"));
          if (!parent.isEmpty()) button("Đọc bình luận gốc", () -> showParent(id, parent));
          if (!locked) {
            button("Trả lời bình luận của " + author, () -> composePost(id, post, ""));
            button("Nhắc đến " + author, () -> composePost(id, "", item.optString("uid")));
          }
          button("Sao chép UID của " + author, () -> copy(item.optString("uid")));
        }
        if (oldest != null && keys.size() >= 19) {
          button("Xem bình luận cũ hơn", () -> thread(id, oldest));
        }
      });
    });
  }

  private void showParent(String id, String parent) {
    work(() -> {
      JSONObject item = Enrollment.service(this).get("forumPosts/" + id + "/" + parent);
      ui(() -> new AlertDialog.Builder(this).setTitle("Bình luận gốc")
          .setMessage(item.length() == 0 ? "Bình luận đã bị xóa." : item.optString("name") + "\n" + item.optString("text"))
          .setPositiveButton("Đóng", null).show());
    });
  }

  private void copyAuthor(String uid) {
    if (ForumProtocol.validUid(uid)) button("Sao chép UID tác giả", () -> copy(uid));
  }

  private void copy(String uid) {
    if (!ForumProtocol.validUid(uid)) return;
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("UID thành viên", uid));
    status.setText("Đã sao chép UID thành viên.");
  }

  private void composePost(String id, String parent, String mention) {
    topic = id;
    screen = 3; page("Viết bình luận");
    if (!parent.isEmpty()) text("Bạn đang trả lời một bình luận.");
    if (!mention.isEmpty()) text("Bạn đang nhắc đến một thành viên của chủ đề.");
    EditText message = input("Nội dung bình luận (tối đa 3000 ký tự)", 3000, true);
    final Button[] send = new Button[1];
    send[0] = button("Gửi bình luận", () -> {
      String content;
      try { content = ForumProtocol.text(message.getText().toString(), 1, 3000, "Bình luận"); }
      catch (IllegalArgumentException error) { status.setText(error.getMessage()); return; }
      send[0].setEnabled(false);
      status.setText("Đang gửi bình luận…");
      work(() -> {
        FirebaseRest service = Enrollment.service(this);
        String uid = service.uid();
        String name = requireProfile(service, uid);
        String post = ForumProtocol.id();
        JSONObject data = new JSONObject().put("uid", uid).put("name", name)
            .put("text", content).put("parent", parent).put("mention", mention)
            .put("createdAt", FirebaseRest.timestamp());
        service.put("forumPosts/" + id + "/" + post, data);
        String error = "";
        try { notifyParticipants(service, uid, id, post, parent, mention); }
        catch (Exception noticeError) { error = "Bình luận đã gửi, nhưng không gửi được tất cả thông báo."; }
        String notice = error;
        ui(() -> {
          thread(id, null);
          if (!notice.isEmpty()) status.setText(notice);
        });
      }, () -> send[0].setEnabled(true));
    });
    button("Quay về chủ đề", () -> thread(id, null));
  }

  private void notifyParticipants(FirebaseRest service, String sender, String id,
      String post, String parent, String mention) throws Exception {
    String recipient;
    if (parent.isEmpty()) recipient = service.get("forumTopics/" + id).optString("uid");
    else recipient = service.get("forumPosts/" + id + "/" + parent).optString("uid");
    if (ForumProtocol.validUid(recipient) && !sender.equals(recipient)) {
      sendNotice(service, recipient, sender, id, post, "reply");
    }
    if (ForumProtocol.validUid(mention) && !sender.equals(mention) && !mention.equals(recipient)) {
      sendNotice(service, mention, sender, id, post, "mention");
    }
  }

  private void sendNotice(FirebaseRest service, String target, String sender,
      String id, String post, String kind) throws Exception {
    JSONObject notice = new JSONObject().put("topic", id).put("post", post)
        .put("from", sender).put("kind", kind).put("lastAt", FirebaseRest.timestamp());
    service.put("forumNotices/" + target + "/" + ForumProtocol.noticeId(id, post), notice);
  }

  private void notices() {
    screen = 4; page("Thông báo diễn đàn");
    button("Làm mới thông báo", this::notices);
    button("Quay về diễn đàn", this::home);
    status.setText("Đang tải thông báo…");
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      String uid = service.uid();
      JSONObject entries = service.activity("forumNotices/" + uid, 40, null);
      ForumNotifications.remember(this, uid, entries);
      List<String> keys = new ArrayList<>();
      for (java.util.Iterator<String> it = entries.keys(); it.hasNext();) keys.add(it.next());
      keys.sort((left, right) -> {
        long a = entries.optJSONObject(left).optLong("lastAt");
        long b = entries.optJSONObject(right).optLong("lastAt");
        int compare = Long.compare(b, a);
        return compare != 0 ? compare : right.compareTo(left);
      });
      ui(() -> {
        status.setText("Thông báo mới nhất trước.");
        if (keys.isEmpty()) text("Chưa có thông báo nào.");
        for (String key : keys) {
          JSONObject entry = entries.optJSONObject(key);
          if (entry == null) continue;
          String id = entry.optString("topic");
          if (!ForumProtocol.validId(id)) continue;
          button(("mention".equals(entry.optString("kind")) ? "Có người nhắc đến bạn" : "Có người trả lời bạn")
              + " · " + date(entry.optLong("lastAt")), () -> thread(id, null));
        }
      });
    });
  }
}
