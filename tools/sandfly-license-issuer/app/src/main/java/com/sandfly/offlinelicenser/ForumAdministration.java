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
import io.meridian.licensing.LicensePolicy;
import io.meridian.licensing.RecoveryProtocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

final class ForumAdministration {
  private final MainActivity activity;

  ForumAdministration(MainActivity activity) { this.activity = activity; }

  private FirebaseRest authorized() throws Exception {
    FirebaseRest service = EnrollmentService.create(activity);
    if (!service.flag("admins/" + service.uid())) throw new SecurityException("Không thực hiện được.");
    return service;
  }

  private static final class Screen {
    final LinearLayout root;
    final TextView status;
    final AlertDialog dialog;
    Screen(LinearLayout root, TextView status, AlertDialog dialog) {
      this.root = root; this.status = status; this.dialog = dialog;
    }
  }

  private LinearLayout column() {
    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);
    return root;
  }

  private TextView text(LinearLayout layout, String label) {
    TextView value = new TextView(activity);
    value.setText(label);
    value.setTextSize(16);
    value.setTextIsSelectable(true);
    layout.addView(value);
    return value;
  }

  private Button button(LinearLayout layout, String label, Runnable action) {
    Button value = new Button(activity);
    value.setAllCaps(false);
    value.setText(label);
    value.setOnClickListener(v -> action.run());
    layout.addView(value);
    return value;
  }

  private EditText input(LinearLayout layout, String name) {
    TextView title = text(layout, name);
    EditText value = new EditText(activity);
    value.setId(View.generateViewId());
    value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
    title.setLabelFor(value.getId());
    layout.addView(value);
    return value;
  }

  private Screen page(String title) {
    LinearLayout root = column();
    TextView status = text(root, "");
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(root);
    AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title)
        .setView(scroll).setNegativeButton("Đóng", null).create();
    dialog.show();
    return new Screen(root, status, dialog);
  }

  private interface Task { void run(FirebaseRest service) throws Exception; }
  private void run(Screen screen, boolean write, Task task) {
    screen.status.setText("");
    (write ? SyncCoordinator.WORKER : SyncCoordinator.READS).execute(() -> {
      try { task.run(authorized()); }
      catch (Exception error) {
        update(screen, "Không thực hiện được.");
      }
    });
  }

  private void ui(Screen screen, Runnable render) {
    activity.runOnUiThread(() -> {
      if (!activity.isFinishing() && !activity.isDestroyed() && screen.dialog.isShowing()) render.run();
    });
  }

  private void update(Screen screen, String message) {
    ui(screen, () -> screen.status.setText(message));
  }

  void open() {
    Screen screen = page("Quản lý diễn đàn");
    EditText myName = input(screen.root, "Tên quản lý diễn đàn");
    button(screen.root, "Lưu tên", () -> saveOwnerName(screen, myName.getText().toString()));
    run(screen, false, service -> {
      String current = service.get("forumProfiles/" + service.uid()).optString("name");
      if (!current.isEmpty()) ui(screen, () -> myName.setText(current));
    });
    button(screen.root, "Báo cáo", this::reports);
    button(screen.root, "Chủ đề", this::topics);
    button(screen.root, "Nội dung đã ẩn", this::archives);
    button(screen.root, "Bài đã xóa", this::deleted);
    button(screen.root, "Lịch sử chỉnh sửa", this::edits);
    button(screen.root, "Thành viên bị chặn", this::blocked);
    button(screen.root, "Người điều hành", this::moderators);
    button(screen.root, "Lịch sử tên", this::names);
    button(screen.root, "Dọn bản lưu", this::cleanup);
    button(screen.root, "Chuyển dữ liệu diễn đàn cũ", this::migrate);
    run(screen, true, service -> {
      int deleted = purgeExpired(service);
      if (deleted > 0) update(screen, "Đã dọn " + deleted + " bản lưu.");
    });
  }

  private void saveOwnerName(Screen screen, String requestedName) {
    String name;
    try { name = ForumProtocol.text(requestedName, 2, 60, "Tên hiển thị"); }
    catch (Exception error) { update(screen, "Tên không hợp lệ."); return; }
    run(screen, true, service -> {
      String uid = service.uid();
      JSONObject current = service.get("forumProfiles/" + uid);
      String previous = current.optString("name");
      if (previous.equals(name)) return;
      JSONObject updated = new JSONObject().put("name", name);
      if (!previous.isEmpty()) {
        String event = ForumProtocol.id();
        service.put("forumNameHistory/" + uid + "/" + event,
            new JSONObject().put("previous", previous).put("changedAt", FirebaseRest.timestamp()));
        updated.put("changeEvent", event);
      }
      service.put("forumProfiles/" + uid, updated);
      update(screen, "Đã lưu.");
    });
  }

  private void moderators() {
    Screen screen = page("Phân quyền diễn đàn");
    LinearLayout members = column();
    button(screen.root, "Danh sách tất cả thành viên", () -> listMembers(screen, members));
    button(screen.root, "Người điều hành hiện tại",
        () -> currentModerators(screen, members));
    button(screen.root, "Thu hồi quyền Nhà phát triển", () -> revokeDeveloper(screen, members));
    screen.root.addView(members);
    listMembers(screen, members);
  }

  private void listMembers(Screen screen, LinearLayout results) {
    run(screen, false, service -> {
      JSONObject profiles = service.get("forumProfiles");
      JSONObject requests = service.get("requests");
      String developer;
      try {
        developer = service.string("forumDeveloperUid");
      } catch (FirebaseRest.PermissionDenied missingRules) {
        developer = "";
      }
      java.util.Map<String, String> people = new java.util.HashMap<>();
      for (java.util.Iterator<String> it = requests.keys(); it.hasNext();) {
        String uid = it.next();
        JSONObject person = requests.optJSONObject(uid);
        if (ForumProtocol.validUid(uid) && person != null) {
          people.put(uid, person.optString("name", "Thành viên"));
        }
      }
      for (java.util.Iterator<String> it = profiles.keys(); it.hasNext();) {
        String uid = it.next();
        JSONObject person = profiles.optJSONObject(uid);
        if (ForumProtocol.validUid(uid) && person != null) {
          people.put(uid, person.optString("name", people.getOrDefault(uid, "Thành viên")));
        }
      }
      List<String> uids = new ArrayList<>(people.keySet());
      uids.sort((a, b) -> {
        int byName = people.get(a).compareToIgnoreCase(people.get(b));
        return byName != 0 ? byName : a.compareTo(b);
      });
      String currentDeveloper = developer;
      ui(screen, () -> {
        results.removeAllViews();
        text(results, currentDeveloper.isEmpty() ? "Chưa bổ nhiệm Nhà phát triển."
            : "Nhà phát triển hiện tại: " + people.getOrDefault(currentDeveloper, "Thành viên") + " · " + currentDeveloper);
        for (String uid : uids) {
          String name = people.get(uid);
          text(results, name + " · " + uid);
          button(results, "Bổ nhiệm Quản trị viên: " + name, () -> appoint(screen, uid));
          if (!uid.equals(currentDeveloper)) {
            button(results, "Bổ nhiệm Nhà phát triển: " + name,
                () -> appointDeveloper(screen, uid, results));
          }
        }
        if (uids.isEmpty()) screen.status.setText("Chưa có thành viên.");
      });
    });
  }

  private void appointDeveloper(Screen screen, String target, LinearLayout members) {
    run(screen, true, service -> {
      if (!ForumProtocol.validUid(target)
          || service.get("forumProfiles/" + target).length() == 0) {
        update(screen, "Thành viên chưa có hồ sơ diễn đàn.");
        return;
      }
      service.putString("forumDeveloperUid", target);
      JSONObject old = service.get("forumPublicRoles");
      JSONObject changes = new JSONObject();
      for (java.util.Iterator<String> it = old.keys(); it.hasNext();) {
        String uid = it.next();
        JSONObject role = old.optJSONObject(uid);
        if (!target.equals(uid) && role != null
            && "developer".equals(role.optString("badge"))) {
          changes.put("forumPublicRoles/" + uid, JSONObject.NULL);
        }
      }
      changes.put("forumPublicRoles/" + target,
          new JSONObject().put("badge", "developer"));
      service.update(changes);
      update(screen, "Đã bổ nhiệm một Nhà phát triển.");
      listMembers(screen, members);
    });
  }

  private void revokeDeveloper(Screen screen, LinearLayout members) {
    run(screen, true, service -> {
      String selected;
      try {
        selected = service.string("forumDeveloperUid");
      } catch (FirebaseRest.PermissionDenied missingRules) {
        update(screen, "Hãy cập nhật Firebase Rules trước.");
        return;
      }
      if (selected.isEmpty()) {
        update(screen, "Chưa có Nhà phát triển.");
        return;
      }
      JSONObject changes = new JSONObject()
          .put("forumDeveloperUid", JSONObject.NULL);
      JSONObject role = service.get("forumPublicRoles/" + selected);
      if ("developer".equals(role.optString("badge"))) {
        changes.put("forumPublicRoles/" + selected, JSONObject.NULL);
      }
      service.update(changes);
      update(screen, "Đã thu hồi quyền Nhà phát triển.");
      listMembers(screen, members);
    });
  }

  private boolean selectedDeveloper(FirebaseRest service, String uid) throws Exception {
    try {
      return uid.equals(service.string("forumDeveloperUid"));
    } catch (FirebaseRest.PermissionDenied missingRules) {
      return false;
    }
  }

  private void appoint(Screen screen, String uid) {
    run(screen, true, service -> {
      JSONObject request = service.get("requests/" + uid);
      JSONObject decision = service.get("decisions/" + uid);
      if (!"approved".equals(decision.optString("state"))) throw new SecurityException();
      LicensePolicy license = LicensePolicy.verify(request.getString("request"), decision.getString("policy"),
          RecoveryProtocol.publicKey(IssuerKeys.publicKey()));
      if (!license.active(service.serverTimeMillis() / 1000L)) throw new SecurityException();
      JSONObject current = service.get("devices/" + RecoveryProtocol.deviceId(request.getString("request")));
      if (!decision.getString("policy").equals(current.optString("value"))) throw new SecurityException();
      service.put("forumModerators/" + uid, new JSONObject().put("enabled", true)
          .put("device", RecoveryProtocol.deviceId(request.getString("request")))
          .put("policy", decision.getString("policy"))
          .put("expiresAt", license.expiresAt == 0 ? 0 : license.expiresAt * 1000L)
          .put("decisionAt", decision.getLong("decidedAt")));
      if (!selectedDeveloper(service, uid)) {
        service.put("forumPublicRoles/" + uid, new JSONObject().put("badge", "moderator"));
      }
      update(screen, "Đã bổ nhiệm.");
    });
  }

  private void currentModerators(Screen screen, LinearLayout result) {
    run(screen, false, service -> {
      JSONObject staff = service.get("forumModerators");
      List<String> users = new ArrayList<>();
      for (java.util.Iterator<String> it = staff.keys(); it.hasNext();) users.add(it.next());
      Collections.sort(users);
      List<String> names = new ArrayList<>();
      for (String uid : users) {
        names.add(service.get("requests/" + uid).optString("name", "Thành viên") + " · " + uid);
      }
      ui(screen, () -> {
        result.removeAllViews();
        for (int i = 0; i < users.size(); i++) {
          String uid = users.get(i);
          button(result, "Bãi nhiệm: " + names.get(i), () -> run(screen, true, service2 -> {
            service2.deleteForumReaction("forumModerators/" + uid);
            if (!selectedDeveloper(service2, uid)) {
              service2.deleteCleanupData("forumPublicRoles/" + uid);
            }
            update(screen, "Đã bãi nhiệm.");
            currentModerators(screen, result);
          }));
        }
        if (users.isEmpty()) screen.status.setText("Không có người điều hành.");
      });
    });
  }

  private void reports() {
    Screen screen = page("Báo cáo");
    run(screen, false, service -> {
      JSONObject all = service.get("forumReports");
      List<String> topics = new ArrayList<>(), posts = new ArrayList<>(),
          reportedUsers = new ArrayList<>();
      for (java.util.Iterator<String> it = all.keys(); it.hasNext();) {
        String id = it.next();
        JSONObject records = all.optJSONObject(id);
        if (records == null || !ForumProtocol.validId(id)) continue;
        for (java.util.Iterator<String> reportIds = records.keys(); reportIds.hasNext();) {
          JSONObject report = records.optJSONObject(reportIds.next());
          if (report == null) continue;
          String post = report.optString("post");
          String reported = report.optString("targetUid");
          String key = id + "/" + post + "/" + reported;
          if (topics.contains(key)) continue;
          topics.add(key);
          posts.add(post);
          reportedUsers.add(reported);
        }
      }
      ui(screen, () -> {
        for (int i = 0; i < topics.size(); i++) {
          String id = topics.get(i).split("/")[0], post = posts.get(i);
          String reported = reportedUsers.get(i);
          text(screen.root, ForumProtocol.validUid(reported)
              ? "Báo cáo người dùng: " + reported
              : post.isEmpty() ? "Báo cáo chủ đề" : "Báo cáo bình luận");
          button(screen.root, "Xem nội dung", () -> topic(id));
          button(screen.root, "Ẩn nội dung", () -> hide(screen, id, post));
          if (ForumProtocol.validUid(reported)) {
            button(screen.root, "Chặn người dùng", () -> ban(screen, reported, true));
          }
        }
        if (topics.isEmpty()) screen.status.setText("Không có báo cáo.");
      });
    });
  }

  private void rate(Screen screen, String id, int stars) {
    run(screen, true, service -> {
      long boost = ForumProtocol.ratingOffset(stars);
      JSONObject topic = service.get("forumTopics/" + id);
      if (topic.length() == 0) throw new IllegalStateException();
      service.put("forumAdminRatings/" + id, new JSONObject().put("stars", stars)
          .put("by", service.uid()).put("updatedAt", FirebaseRest.timestamp()));
      service.patch("forumTopics/" + id,
          new JSONObject().put("rankAt", topic.getLong("lastAt") + boost));
      update(screen, "Đã đánh giá.");
    });
  }

  private void topics() {
    Screen screen = page("Chủ đề");
    button(screen.root, "Làm mới", this::topics);
    run(screen, false, service -> {
      JSONObject records = service.ranked("forumTopics", 50, null);
      List<String> ids = ForumProtocol.newest(records);
      ui(screen, () -> {
        for (String id : ids) {
          JSONObject item = records.optJSONObject(id);
          if (item != null) button(screen.root, item.optString("title"), () -> topic(id));
        }
        if (ids.isEmpty()) screen.status.setText("Không có chủ đề.");
      });
    });
  }

  private void topic(String id) {
    Screen screen = page("Chủ đề");
    run(screen, false, service -> {
      JSONObject record = service.get("forumTopics/" + id);
      if (record.length() == 0) { update(screen, "Không tìm thấy chủ đề."); return; }
      JSONObject pinned = service.get("forumPinnedTopics");
      JSONObject announcement = service.get("forumAnnouncements/" + id);
      JSONObject comments = service.recent("forumPosts/" + id, "$key", 50, null);
      JSONObject pinnedComments = service.get("forumPinnedPosts/" + id);
      int rating = service.get("forumAdminRatings/" + id).optInt("stars", 0);
      ui(screen, () -> {
        text(screen.root, record.optString("title"));
        text(screen.root, record.optString("body"));
        text(screen.root, "Đánh giá" + (rating > 0 ? ": " + rating + " sao" : ""));
        for (int stars = 1; stars <= 5; stars++) {
          int choice = stars;
          button(screen.root, stars + " sao", () -> rate(screen, id, choice));
        }
        button(screen.root, pinned.has(id) ? "Bỏ ghim chủ đề" : "Ghim chủ đề",
            () -> setPin(screen, id, pinned.has(id)));
        button(screen.root, announcement.length() > 0 ? "Bỏ thông báo quan trọng" : "Thông báo quan trọng",
            () -> announce(screen, id, announcement.length() == 0));
        button(screen.root, record.optBoolean("locked") ? "Mở khóa chủ đề" : "Khóa chủ đề",
            () -> lock(screen, id, !record.optBoolean("locked")));
        button(screen.root, "Lịch sử chỉnh sửa", () -> editHistory(id, "topic"));
        button(screen.root, "Ẩn chủ đề", () -> hide(screen, id, ""));
        button(screen.root, "Xóa vĩnh viễn chủ đề", () -> deleteForever(screen, id, ""));
        button(screen.root, "Chặn người đăng", () -> ban(screen, record.optString("uid"), true));
        for (String post : ForumProtocol.newest(comments)) {
          JSONObject message = comments.optJSONObject(post);
          if (message == null) continue;
          text(screen.root, message.optString("text"));
          button(screen.root, pinnedComments.has(post) ? "Bỏ ghim bình luận" : "Ghim bình luận",
              () -> setPostPin(screen, id, post, pinnedComments.has(post)));
          button(screen.root, "Ẩn bình luận", () -> hide(screen, id, post));
          button(screen.root, "Lịch sử sửa bình luận", () -> editHistory(id, post));
          button(screen.root, "Xóa vĩnh viễn bình luận", () -> deleteForever(screen, id, post));
          button(screen.root, "Chặn người bình luận", () -> ban(screen, message.optString("uid"), true));
        }
      });
    });
  }

  private void setPin(Screen screen, String topic, boolean alreadyPinned) {
    run(screen, true, service -> {
      if (alreadyPinned) service.deleteForumReaction("forumPinnedTopics/" + topic);
      else service.putBoolean("forumPinnedTopics/" + topic, true);
      update(screen, "Đã cập nhật.");
    });
  }

  private void setPostPin(Screen screen, String topic, String post, boolean alreadyPinned) {
    run(screen, true, service -> {
      if (alreadyPinned) service.deleteForumReaction("forumPinnedPosts/" + topic + "/" + post);
      else service.putBoolean("forumPinnedPosts/" + topic + "/" + post, true);
      update(screen, "Đã cập nhật.");
    });
  }

  private void announce(Screen screen, String topic, boolean on) {
    run(screen, true, service -> {
      if (on) {
        service.putBoolean("forumPinnedTopics/" + topic, true);
        service.put("forumAnnouncements/" + topic,
            new JSONObject().put("createdAt", FirebaseRest.timestamp()));
      } else service.deleteCleanupData("forumAnnouncements/" + topic);
      update(screen, "Đã cập nhật.");
    });
  }

  private void lock(Screen screen, String topic, boolean on) {
    run(screen, true, service -> {
      service.patch("forumTopics/" + topic,
          new JSONObject().put("locked", on).put("moderationLocked", on));
      update(screen, "Đã cập nhật.");
    });
  }

  private String target(String topic, String post) {
    return post.isEmpty() ? "forumTopics/" + topic : "forumPosts/" + topic + "/" + post;
  }

  private void hide(Screen screen, String topic, String post) {
    run(screen, true, service -> {
      JSONObject content = service.get(target(topic, post));
      if (content.length() == 0) throw new IllegalStateException();
      content.remove("name");
      content.remove("authorName");
      service.put("forumModerationArchive/" + topic + "/" + (post.isEmpty() ? "topic" : post),
          new JSONObject().put("content", content).put("by", service.uid())
              .put("hiddenAt", FirebaseRest.timestamp()));
      service.deleteForumContent(target(topic, post));
      update(screen, "Đã ẩn.");
    });
  }

  private void archives() {
    Screen screen = page("Nội dung đã ẩn");
    run(screen, false, service -> {
      JSONObject all = service.get("forumModerationArchive");
      List<String> topics = new ArrayList<>(), posts = new ArrayList<>(), labels = new ArrayList<>();
      for (java.util.Iterator<String> it = all.keys(); it.hasNext();) {
        String id = it.next();
        JSONObject records = all.optJSONObject(id);
        if (records == null) continue;
        for (java.util.Iterator<String> keys = records.keys(); keys.hasNext();) {
          String post = keys.next();
          JSONObject item = records.optJSONObject(post);
          if (item == null) continue;
          JSONObject content = item.optJSONObject("content");
          topics.add(id); posts.add(post);
          labels.add("topic".equals(post) ? (content == null ? "Chủ đề" : content.optString("title"))
              : (content == null ? "Bình luận" : content.optString("text")));
        }
      }
      ui(screen, () -> {
        for (int i = 0; i < topics.size(); i++) {
          String id = topics.get(i), post = posts.get(i);
          text(screen.root, labels.get(i));
          button(screen.root, "Khôi phục", () -> restore(screen, id, post));
          button(screen.root, "Xóa vĩnh viễn", () -> purgeArchive(screen, id, post));
        }
        if (topics.isEmpty()) screen.status.setText("Không có nội dung đã ẩn.");
      });
    });
  }

  private void clearReports(FirebaseRest service, String topic, String post) throws Exception {
    JSONObject reports = service.get("forumReports/" + topic);
    for (java.util.Iterator<String> it = reports.keys(); it.hasNext();) {
      String id = it.next();
      JSONObject report = reports.optJSONObject(id);
      if (report != null && post.equals(report.optString("post"))) {
        service.deleteCleanupData("forumReports/" + topic + "/" + id);
      }
    }
  }

  private void restore(Screen screen, String topic, String post) {
    run(screen, true, service -> {
      String source = "forumModerationArchive/" + topic + "/" + post;
      JSONObject stored = service.get(source);
      JSONObject content = stored.optJSONObject("content");
      if (content == null || content.length() == 0) throw new IllegalStateException();
      JSONObject copy = new JSONObject(content.toString());
      copy.remove("name"); copy.remove("authorName");
      service.put(target(topic, "topic".equals(post) ? "" : post), copy);
      service.deleteCleanupData(source);
      clearReports(service, topic, "topic".equals(post) ? "" : post);
      update(screen, "Đã khôi phục.");
    });
  }

  private void purgeArchive(Screen screen, String topic, String post) {
    run(screen, true, service -> {
      service.deleteCleanupData("forumModerationArchive/" + topic + "/" + post);
      clearReports(service, topic, "topic".equals(post) ? "" : post);
      update(screen, "Đã xóa.");
    });
  }

  private void deleteForever(Screen screen, String topic, String post) {
    run(screen, true, service -> {
      service.deleteForumContent(target(topic, post));
      if (!post.isEmpty()) clearReports(service, topic, post);
      if (post.isEmpty()) {
        for (String path : new String[]{"forumPosts/", "forumTopicLikes/", "forumPostLikes/",
            "forumPollVotes/", "forumPollGuards/", "forumPinnedPosts/", "forumReports/", "forumPinnedTopics/",
            "forumAnnouncements/"}) {
          try { service.deleteCleanupData(path + topic); } catch (Exception ignored) {}
        }
      } else {
        try { service.deleteCleanupData("forumPostLikes/" + topic + "/" + post); }
        catch (Exception ignored) {}
        try { service.deleteCleanupData("forumPinnedPosts/" + topic + "/" + post); }
        catch (Exception ignored) {}
      }
      update(screen, "Đã xóa.");
    });
  }

  private void ban(Screen screen, String uid, boolean blocked) {
    if (!ForumProtocol.validUid(uid)) { update(screen, "Không thực hiện được."); return; }
    run(screen, true, service -> {
      JSONObject identity = service.get("forumIdentity/" + uid);
      String fingerprint = identity.optString("fingerprint");
      if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalStateException();
      JSONObject changes = new JSONObject().put("forumBans/" + uid, blocked ? true : JSONObject.NULL)
          .put("forumDeviceBans/" + fingerprint, blocked ? true : JSONObject.NULL);
      if (!blocked) {
        JSONObject identities = service.get("forumIdentity");
        for (java.util.Iterator<String> it = identities.keys(); it.hasNext();) {
          String member = it.next();
          JSONObject record = identities.optJSONObject(member);
          if (record != null && fingerprint.equals(record.optString("fingerprint"))) {
            changes.put("forumBans/" + member, JSONObject.NULL);
          }
        }
      }
      service.update(changes);
      update(screen, blocked ? "Đã chặn." : "Đã bỏ chặn.");
    });
  }

  private void blocked() {
    Screen screen = page("Thành viên bị chặn");
    run(screen, false, service -> {
      JSONObject bans = service.get("forumBans");
      List<String> users = new ArrayList<>(), names = new ArrayList<>();
      for (java.util.Iterator<String> it = bans.keys(); it.hasNext();) {
        String uid = it.next();
        if (!ForumProtocol.validUid(uid)) continue;
        users.add(uid);
        String name = service.get("forumProfiles/" + uid).optString("name", "Thành viên");
        names.add(name + " · " + uid);
      }
      ui(screen, () -> {
        for (int i = 0; i < users.size(); i++) {
          String uid = users.get(i);
          button(screen.root, "Bỏ chặn " + names.get(i), () -> ban(screen, uid, false));
        }
        if (users.isEmpty()) screen.status.setText("Không có thành viên bị chặn.");
      });
    });
  }

  private void deleted() {
    Screen screen = page("Bài đã xóa");
    run(screen, false, service -> {
      JSONObject all = service.get("forumDeleteAudit");
      List<String> owners = new ArrayList<>(), ids = new ArrayList<>(), labels = new ArrayList<>();
      List<String> topicIds = new ArrayList<>(), postIds = new ArrayList<>();
      for (java.util.Iterator<String> uids = all.keys(); uids.hasNext();) {
        String uid = uids.next();
        JSONObject archive = all.optJSONObject(uid);
        if (archive == null) continue;
        for (java.util.Iterator<String> records = archive.keys(); records.hasNext();) {
          String id = records.next();
          JSONObject entry = archive.optJSONObject(id);
          if (entry == null) continue;
          JSONObject content = entry.optJSONObject("content");
          if (content == null) continue;
          owners.add(uid); ids.add(id);
          topicIds.add(entry.optString("topic"));
          postIds.add(entry.optString("post"));
          labels.add(content.has("title") ? content.optString("title") + "\n" + content.optString("body")
              : content.optString("text"));
        }
      }
      ui(screen, () -> {
        for (int i = 0; i < ids.size(); i++) {
          String uid = owners.get(i), id = ids.get(i);
          text(screen.root, labels.get(i));
          String topic = topicIds.get(i), post = postIds.get(i);
          button(screen.root, "Xem lịch sử chỉnh sửa", () -> {
            if (ForumProtocol.validId(topic)) editHistory(topic, post.isEmpty() ? "topic" : post);
          });
        }
        if (ids.isEmpty()) screen.status.setText("Không có nội dung đã xóa.");
      });
    });
  }

  private void edits() {
    Screen screen = page("Lịch sử chỉnh sửa");
    run(screen, false, service -> {
      JSONObject all = service.get("forumEditAudit");
      List<String> topics = new ArrayList<>(), posts = new ArrayList<>();
      for (java.util.Iterator<String> it = all.keys(); it.hasNext();) {
        String topic = it.next();
        JSONObject list = all.optJSONObject(topic);
        if (list == null) continue;
        for (java.util.Iterator<String> pi = list.keys(); pi.hasNext();) {
          String post = pi.next();
          topics.add(topic); posts.add(post);
        }
      }
      ui(screen, () -> {
        for (int i = 0; i < topics.size(); i++) {
          String topic = topics.get(i), post = posts.get(i);
          button(screen.root, "Xem lịch sử " + topic + "/" + post, () -> editHistory(topic, post));
        }
        if (topics.isEmpty()) screen.status.setText("Không có lịch sử chỉnh sửa.");
      });
    });
  }

  private void editHistory(String topic, String post) {
    Screen screen = page("Lịch sử chỉnh sửa");
    run(screen, false, service -> {
      JSONObject versions = service.get("forumEditAudit/" + topic + "/" + post);
      List<String> ids = ForumProtocol.newest(versions);
      ui(screen, () -> {
        for (String id : ids) {
          JSONObject version = versions.optJSONObject(id);
          if (version == null) continue;
          JSONObject content = version.optJSONObject("content");
          if (content == null) continue;
          text(screen.root, content.has("title")
              ? content.optString("title") + "\n" + content.optString("body")
              : content.optString("text"));
        }
        if (ids.isEmpty()) screen.status.setText("Không có lịch sử chỉnh sửa.");
      });
    });
  }

  private void names() {
    Screen screen = page("Lịch sử tên");
    EditText input = input(screen.root, "UID thành viên");
    button(screen.root, "Xem", () -> {
      String uid = input.getText().toString().trim();
      if (!ForumProtocol.validUid(uid)) { update(screen, "UID không hợp lệ."); return; }
      run(screen, false, service -> {
        JSONObject request = service.get("requests/" + uid);
        JSONObject profile = service.get("forumProfiles/" + uid);
        JSONObject history = service.get("forumNameHistory/" + uid);
        JSONObject legacy = service.get("forumLegacyNames/" + uid);
        StringBuilder out = new StringBuilder();
        out.append(request.optString("name")).append("\n").append(profile.optString("name"));
        for (java.util.Iterator<String> it = history.keys(); it.hasNext();) {
          JSONObject record = history.optJSONObject(it.next());
          if (record != null) out.append("\n").append(record.optString("previous"));
        }
        for (java.util.Iterator<String> it = legacy.keys(); it.hasNext();) {
          JSONObject record = legacy.optJSONObject(it.next());
          if (record != null) out.append("\n").append(record.optString("name"));
        }
        ui(screen, () -> text(screen.root, out.toString()));
      });
    });
  }

  private void migrate() {
    Screen screen = page("Chuyển dữ liệu cũ");
    button(screen.root, "Chuyển dữ liệu", () -> run(screen, true, service -> {
      JSONObject enrolled = service.get("requests");
      for (java.util.Iterator<String> identities = enrolled.keys(); identities.hasNext();) {
        JSONObject requestRecord = enrolled.optJSONObject(identities.next());
        if (requestRecord == null) continue;
        String requestId = requestRecord.optString("request");
        if (!requestId.matches("SFREQ2\\.[0-9a-f]{64}")) continue;
        String marker = "forumEnrollmentIndex/" + requestId.substring(7);
        if (!service.flag(marker)) service.putBoolean(marker, true);
      }
      JSONObject topics = service.get("forumTopics");
      JSONObject comments = service.get("forumPosts");
      int migrated = 0;
      for (java.util.Iterator<String> ids = topics.keys(); ids.hasNext();) {
        String topic = ids.next();
        JSONObject record = topics.optJSONObject(topic);
        if (record == null || !ForumProtocol.validId(topic)) continue;
        migrated += migrateName(service, "forumTopics/" + topic, record);
      }
      for (java.util.Iterator<String> threads = comments.keys(); threads.hasNext();) {
        String topic = threads.next();
        JSONObject posts = comments.optJSONObject(topic);
        if (posts == null || !ForumProtocol.validId(topic)) continue;
        for (java.util.Iterator<String> ids = posts.keys(); ids.hasNext();) {
          String post = ids.next();
          JSONObject record = posts.optJSONObject(post);
          if (record == null || !ForumProtocol.validId(post)) continue;
          migrated += migrateName(service, "forumPosts/" + topic + "/" + post, record);
        }
      }
      JSONObject scannedTopics = service.get("forumTopics");
      JSONObject scannedPosts = service.get("forumPosts");
      for (java.util.Iterator<String> ti = scannedTopics.keys(); ti.hasNext();) {
        JSONObject entry = scannedTopics.optJSONObject(ti.next());
        if (entry != null && (entry.has("name") || entry.has("authorName")
            || !entry.has("lastAt") || !entry.has("rankAt"))) {
          throw new IllegalStateException("Còn tên cũ chưa chuyển.");
        }
      }
      for (java.util.Iterator<String> ti = scannedPosts.keys(); ti.hasNext();) {
        JSONObject posts = scannedPosts.optJSONObject(ti.next());
        if (posts == null) continue;
        for (java.util.Iterator<String> pi = posts.keys(); pi.hasNext();) {
          JSONObject entry = posts.optJSONObject(pi.next());
          if (entry != null && (entry.has("name") || entry.has("authorName"))) {
            throw new IllegalStateException("Còn tên cũ chưa chuyển.");
          }
        }
      }
      service.putBoolean("forumMigration/ready", true);
      int count = migrated;
      update(screen, "Đã chuyển " + count + " bản ghi.");
    }));
  }

  private int migrateName(FirebaseRest service, String path, JSONObject record) throws Exception {
    String name = record.optString("name");
    if (name.isEmpty()) name = record.optString("authorName");
    String uid = record.optString("uid");
    if (!ForumProtocol.validUid(uid)) return 0;
    boolean isTopic = path.startsWith("forumTopics/");
    boolean needsActivity = isTopic && !record.has("lastAt");
    boolean needsRank = isTopic && !record.has("rankAt");
    if (name.isEmpty() && !needsActivity && !needsRank) return 0;
    if (!name.isEmpty()) service.put("forumLegacyNames/" + uid + "/" + ForumProtocol.id(),
        new JSONObject().put("name", name).put("capturedAt", FirebaseRest.timestamp()));
    JSONObject updated = new JSONObject(record.toString());
    updated.remove("name");
    updated.remove("authorName");
    if (needsActivity) updated.put("lastAt", record.getLong("createdAt"));
    if (needsRank) updated.put("rankAt",
        record.optLong("lastAt", record.optLong("createdAt")));
    service.put(path, updated);
    return 1;
  }

  private int purgeExpired(FirebaseRest service) throws Exception {
    JSONObject records = service.get("forumDeleteAudit");
    long server = service.serverTimeMillis();
    if (server <= 72L * 60 * 60 * 1000) return 0;
    long cutoff = server - 72L * 60 * 60 * 1000;
    int cleaned = 0;
    for (java.util.Iterator<String> users = records.keys(); users.hasNext();) {
      String uid = users.next();
      JSONObject archive = records.optJSONObject(uid);
      if (archive == null) continue;
      for (java.util.Iterator<String> keys = archive.keys(); keys.hasNext();) {
        String id = keys.next();
        JSONObject entry = archive.optJSONObject(id);
        if (entry == null || entry.optLong("deletedAt", 0) <= 0
            || entry.optLong("deletedAt") > cutoff) continue;
        String topic = entry.optString("topic");
        String post = entry.optString("post");
        if (ForumProtocol.validId(topic) && post.isEmpty()
            && service.get("forumTopics/" + topic).length() == 0) {
          for (String prefix : new String[]{"forumPosts/", "forumTopicLikes/", "forumPostLikes/",
              "forumPollVotes/", "forumPollGuards/", "forumPinnedPosts/", "forumReports/", "forumPinnedTopics/",
              "forumAnnouncements/"}) {
            service.deleteCleanupData(prefix + topic);
          }
        }
        service.deleteCleanupData("forumDeleteAudit/" + uid + "/" + id);
        cleaned++;
        if (!ForumProtocol.validId(topic)) continue;
        JSONObject history = service.get("forumEditAudit/" + topic + "/" + (post.isEmpty() ? "topic" : post));
        for (java.util.Iterator<String> revisions = history.keys(); revisions.hasNext();) {
          service.deleteCleanupData("forumEditAudit/" + topic + "/" + (post.isEmpty() ? "topic" : post)
              + "/" + revisions.next());
        }
      }
    }
    return cleaned;
  }

  private void cleanup() {
    Screen screen = page("Dọn bản lưu");
    button(screen.root, "Xóa bản lưu quá hạn", () -> run(screen, true, service -> {
      int count = purgeExpired(service);
      update(screen, "Đã dọn " + count + " bản lưu.");
    }));
  }
}
