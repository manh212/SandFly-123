package com.google.android.accessibility.talkback.licensing;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.content.SharedPreferences;
import android.content.Intent;
import androidx.core.view.ViewCompat;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
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
  private String focusAfterRefresh;
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

  private TextView forumItem(String label, Runnable primaryAction) {
    TextView view = text(label);
    view.setFocusable(true);
    if (primaryAction != null) view.setOnClickListener(v -> primaryAction.run());
    return view;
  }

  private void forumAction(View view, String label, Runnable action) {
    ViewCompat.addAccessibilityAction(view, label, (target, arguments) -> {
      action.run();
      return true;
    });
  }

  private void restoreForumFocus(View view, String key) {
    if (!key.equals(focusAfterRefresh)) return;
    focusAfterRefresh = null;
    view.post(() -> {
      if (!isFinishing() && !isDestroyed()) {
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
      }
    });
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


  private SharedPreferences readState() {
    return getSharedPreferences("meridian_forum_read_state", MODE_PRIVATE);
  }

  private int newCommentCount(FirebaseRest service, String topicId, String self,
      long lastReadAt) throws Exception {
    int count = 0;
    String before = null;
    for (;;) {
      JSONObject batch = service.recent("forumPosts/" + topicId, "$key", 100, before);
      List<String> posts = ForumProtocol.newest(batch);
      if (posts.isEmpty()) break;
      boolean finished = false;
      for (String postId : posts) {
        if (postId.equals(before)) continue;
        JSONObject post = batch.optJSONObject(postId);
        if (post == null) continue;
        long postedAt = post.optLong("createdAt");
        if (postedAt <= lastReadAt) {
          finished = true;
          break;
        }
        if (!self.equals(post.optString("uid"))) count++;
      }
      String oldest = posts.get(posts.size() - 1);
      if (finished || posts.size() < 100 || oldest.equals(before)) break;
      before = oldest;
    }
    return count;
  }

  private Map<String,Integer> newMentionCounts(FirebaseRest service, String self,
      List<String> topicIds, SharedPreferences state) throws Exception {
    Map<String,Integer> counts = new HashMap<>();
    long earliest = Long.MAX_VALUE;
    for (String id : topicIds) {
      long lastRead = state.getLong("seen:" + self + ":" + id, 0);
      if (lastRead > 0) earliest = Math.min(earliest, lastRead);
    }
    if (earliest == Long.MAX_VALUE) return counts;
    Set<String> counted = new HashSet<>();
    String before = null;
    for (;;) {
      JSONObject batch = service.recent("forumNotices/" + self, "$key", 100, before);
      List<String> notices = new ArrayList<>();
      for (java.util.Iterator<String> keys = batch.keys(); keys.hasNext();) {
        notices.add(keys.next());
      }
      Collections.sort(notices, Collections.reverseOrder());
      if (notices.isEmpty()) break;
      boolean finished = false;
      for (String key : notices) {
        if (key.equals(before)) continue;
        JSONObject notice = batch.optJSONObject(key);
        if (notice == null) continue;
        long createdAt = notice.optLong("createdAt");
        if (createdAt <= earliest) {
          finished = true;
          break;
        }
        String topicId = notice.optString("topic");
        if (!topicIds.contains(topicId)
            || createdAt <= state.getLong("seen:" + self + ":" + topicId, 0)) continue;
        String kind = notice.optString("kind");
        if (!"mention".equals(kind) && !"reply".equals(kind)) continue;
        if (counted.add(topicId + "/" + notice.optString("post"))) {
          counts.put(topicId, counts.getOrDefault(topicId, 0) + 1);
        }
      }
      String oldest = notices.get(notices.size() - 1);
      if (finished || notices.size() < 100 || oldest.equals(before)) break;
      before = oldest;
    }
    return counts;
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
      boolean owner = service.isOwner();
      boolean staff = owner || service.isModerator();
      ui(target, () -> {
        if (staff) button("Điều hành", this::moderation);
        if (owner) {
          button("Hộp thư thành viên",
              () -> startActivity(new Intent(this, OwnerChatActivity.class)));
        }
      });
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
      String self = service.uid();
      ForumCommunityService roles = new ForumCommunityService(this);
      boolean owner = roles.isOwner();
      boolean staff = owner || roles.isModerator();
      JSONObject bans = owner ? service.get("forumBans") : new JSONObject();
      List<String> labels = new ArrayList<>(), visible = new ArrayList<>();
      List<Boolean> likedStates = new ArrayList<>();
      for (String id : ids) {
        JSONObject record = records.optJSONObject(id);
        if (record == null || (!selected.isEmpty() && !selected.equals(record.optString("category")))) continue;
        String author = displayName(service, record.optString("uid"), names);
        labels.add((pinned.has(id) ? "Đã ghim · " : "") + record.optString("title")
            + " · " + author + " · " + ForumProtocol.categoryName(record.optString("category"))
            + (record.optBoolean("locked") ? " · Đã khóa" : ""));
        visible.add(id);
        likedStates.add(service.get("forumTopicLikes/" + id + "/" + self).length() > 0);
      }
      SharedPreferences state = readState();
      Map<String,Integer> mentionCounts = newMentionCounts(service, self, visible, state);
      for (int i = 0; i < visible.size(); i++) {
        String id = visible.get(i);
        long seenAt = state.getLong("seen:" + self + ":" + id, 0);
        if (seenAt == 0) continue;
        JSONObject record = records.optJSONObject(id);
        int unread = record != null && record.optLong("lastAt") > seenAt
            ? newCommentCount(service, id, self, seenAt) : 0;
        int mentions = mentionCounts.getOrDefault(id, 0);
        if (unread > 0 || mentions > 0) {
          StringBuilder summary = new StringBuilder(labels.get(i));
          if (unread > 0) summary.append(" · ").append(unread).append(" bình luận mới");
          if (mentions > 0) summary.append(" · ").append(mentions).append(" lượt nhắc đến");
          labels.set(i, summary.toString());
        }
      }
      ui(target, () -> {
        for (int i = 0; i < visible.size(); i++) {
          String id = visible.get(i), label = labels.get(i);
          JSONObject record = records.optJSONObject(id);
          if (record == null) continue;
          boolean own = self.equals(record.optString("uid"));
          boolean locked = record.optBoolean("locked");
          boolean liked = likedStates.get(i);
          Button item = button(label, () -> thread(id, null));
          if (!locked) forumAction(item, "Bình luận", () -> composePost(id, "", ""));
          forumAction(item, liked ? "Bỏ thích bài viết" : "Thích bài viết",
              () -> toggleTopicLikeInList(id, selected, before));
          forumAction(item, "Báo cáo bài viết", () -> report(id, ""));
          String authorUid = record.optString("uid");
          if (!self.equals(authorUid)) {
            forumAction(item, "Báo cáo người đăng",
                () -> reportPerson(id, "", authorUid));
          }
          if (staff) {
            boolean pinnedItem = pinned.has(id);
            forumAction(item, pinnedItem ? "Bỏ ghim bài viết" : "Ghim bài viết",
                () -> inlinePin(id, "", !pinnedItem, "list:" + id, selected, before));
            if (!own) {
              String neighbor = i + 1 < visible.size() ? visible.get(i + 1)
                  : i > 0 ? visible.get(i - 1) : "";
              forumAction(item, "Xóa bài viết",
                  () -> removeAsModerator(id, "", neighbor.isEmpty() ? null : "list:" + neighbor,
                      selected, before));
              forumAction(item, locked ? "Mở khóa bài viết" : "Khóa bài viết",
                  () -> changeTopicLockInList(id, !locked, selected, before));
            }
          }
          if (owner && !self.equals(authorUid)) {
            boolean blocked = bans.has(authorUid);
            forumAction(item, blocked ? "Bỏ chặn người đăng" : "Chặn người đăng",
                () -> blockMember(authorUid, !blocked));
          }
          if (own) {
            forumAction(item, "Sửa bài viết",
                () -> edit(id, "", record.optString("title"), record.optString("body")));
            String nextItem = i + 1 < visible.size() ? visible.get(i + 1)
                : i > 0 ? visible.get(i - 1) : "";
            forumAction(item, "Xóa bài viết",
                () -> deleteNow(id, "", nextItem.isEmpty() ? null : "list:" + nextItem));
            if (!record.optBoolean("moderationLocked")) {
              forumAction(item, locked ? "Mở khóa bài viết" : "Khóa bài viết",
                  () -> changeTopicLockInList(id, !locked, selected, before));
            }
          }
          restoreForumFocus(item, "list:" + id);
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
      ForumCommunityService roles = new ForumCommunityService(this);
      boolean owner = roles.isOwner();
      boolean staff = owner || roles.isModerator();
      JSONObject bans = owner ? service.get("forumBans") : new JSONObject();
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
      Map<String,String> replyContext = new HashMap<>();
      boolean topicLiked = service.get("forumTopicLikes/" + id + "/" + self).length() > 0;
      for (String post : ids) {
        JSONObject item = records.optJSONObject(post);
        if (item == null) continue;
        displayName(service, item.optString("uid"), names);
        liked.put(post, service.get("forumPostLikes/" + id + "/" + post + "/" + self).length() > 0);
        String parent = item.optString("parent");
        if (ForumProtocol.validId(parent)) {
          JSONObject original = records.optJSONObject(parent);
          if (original == null) original = service.get("forumPosts/" + id + "/" + parent);
          if (original.length() > 0) {
            String parentName = displayName(service, original.optString("uid"), names);
            String parentText = original.optString("text");
            if (parentText.length() > 180) parentText = parentText.substring(0, 180) + "…";
            replyContext.put(post, " · Trả lời " + parentName + ": " + parentText);
          } else {
            replyContext.put(post, " · Trả lời bình luận không còn hiển thị");
          }
        } else if (ForumProtocol.validUid(item.optString("mention"))) {
          String targetName = displayName(service, item.optString("mention"), names);
          replyContext.put(post, " · Nhắc đến " + targetName);
        }
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
      if (before == null) {
        long seenAt = service.serverTimeMillis();
        if (seenAt > 0) {
          SharedPreferences state = readState();
          long previous = state.getLong("seen:" + self + ":" + id, 0);
          state.edit().putLong("seen:" + self + ":" + id, Math.max(previous, seenAt)).apply();
        }
      }
      ui(target, () -> {
        String articleLabel = ForumProtocol.categoryName(data.optString("category"))
            + " · " + data.optString("title") + (pinned ? " · Đã ghim" : "")
            + (locked ? " · Đã khóa" : "") + " · " + author
            + " · " + date(data.optLong("createdAt")) + " · " + data.optString("body")
            + (data.has("editedAt") ? " · Đã chỉnh sửa" : "");
        TextView article = forumItem(articleLabel,
            locked ? null : () -> composePost(id, "", ""));
        forumAction(article, topicLiked ? "Bỏ thích bài viết" : "Thích bài viết",
            () -> toggleLike(id, ""));
        forumAction(article, "Báo cáo bài viết", () -> report(id, ""));
        String topicAuthorUid = data.optString("uid");
        if (!self.equals(topicAuthorUid)) {
          forumAction(article, "Báo cáo người đăng",
              () -> reportPerson(id, "", topicAuthorUid));
        }
        if (staff) {
          forumAction(article, pinned ? "Bỏ ghim bài viết" : "Ghim bài viết",
              () -> inlinePin(id, "", !pinned, "topic:" + id, "", null));
          if (!own) {
            forumAction(article, "Xóa bài viết",
                () -> removeAsModerator(id, "", null, category, null));
            forumAction(article, locked ? "Mở khóa bài viết" : "Khóa bài viết",
                () -> changeLocked(id, !locked));
          }
          for (int stars = 1; stars <= 5; stars++) {
            int selected = stars;
            forumAction(article, selected + " sao",
                () -> inlineRate(id, selected));
          }
        }
        if (owner && !self.equals(topicAuthorUid)) {
          boolean blocked = bans.has(topicAuthorUid);
          forumAction(article, blocked ? "Bỏ chặn người đăng" : "Chặn người đăng",
              () -> blockMember(topicAuthorUid, !blocked));
        }
        if (own) {
          forumAction(article, "Sửa bài viết",
              () -> edit(id, "", data.optString("title"), data.optString("body")));
          forumAction(article, "Xóa bài viết", () -> deleteNow(id, ""));
          if (!data.optBoolean("moderationLocked")) {
            forumAction(article, locked ? "Mở khóa bài viết" : "Khóa bài viết",
                () -> changeLocked(id, !locked));
          }
        }
        if (!locked) forumAction(article, "Bình luận", () -> composePost(id, "", ""));
        showPoll(id, data, own, voted, article);
        restoreForumFocus(article, "topic:" + id);
        for (String postId : ids) {
          JSONObject post = records.optJSONObject(postId);
          if (post == null) continue;
          String postAuthor = names.get(post.optString("uid"));
          boolean commentPinned = pinnedPosts.has(postId);
          TextView comment = forumItem(
              (commentPinned ? "Đã ghim · " : "") + postAuthor + ": " + post.optString("text")
                  + (post.has("editedAt") ? " · Đã chỉnh sửa" : "")
                  + replyContext.getOrDefault(postId, ""),
              locked ? null : () -> composePost(id, postId, ""));
          String parent = post.optString("parent");
          if (!parent.isEmpty()) {
            forumAction(comment, "Xem bình luận gốc", () -> showParent(id, parent));
          }
          forumAction(comment,
              Boolean.TRUE.equals(liked.get(postId)) ? "Bỏ thích bình luận" : "Thích bình luận",
              () -> toggleLike(id, postId));
          forumAction(comment, "Báo cáo bình luận", () -> report(id, postId));
          String commentAuthorUid = post.optString("uid");
          if (!self.equals(commentAuthorUid)) {
            forumAction(comment, "Báo cáo người bình luận",
                () -> reportPerson(id, postId, commentAuthorUid));
          }
          if (!locked) {
            forumAction(comment, "Trả lời " + postAuthor,
                () -> composePost(id, postId, commentAuthorUid));
          }
          if (staff) {
            forumAction(comment, commentPinned ? "Bỏ ghim bình luận" : "Ghim bình luận",
                () -> inlinePin(id, postId, !commentPinned, "post:" + postId, "", null));
            if (!self.equals(commentAuthorUid)) {
              int index = ids.indexOf(postId);
              String neighbor = index + 1 < ids.size() ? ids.get(index + 1)
                  : index > 0 ? ids.get(index - 1) : "";
              forumAction(comment, "Xóa bình luận",
                  () -> removeAsModerator(id, postId,
                      neighbor.isEmpty() ? "topic:" + id : "post:" + neighbor, "", null));
            }
          }
          if (owner && !self.equals(commentAuthorUid)) {
            boolean blocked = bans.has(commentAuthorUid);
            forumAction(comment, blocked ? "Bỏ chặn người bình luận" : "Chặn người bình luận",
                () -> blockMember(commentAuthorUid, !blocked));
          }
          if (self.equals(commentAuthorUid)) {
            forumAction(comment, "Sửa bình luận",
                () -> edit(id, postId, "", post.optString("text")));
            int index = ids.indexOf(postId);
            String neighbor = index + 1 < ids.size() ? ids.get(index + 1)
                : index > 0 ? ids.get(index - 1) : "";
            forumAction(comment, "Xóa bình luận",
                () -> deleteNow(id, postId,
                    neighbor.isEmpty() ? "topic:" + id : "post:" + neighbor));
          }
          restoreForumFocus(comment, "post:" + postId);
        }
        if (loaded.size() == 20 && oldest != null) button("Bình luận cũ hơn", () -> thread(id, oldest));
      });
    });
  }

  private void showPoll(String id, JSONObject topicData, boolean own, boolean voted,
      TextView article) {
    JSONArray options = topicData.optJSONArray("pollOptions");
    if (options == null) return;
    boolean closed = topicData.optBoolean("pollClosed");
    StringBuilder pollLabel = new StringBuilder("\nBình chọn");
    if (closed) pollLabel.append(" · Đã kết thúc");
    for (int i = 0; i < options.length(); i++) {
      pollLabel.append("\n").append(options.optString(i));
    }
    article.append(pollLabel.toString());
    if (!closed && !voted) {
      for (int i = 0; i < options.length(); i++) {
        int selected = i;
        forumAction(article, "Bỏ phiếu: " + options.optString(i), () -> {
          int expected = generation;
          work(() -> {
            new ForumCommunityService(this).castVote(id, selected);
            ui(expected, () -> {
              focusAfterRefresh = "topic:" + id;
              thread(id, null);
            });
          });
        });
      }
    }
    forumAction(article, "Xem kết quả bình chọn", () -> {
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
        ui(expected, () -> status.setText(result.toString()));
      });
    });
    if (own && !closed) {
      forumAction(article, "Kết thúc bình chọn", () -> {
        int expected = generation;
        work(() -> {
          new ForumCommunityService(this).closePoll(id);
          ui(expected, () -> {
            focusAfterRefresh = "topic:" + id;
            thread(id, null);
          });
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
      ui(expected, () -> {
        if (previousScreen == 7) manageContent(id, "");
        else {
          focusAfterRefresh = "topic:" + id;
          thread(id, null);
        }
      });
    });
  }

  private void changeTopicLockInList(String id, boolean locked, String selected, Long before) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).changeLock(id, locked);
      ui(expected, () -> {
        focusAfterRefresh = "list:" + id;
        topics(selected, before);
      });
    });
  }

  private void toggleTopicLikeInList(String id, String selected, Long before) {
    int expected = generation;
    work(() -> {
      ForumCommunityService client = new ForumCommunityService(this);
      client.setLiked(id, "", !client.didLike(id, ""));
      ui(expected, () -> {
        focusAfterRefresh = "list:" + id;
        topics(selected, before);
      });
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
      ui(expected, () -> {
        focusAfterRefresh = post.isEmpty() ? "topic:" + id : "post:" + post;
        thread(id, null);
      });
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

  private void reportPerson(String topic, String post, String memberUid) {
    String key = "user:" + topic + "/" + post + "/" + memberUid;
    if (submittedReports.contains(key)) {
      status.setText("Đã báo cáo người dùng.");
      return;
    }
    if (!pendingReports.add(key)) return;
    int expected = generation;
    status.setText("Đang báo cáo người dùng…");
    IO.execute(() -> {
      boolean success;
      try {
        new ForumCommunityService(this).reportUser(topic, post, memberUid);
        success = true;
      } catch (Exception error) {
        success = false;
      }
      boolean sent = success;
      runOnUiThread(() -> {
        pendingReports.remove(key);
        if (sent) submittedReports.add(key);
        if (generation == expected && !isFinishing() && !isDestroyed()) {
          status.setText(sent ? "Đã báo cáo người dùng."
              : "Không gửi được báo cáo người dùng.");
        }
      });
    });
  }

  private void blockMember(String memberUid, boolean blocked) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).blockUser(memberUid, blocked);
      ui(expected, () -> status.setText(blocked ? "Đã chặn thành viên."
          : "Đã bỏ chặn thành viên."));
    });
  }

  private void inlinePin(String id, String post, boolean pinned, String focus,
      String selected, Long before) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).setPinned(id, post, pinned);
      ui(expected, () -> {
        focusAfterRefresh = focus;
        if (focus.startsWith("list:")) topics(selected, before);
        else thread(id, null);
      });
    });
  }

  private void inlineRate(String id, int stars) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).rateTopic(id, stars);
      ui(expected, () -> {
        focusAfterRefresh = "topic:" + id;
        thread(id, null);
      });
    });
  }

  private void removeAsModerator(String id, String post, String focus,
      String selected, Long before) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).hide(id, post);
      ui(expected, () -> {
        focusAfterRefresh = focus;
        if (post.isEmpty()) topics(selected, before);
        else thread(id, null);
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
    deleteNow(topic, post, post.isEmpty() ? null : "topic:" + topic);
  }

  private void deleteNow(String topic, String post, String nextFocus) {
    int expected = generation;
    work(() -> {
      new ForumCommunityService(this).softDelete(topic, post);
      ui(expected, () -> {
        focusAfterRefresh = nextFocus;
        if (post.isEmpty()) topics(category, null);
        else thread(topic, null);
      });
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
      if (!client.isModerator() && !client.isOwner()) {
        ui(expected, () -> status.setText("Không thực hiện được."));
        return;
      }
      JSONObject topics = service.get("forumReports");
      boolean owner = client.isOwner();
      List<String> items = new ArrayList<>(), posts = new ArrayList<>(),
          labels = new ArrayList<>(), reportedUsers = new ArrayList<>();
      for (java.util.Iterator<String> keys = topics.keys(); keys.hasNext();) {
        String thread = keys.next();
        JSONObject reports = topics.optJSONObject(thread);
        if (reports == null || !ForumProtocol.validId(thread)) continue;
        for (java.util.Iterator<String> rk = reports.keys(); rk.hasNext();) {
          JSONObject report = reports.optJSONObject(rk.next());
          if (report == null) continue;
          String post = report.optString("post");
          String key = thread + "/" + post + "/" + report.optString("targetUid");
          if (items.contains(key)) continue;
          items.add(key);
          posts.add(post);
          String reported = report.optString("targetUid");
          reportedUsers.add(reported);
          labels.add(ForumProtocol.validUid(reported) ? "Báo cáo người dùng"
              : post.isEmpty() ? "Báo cáo chủ đề" : "Báo cáo bình luận");
        }
      }
      ui(expected, () -> {
        if (items.isEmpty()) status.setText("Không có báo cáo.");
        for (int i = 0; i < items.size(); i++) {
          String[] parts = items.get(i).split("/", -1);
          String id = parts[0], post = posts.get(i);
          String reportedUid = reportedUsers.get(i);
          text(labels.get(i));
          button("Xem chủ đề", () -> thread(id, null));
          button(post.isEmpty() ? "Ẩn chủ đề" : "Ẩn bình luận", () -> hide(id, post));
          if (ForumProtocol.validUid(reportedUid) && owner) {
            button("Chặn người dùng", () -> blockMember(reportedUid, true));
          }
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
