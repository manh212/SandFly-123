package com.google.android.accessibility.talkback.licensing;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ForumActivity extends ComponentActivity {
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private LinearLayout root;
  private TextView status;
  private int generation, screen;
  private String category = "", topic = "";
  private final Set<String> pendingReports = new HashSet<>();
  private final Set<String> submittedReports = new HashSet<>();
  private interface Task { void run() throws Exception; }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { back(); }
    });
    if (!ApkSignatureVerifier.isOfficial(this)) {
      page("Diễn đàn Meridian"); status.setText("Bản Meridian chưa được xác thực."); return;
    }
    String requested = getIntent().getStringExtra("forumTopic");
    if (ForumProtocol.validId(requested)) thread(requested, null);
    else home();
  }

  private void back() {
    if (screen == 2) home();
    else if (screen == 7) moderatorTopics(null);
    else if (screen == 8) moderation();
    else if (screen == 9) manageContent(topic, "");
    else if (screen == 3) topics(category, null);
    else if (screen == 5) thread(topic, null);
    else if (screen == 1 || screen == 4 || screen == 6) home();
    else finish();
  }

  private void ui(int expected, Runnable action) {
    runOnUiThread(() -> {
      if (!isFinishing() && !isDestroyed() && generation == expected) action.run();
    });
  }

  private void work(Task action) {
    int expected = generation;
    IO.execute(() -> {
      try { action.run(); }
      catch (Exception error) {
        ui(expected, () -> status.setText("Không thực hiện được. Thử lại."));
      }
    });
  }

  private void page(String heading) {
    generation++;
    ScrollView scroll = new ScrollView(this);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);
    scroll.addView(root);
    setContentView(scroll);
    TextView label = text(heading);
    label.setTextSize(22);
    label.setFocusable(true);
    status = text("");
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
  }

  private TextView text(String value) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(16);
    view.setTextIsSelectable(false);
    view.setPadding(0, 8, 0, 8);
    root.addView(view);
    return view;
  }

  private Button button(String label, Runnable action) {
    Button view = new Button(this);
    view.setText(label);
    view.setAllCaps(false);
    view.setOnClickListener(v -> action.run());
    root.addView(view);
    return view;
  }

  private EditText input(String label, int max, boolean multiline) {
    TextView title = text(label);
    title.setTextIsSelectable(false);
    EditText field = new EditText(this);
    field.setId(View.generateViewId());
    title.setLabelFor(field.getId());
    field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
    field.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    if (multiline) field.setMinLines(3);
    root.addView(field);
    return field;
  }

  private String date(long millis) {
    return DateFormat.getDateTimeInstance().format(new Date(millis));
  }

  private String displayName(FirebaseRest service, String uid, Map<String,String> names) {
    if (names.containsKey(uid)) return names.get(uid);
    String name = "Thành viên";
    try {
      JSONObject profile = service.get("forumProfiles/" + uid);
      String candidate = profile.optString("name");
      if (!candidate.isEmpty()) name = candidate;
    } catch (Exception ignored) {}
    name += ForumCommunityService.publicBadge(service, uid);
    names.put(uid, name);
    return name;
  }

  private void home() {
    screen = 0;
    page("Diễn đàn cộng đồng");
    button("Hồ sơ", this::profile);
    button("Thông báo", this::notices);
    button("Tất cả chủ đề", () -> topics("", null));
    for (int i = 0; i < ForumProtocol.categories().size(); i++) {
      String code = ForumProtocol.categories().get(i);
      button(ForumProtocol.categoryNames().get(i), () -> topics(code, null));
    }
    button("Tạo chủ đề", this::composeTopic);
    int target = generation;
    work(() -> {
      ForumCommunityService service = new ForumCommunityService(this);
      if (service.isModerator() || Enrollment.service(this).flag("admins/" + service.uid())) {
        ui(target, () -> button("Điều hành", this::moderation));
      }
    });
  }

  private void profile() {
    screen = 1;
    page("Tên hiển thị của tôi");
    EditText field = input("Tên hiển thị", 60, false);
    button("Lưu tên hiển thị", () -> {
      String value;
      try { value = ForumProtocol.text(field.getText().toString(), 2, 60, "Tên hiển thị"); }
      catch (Exception error) { field.setError(error.getMessage()); return; }
      status.setText("Đang lưu tên…");
      work(() -> {
        ForumCommunityService client = new ForumCommunityService(this);
        client.changeName(value);
        int ticket = generation;
        ui(ticket, () -> { status.setText("Đã cập nhật tên hiển thị."); field.setText(value); });
      });
    });
    work(() -> {
      ForumCommunityService client = new ForumCommunityService(this);
      String name = client.currentName();
      int ticket = generation;
      ui(ticket, () -> { if (!name.isEmpty()) field.setText(name); });
    });
  }

  private void topics(String selected, Long before) {
    screen = 2;
    category = selected;
    page(selected.isEmpty() ? "Tất cả chủ đề" : ForumProtocol.categoryName(selected));
    button("Tạo chủ đề", this::composeTopic);
    button("Làm mới", () -> topics(selected, null));
    int target = generation;
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      JSONObject records = service.ranked("forumTopics", 30, before);
      JSONObject pinned = service.get("forumPinnedTopics");
      List<String> recent = ForumProtocol.newest(records);
      int pageSize = recent.size();
      long oldest = Long.MAX_VALUE;
      for (String id : recent) {
        JSONObject record = records.optJSONObject(id);
        if (record != null) oldest = Math.min(oldest, record.optLong("rankAt", record.optLong("lastAt")));
      }
      final long next = oldest;
      if (before == null) {
        for (java.util.Iterator<String> iter = pinned.keys(); iter.hasNext();) {
          String id = iter.next();
          if (!ForumProtocol.validId(id) || records.has(id)) continue;
          JSONObject entry = service.get("forumTopics/" + id);
          if (entry.length() > 0) records.put(id, entry);
        }
      }
      List<String> ids = new ArrayList<>();
      for (java.util.Iterator<String> iter = records.keys(); iter.hasNext();) {
        String id = iter.next();
        if (ForumProtocol.validId(id)) ids.add(id);
      }
      ids.sort((left, right) -> {
        JSONObject a = records.optJSONObject(left), b = records.optJSONObject(right);
        int priority = Boolean.compare(pinned.has(right), pinned.has(left));
        if (priority != 0) return priority;
        int time = Long.compare(b.optLong("rankAt", b.optLong("lastAt")),
            a.optLong("rankAt", a.optLong("lastAt")));
        return time != 0 ? time : right.compareTo(left);
      });
      Map<String,String> names = new HashMap<>();
      List<String> labels = new ArrayList<>(), visible = new ArrayList<>();
      for (String id : ids) {
        JSONObject record = records.optJSONObject(id);
        if (record == null || (!selected.isEmpty() && !selected.equals(record.optString("category")))) continue;
        String author = displayName(service, record.optString("uid"), names);
        labels.add((pinned.has(id) ? "Đã ghim · " : "") + record.optString("title")
            + " · " + author + " · " + ForumProtocol.categoryName(record.optString("category"))
            + (record.optBoolean("locked") ? " · Đã khóa" : ""));
        visible.add(id);
      }
      ui(target, () -> {
        for (int i = 0; i < visible.size(); i++) {
          String id = visible.get(i), label = labels.get(i);
          button(label, () -> thread(id, null));
        }
        if (pageSize == 30 && next > 0) button("Chủ đề cũ hơn", () -> topics(selected, next - 1));
        status.setText(visible.isEmpty() ? "Không có chủ đề." : "");
      });
    });
  }

  private void composeTopic() {
    screen = 4;
    page("Tạo chủ đề mới");
    TextView categoryLabel = text("Chuyên mục");
    Spinner choice = new Spinner(this);
    choice.setId(View.generateViewId());
    categoryLabel.setLabelFor(choice.getId());
    choice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
        ForumProtocol.categoryNames()));
    root.addView(choice);
    EditText title = input("Tiêu đề", 140, false);
    EditText body = input("Nội dung", 5000, true);
    EditText poll = input("Phương án bình chọn", 800, true);
    button("Đăng chủ đề", () -> {
      String heading = title.getText().toString(), content = body.getText().toString();
      List<String> options = new ArrayList<>();
      for (String line : poll.getText().toString().split("\\n")) if (!line.trim().isEmpty()) options.add(line.trim());
      int index = choice.getSelectedItemPosition();
      status.setText("Đang đăng chủ đề…");
      int expected = generation;
      work(() -> {
        ForumCommunityService client = new ForumCommunityService(this);
        String id = client.createTopic(ForumProtocol.categories().get(index), heading, content, options);
        ui(expected, () -> thread(id, null));
      });
    });
    button("Tên hiển thị", this::profile);
  }

  private void thread(String id, String before) {
    if (!ForumProtocol.validId(id)) return;
    screen = 3;
    topic = id;
    page("Chủ đề");
    button("Quay lại", () -> topics(category, null));
    button("Làm mới", () -> thread(id, null));
    int target = generation;
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      String self = service.uid();
      JSONObject data = service.get("forumTopics/" + id);
      if (data.length() == 0) throw new IllegalStateException("Không tìm thấy chủ đề.");
      if (data.has("pollOptions")) {
        try { new ForumCommunityService(this).bindInstallation(); }
        catch (Exception ignored) {}
      }
      JSONObject records = service.recent("forumPosts/" + id, "$key", 20, before);
      List<String> loaded = ForumProtocol.newest(records);
      String oldest = loaded.isEmpty() ? null : loaded.get(loaded.size() - 1);
      JSONObject pinnedPosts = service.get("forumPinnedPosts/" + id);
      if (before == null) {
        for (java.util.Iterator<String> keys = pinnedPosts.keys(); keys.hasNext();) {
          String post = keys.next();
          if (!ForumProtocol.validId(post) || records.has(post)) continue;
          JSONObject record = service.get("forumPosts/" + id + "/" + post);
          if (record.length() > 0) records.put(post, record);
        }
      }
      List<String> ids = ForumProtocol.newest(records);
      ids.sort((a, b) -> {
        int priority = Boolean.compare(pinnedPosts.has(b), pinnedPosts.has(a));
        return priority == 0 ? b.compareTo(a) : priority;
      });
      Map<String,String> names = new HashMap<>();
      String author = displayName(service, data.optString("uid"), names);
      Map<String,Boolean> liked = new HashMap<>();
      boolean topicLiked = service.get("forumTopicLikes/" + id + "/" + self).length() > 0;
      for (String post : ids) {
        JSONObject item = records.optJSONObject(post);
        if (item == null) continue;
        displayName(service, item.optString("uid"), names);
        liked.put(post, service.get("forumPostLikes/" + id + "/" + post + "/" + self).length() > 0);
      }
      String fingerprint = service.get("forumIdentity/" + self).optString("fingerprint");
      boolean hasVoted = service.get("forumPollVotes/" + id + "/" + self).length() > 0;
      if (!hasVoted && fingerprint.matches("[0-9a-f]{64}")) {
        hasVoted = service.get("forumPollGuards/" + id + "/" + fingerprint).length() > 0;
      }
      final boolean voted = hasVoted;
      boolean locked = data.optBoolean("locked");
      boolean own = self.equals(data.optString("uid"));
      boolean pinned = service.flag("forumPinnedTopics/" + id);
      ForumCommunityService client = new ForumCommunityService(this);
      boolean mod = client.isModerator() || service.flag("admins/" + self);
      ui(target, () -> {
        text(ForumProtocol.categoryName(data.optString("category")));
        text(data.optString("title") + (pinned ? " · Đã ghim" : "")
            + (locked ? " · Đã khóa" : ""));
        text(author + " · " + date(data.optLong("createdAt")));
        text(data.optString("body") + (data.has("editedAt") ? " · Đã chỉnh sửa" : ""));
        button(topicLiked ? "Bỏ thích chủ đề" : "Thích chủ đề", () -> toggleLike(id, ""));
        button("Báo cáo chủ đề", () -> report(id, ""));
        if (own) {
          button("Sửa chủ đề", () -> edit(id, "", data.optString("title"), data.optString("body")));
          button("Xóa chủ đề", () -> deleteNow(id, ""));
        }
        if (own && !data.optBoolean("moderationLocked")) {
          button(locked ? "Mở khóa chủ đề" : "Khóa chủ đề", () -> changeLocked(id, !locked));
        }
        if (!locked) button("Bình luận", () -> composePost(id, "", ""));
        showPoll(id, data, own, mod, voted);
        for (String postId : ids) {
          JSONObject post = records.optJSONObject(postId);
          if (post == null) continue;
          String postAuthor = names.get(post.optString("uid"));
          boolean commentPinned = pinnedPosts.has(postId);
          text((commentPinned ? "Đã ghim · " : "") + postAuthor + ": " + post.optString("text")
              + (post.has("editedAt") ? " · Đã chỉnh sửa" : ""));
          String parent = post.optString("parent");
          if (!parent.isEmpty()) button("Xem bình luận gốc", () -> showParent(id, parent));
          button(Boolean.TRUE.equals(liked.get(postId)) ? "Bỏ thích bình luận" : "Thích bình luận",
              () -> toggleLike(id, postId));
          button("Báo cáo bình luận", () -> report(id, postId));
          if (!locked) {
            button("Trả lời bình luận", () -> composePost(id, postId, ""));
            button("Nhắc đến " + postAuthor, () -> composePost(id, "", post.optString("uid")));
          }
          if (self.equals(post.optString("uid"))) {
            button("Sửa bình luận", () -> edit(id, postId, "", post.optString("text")));
            button("Xóa bình luận", () -> deleteNow(id, postId));
          }
        }
        if (loaded.size() == 20 && oldest != null) button("Bình luận cũ hơn", () -> thread(id, oldest));
      });
    });
  }

  private void showPoll(String id, JSONObject topicData, boolean own, boolean mod, boolean voted) {
    JSONArray options = topicData.optJSONArray("pollOptions");
    if (options == null) return;
    text("Bình chọn" + (topicData.optBoolean("pollClosed") ? " · Đã kết thúc" : ""));
    if (!topicData.optBoolean("pollClosed") && !voted) {
      for (int i = 0; i < options.length(); i++) {
        int selected = i;
        button("Bỏ phiếu: " + options.optString(i), () -> {
          int expected = generation;
          work(() -> {
            new ForumCommunityService(this).castVote(id, selected);
            ui(expected, () -> thread(id, null));
          });
        });
      }
    }
    button("Xem kết quả", () -> {
      int expected = generation;
      work(() -> {
        JSONObject votes = Enrollment.service(this).get("forumPollVotes/" + id);
        int[] totals = new int[options.length()];
        for (java.util.Iterator<String> keys = votes.keys(); keys.hasNext();) {
          JSONObject vote = votes.optJSONObject(keys.next());
          int option = vote == null ? -1 : vote.optInt("choice", -1);
          if (option >= 0 && option < totals.length) totals[option]++;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < totals.length; i++) {
          result.append(options.optString(i)).append(": ").append(totals[i]).append(" phiếu\n");
        }
        ui(expected, () -> text(result.toString()));
      });
    });
    if (own && !topicData.optBoolean("pollClosed")) {
      button("Kết thúc bình chọn", () -> {
        int expected = generation;
        work(() -> {
          new ForumCommunityService(this).closePoll(id);
          ui(expected, () -> thread(id, null));
        });
      });
    }
  }

  private void moderatorTopics(Long before) {
    screen = 8;
    page("Quản trị chủ đề");
    button("Điều hành", this::moderation);
    int target = generation;
    work(() -> {
      ForumCommunityService client = new ForumCommunityService(this);
      if (!client.isOwner() && !client.isModerator()) return;
      JSONObject records = Enrollment.service(this).ranked("forumTopics", 30, before);
      List<String> ids = ForumProtocol.newest(records);
      ids.sort((a, b) -> Long.compare(
          records.optJSONObject(b).optLong("rankAt", 0),
          records.optJSONObject(a).optLong("rankAt", 0)));
      long boundary = Long.MAX_VALUE;
      for (String id : ids) boundary = Math.min(boundary,
          records.optJSONObject(id).optLong("rankAt", 0));
      long older = boundary;
      ui(target, () -> {
        for (String id : ids) {
          JSONObject topicData = records.optJSONObject(id);
          if (topicData != null) button(topicData.optString("title"),
              () -> manageContent(id, ""));
        }
        if (ids.size() >= 30) button("Cũ hơn", () -> moderatorTopics(older - 1));
      });
    });
  }

  private void manageContent(String id, String post) {
    topic = id;
    screen = 7;
    page(post.isEmpty() ? "Quản trị chủ đề" : "Quản trị bình luận");
    button("Chủ đề", () -> moderatorTopics(null));
    int target = generation;
    work(() -> {
      ForumCommunityService client = new ForumCommunityService(this);
      if (!client.isOwner() && !client.isModerator()) return;
      FirebaseRest service = Enrollment.service(this);
      String path = post.isEmpty() ? "forumTopics/" + id : "forumPosts/" + id + "/" + post;
      JSONObject record = service.get(path);
      if (record.length() == 0) return;
      boolean pinned = post.isEmpty() ? service.flag("forumPinnedTopics/" + id)
          : service.flag("forumPinnedPosts/" + id + "/" + post);
      int rating = post.isEmpty() ? client.topicRating(id) : 0;
      ui(target, () -> {
        button(pinned ? "Bỏ ghim" : "Ghim", () -> pin(id, post, !pinned));
        button("Ẩn", () -> hide(id, post));
        if (post.isEmpty()) button("Bình luận", () -> manageTopicComments(id));
        if (post.isEmpty()) {
          button(record.optBoolean("locked") ? "Mở khóa" : "Khóa",
              () -> changeLocked(id, !record.optBoolean("locked")));
          if (record.has("pollOptions") && !record.optBoolean("pollClosed")) {
            button("Kết thúc bình chọn", () -> {
              int expected = generation;
              work(() -> {
                new ForumCommunityService(this).closePoll(id);
                ui(expected, () -> manageContent(id, ""));
              });
            });
          }
          text("Đánh giá" + (rating > 0 ? ": " + rating + " sao" : ""));
          for (int stars = 1; stars <= 5; stars++) {
            int selected = stars;
            button(stars + " sao", () -> {
              int expected = generation;
              work(() -> {
                new ForumCommunityService(this).rateTopic(id, selected);
                ui(expected, () -> manageContent(id, ""));
              });
            });
          }
        }
      });
    });
  }

  private void manageTopicComments(String id) {
    screen = 9;
    page("Quản trị bình luận");
    button("Chủ đề", () -> manageContent(id, ""));
    int target = generation;
    work(() -> {
      ForumCommunityService client = new ForumCommunityService(this);
      if (!client.isOwner() && !client.isModerator()) return;
      JSONObject posts = Enrollment.service(this).recent("forumPosts/" + id, "$key", 50, null);
      List<String> ids = ForumProtocol.newest(posts);
      ui(target, () -> {
        for (String post : ids) {
          JSONObject entry = posts.optJSONObject(post);
          if (entry != null) button(entry.optString("text"), () -> manageContent(id, post));
        }
      });
    });
  }

  private void changeLocked(String id, boolean locked) {
    int previousScreen = screen;
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).changeLock(id, locked);
      ui(expected, () -> { if (previousScreen == 7) manageContent(id, ""); else thread(id, null); });
    });
  }

  private void pin(String id, String post, boolean pinned) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).setPinned(id, post, pinned);
      ui(expected, () -> manageContent(id, post));
    });
  }

  private void hide(String id, String post) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).hide(id, post);
      ui(expected, () -> { if (post.isEmpty()) moderatorTopics(null); else manageTopicComments(id); });
    });
  }

  private void toggleLike(String id, String post) {
    int expected = generation;
    work(() -> {
      ForumCommunityService client = new ForumCommunityService(this);
      client.setLiked(id, post, !client.didLike(id, post));
      ui(expected, () -> thread(id, null));
    });
  }

  private void showParent(String topic, String parent) {
    int expected = generation;
    work(() -> {
      JSONObject entry = Enrollment.service(this).get("forumPosts/" + topic + "/" + parent);
      ui(expected, () -> text(entry.length() == 0 ? "Không tìm thấy bình luận." : entry.optString("text")));
    });
  }

  private void composePost(String id, String parent, String mention) {
    screen = 5;
    page("Viết bình luận");
    EditText content = input("Nội dung bình luận", 3000, true);
    button("Gửi bình luận", () -> {
      String value = content.getText().toString();
      status.setText("Đang gửi bình luận…");
      int expected = generation;
      work(() -> {
        ForumCommunityService client = new ForumCommunityService(this);
        client.reply(id, parent, mention, value);
        ui(expected, () -> thread(id, null));
      });
    });
  }

  private void report(String id, String post) {
    String key = id + "/" + post;
    if (submittedReports.contains(key)) {
      status.setText("Đã báo cáo.");
      return;
    }
    if (!pendingReports.add(key)) return;
    int expected = generation;
    status.setText("Đang báo cáo…");
    IO.execute(() -> {
      boolean success;
      try {
        new ForumCommunityService(this).report(id, post);
        success = true;
      } catch (Exception error) {
        success = false;
      }
      boolean sent = success;
      runOnUiThread(() -> {
        pendingReports.remove(key);
        if (sent) submittedReports.add(key);
        if (generation == expected && !isFinishing() && !isDestroyed()) {
          status.setText(sent ? "Đã báo cáo." : "Không gửi được báo cáo. Thử lại.");
        }
      });
    });
  }

  private void edit(String topic, String post, String oldTitle, String previous) {
    screen = 5;
    page(post.isEmpty() ? "Sửa chủ đề" : "Sửa bình luận");
    EditText title = post.isEmpty() ? input("Tiêu đề", 140, false) : null;
    if (title != null) title.setText(oldTitle);
    EditText content = input("Nội dung", post.isEmpty() ? 5000 : 3000, true);
    content.setText(previous);
    button("Lưu", () -> {
      String value = content.getText().toString();
      String heading = title == null ? "" : title.getText().toString();
      int expected = generation;
      work(() -> {
        ForumCommunityService client = new ForumCommunityService(this);
        if (post.isEmpty()) client.updateOwnTopic(topic, heading, value);
        else client.updateOwnComment(topic, post, value);
        ui(expected, () -> thread(topic, null));
      });
    });
  }

  private void deleteNow(String topic, String post) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).softDelete(topic, post);
      ui(expected, () -> { if (post.isEmpty()) topics(category, null); else thread(topic, null); });
    });
  }

  private void moderation() {
    screen = 6;
    page("Điều hành");
    button("Làm mới", this::moderation);
    button("Chủ đề", () -> moderatorTopics(null));
    int expected = generation;
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      ForumCommunityService client = new ForumCommunityService(this);
      if (!client.isModerator() && !service.flag("admins/" + client.uid())) {
        ui(expected, () -> status.setText("Không thực hiện được."));
        return;
      }
      JSONObject topics = service.get("forumReports");
      List<String> items = new ArrayList<>(), posts = new ArrayList<>(), labels = new ArrayList<>();
      for (java.util.Iterator<String> keys = topics.keys(); keys.hasNext();) {
        String thread = keys.next();
        JSONObject reports = topics.optJSONObject(thread);
        if (reports == null || !ForumProtocol.validId(thread)) continue;
        for (java.util.Iterator<String> rk = reports.keys(); rk.hasNext();) {
          JSONObject report = reports.optJSONObject(rk.next());
          if (report == null) continue;
          String post = report.optString("post");
          String key = thread + "/" + post;
          if (items.contains(key)) continue;
          items.add(key);
          posts.add(post);
          labels.add(post.isEmpty() ? "Báo cáo chủ đề" : "Báo cáo bình luận");
        }
      }
      ui(expected, () -> {
        if (items.isEmpty()) status.setText("Không có báo cáo.");
        for (int i = 0; i < items.size(); i++) {
          String[] parts = items.get(i).split("/", -1);
          String id = parts[0], post = posts.get(i);
          text(labels.get(i));
          button("Xem chủ đề", () -> thread(id, null));
          button(post.isEmpty() ? "Ẩn chủ đề" : "Ẩn bình luận", () -> hide(id, post));
        }
      });
    });
  }

  private void notices() {
    screen = 1;
    page("Thông báo");
    button("Làm mới", this::notices);
    int target = generation;
    work(() -> {
      FirebaseRest service = Enrollment.service(this);
      JSONObject records = service.recent("forumNotices/" + service.uid(), "$key", 40, null);
      JSONObject announcements = service.get("forumAnnouncements");
      List<String> keys = new ArrayList<>();
      for (java.util.Iterator<String> iterator = records.keys(); iterator.hasNext();) keys.add(iterator.next());
      Collections.sort(keys, Collections.reverseOrder());
      ui(target, () -> {
        for (String key : keys) {
          JSONObject entry = records.optJSONObject(key);
          if (entry == null || !ForumProtocol.validId(entry.optString("topic"))) continue;
          String id = entry.optString("topic");
          button("mention".equals(entry.optString("kind")) ? "Nhắc đến bạn" : "Trả lời bạn",
              () -> thread(id, null));
        }
        for (java.util.Iterator<String> iterator = announcements.keys(); iterator.hasNext();) {
          String id = iterator.next();
          if (ForumProtocol.validId(id)) button("Thông báo quan trọng", () -> thread(id, null));
        }
        if (keys.isEmpty() && announcements.length() == 0) status.setText("Không có thông báo.");
      });
    });
  }
}
